package com.ultratv.tv.nativeapp.ui.player.engine

import android.content.Context
import android.net.Uri
import android.view.View
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Moteur LibVLC : MPEG-TS difficiles, AC3/E-AC3/DTS sans passthrough, MKV exotiques, flux mal formés.
 * `--quiet` : LibVLC ne journalise rien (ses messages peuvent contenir l'URL du flux).
 */
class VlcEngine(private val ctx: Context, override val config: EngineConfig) : PlayerEngine {
    override val kind = EngineKind.VLC
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<EngineEvent> = _events

    private val layout = VLCVideoLayout(ctx)
    override val view: View get() = layout

    private val libVlc: LibVLC
    private val mp: MediaPlayer
    private var firstFrame = false

    /**
     * Direct : 2 s (borné par le tampon minimum) — 1 s (1.2.37) laissait trop peu de marge sur un fournisseur irrégulier
     * (saccades), 5 s (avant) retardait la première image.
     */
    private val networkCachingMs = if (config.isLive) maxOf(config.buffer.startMs, 2_000).coerceAtMost(maxOf(config.buffer.minMs, 2_000)) else config.buffer.vlcNetworkCachingMs
    @Volatile private var released = false

    init {
        val b = config.buffer
        val opts = arrayListOf(
            // VLC peut SAUTER une image en retard (par défaut) : l'interdire faisait s'accumuler le retard sur une box
            // modeste — lecture qui « lague ».
            "--quiet",
            "--network-caching=$networkCachingMs", "--live-caching=${b.vlcLiveCachingMs}", "--file-caching=${b.vlcFileCachingMs}",
            "--http-user-agent=${config.userAgent}", "--codec=all",
        )
        // Affichage direct du décodeur matériel (MediaCodec DR) GARDÉ : le désactiver (avant, en mode Auto) recopiait
        // chaque image par le processeur — très lourd sur une box. Seul le mode Logiciel n'utilise pas MediaCodec.
        if (config.decoder == DecoderMode.SOFTWARE) opts += "--no-mediacodec-dr"
        opts += com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyleMapper.toVlcOptions(config.subtitleStyle)
        libVlc = LibVLC(ctx, opts)
        mp = MediaPlayer(libVlc)
        mp.attachViews(layout, null, false, false)
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Buffering -> _events.tryEmit(if (e.buffering >= 100f) EngineEvent.Ready else EngineEvent.Buffering)
                MediaPlayer.Event.Playing -> _events.tryEmit(EngineEvent.Ready)
                MediaPlayer.Event.Vout -> if (e.voutCount > 0 && !firstFrame) { firstFrame = true; _events.tryEmit(EngineEvent.FirstFrame); autoSelectTracks(); applySavedDelay() }
                // Les pistes arrivent au fil de l'analyse du flux (souvent APRÈS la première image) : on réessaie à chaque ajout.
                MediaPlayer.Event.ESAdded -> if (!released) autoSelectTracks()
                MediaPlayer.Event.EndReached -> _events.tryEmit(EngineEvent.Ended)
                // LibVLC n'expose pas de code : on classe selon qu'une image est déjà passée.
                MediaPlayer.Event.EncounteredError -> _events.tryEmit(EngineEvent.Error(if (firstFrame) PlayErrorKind.NETWORK else PlayErrorKind.FORMAT))
            }
        }
    }

    @Volatile private var audioManual = false
    @Volatile private var textManual = false
    private var lastAutoAudio: String? = null
    private var textOffDone = false
    private var lastAutoText: String? = null

    /**
     * Choix automatique audio / sous-titres selon les langues préférées. LibVLC n'a aucune préférence automatique :
     * il joue la première piste (souvent l'anglaise). Jamais de remplacement d'un choix manuel de la séance.
     */
    private fun autoSelectTracks() {
        val tc = com.ultratv.tv.nativeapp.data.subtitles.TrackChoice
        if (!audioManual && config.preferredAudio.isNotEmpty()) {
            val tracks = mp.audioTracks.orEmpty().filter { it.id >= 0 }
            if (tracks.size > 1) tc.bestIndex(tracks.map { com.ultratv.tv.nativeapp.data.subtitles.TrackChoice.Candidate(null, it.name) }, config.preferredAudio)?.let { tracks[it] }
                ?.takeIf { it.id != mp.audioTrack && lastAutoAudio != it.id.toString() }?.let { lastAutoAudio = it.id.toString(); mp.audioTrack = it.id }
        }
        if (!textManual) {
            val tracks = mp.spuTracks.orEmpty().filter { it.id >= 0 }
            if (config.textOff) { if (!textOffDone && tracks.isNotEmpty()) { textOffDone = true; mp.spuTrack = -1 } }
            else if (config.preferredText.isNotEmpty()) tc.bestIndex(tracks.map { com.ultratv.tv.nativeapp.data.subtitles.TrackChoice.Candidate(null, it.name) }, config.preferredText)?.let { tracks[it] }
                ?.takeIf { it.id != mp.spuTrack && lastAutoText != it.id.toString() }?.let { lastAutoText = it.id.toString(); mp.spuTrack = it.id }
        }
    }

    private fun applySavedDelay() { if (config.subtitleStyle.delayMs != 0) setSubtitleDelay(config.subtitleStyle.delayMs) }

    /** Les options `--freetype-*` se fixent à la création de LibVLC : appliquées au prochain démarrage du moteur. */
    override fun applySubtitleStyle(style: com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyle): Boolean = false
    override fun setSubtitleDelay(ms: Int): Boolean { mp.setSpuDelay(ms * 1000L); return true }
    override fun addExternalSubtitle(path: String): Boolean =
        mp.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(java.io.File(path)), true).also { textManual = true }

    /** Zapping : le MÊME LibVLC / MediaPlayer enchaîne le média suivant (options de cache posées par média). */
    override val reusable: Boolean get() = true

    override fun load(url: String, startPositionMs: Long) {
        if (released) return
        firstFrame = false
        audioManual = false; textManual = false; textOffDone = false; lastAutoAudio = null; lastAutoText = null
        // Flux précédent arrêté AVANT d'ouvrir le suivant (connexion unique du fournisseur).
        if (mp.media != null) runCatching { mp.stop() }
        val media = Media(libVlc, Uri.parse(url)).apply {
            when (config.decoder) {
                DecoderMode.HARDWARE -> setHWDecoderEnabled(true, true)
                DecoderMode.SOFTWARE -> setHWDecoderEnabled(false, false)
                DecoderMode.AUTO -> setHWDecoderEnabled(true, false)
            }
            addOption(":network-caching=$networkCachingMs")
            addOption(":live-caching=${config.buffer.vlcLiveCachingMs}")
            addOption(":file-caching=${config.buffer.vlcFileCachingMs}")
            if (maxHeight != Int.MAX_VALUE) addOption(":preferred-resolution=${maxHeight.coerceAtLeast(240)}")
            maxBitrateBps?.let { addOption(":adaptive-maxbitrate=$it") }
            if (startPositionMs > 5_000) addOption(":start-time=${startPositionMs / 1000}")
        }
        mp.media = media
        media.release()
        mp.play()
    }

    override fun play() { mp.play() }
    override fun pause() { mp.pause() }
    override fun seekTo(ms: Long) { mp.time = ms }
    // Lus par les boucles du lecteur (500 ms, 10 s) : jamais sur un MediaPlayer natif déjà libéré (plantage natif).
    override val isPlaying get() = !released && mp.isPlaying
    override val positionMs get() = if (released) 0L else mp.time.coerceAtLeast(0)
    override val durationMs get() = if (released) -1L else mp.length.takeIf { it > 0 } ?: -1L

    private fun vlcTracks(list: Array<org.videolan.libvlc.MediaPlayer.TrackDescription>?, selected: Int): List<TrackInfo> {
        val tc = com.ultratv.tv.nativeapp.data.subtitles.TrackChoice
        val ui = java.util.Locale(config.uiLanguage)
        // L'entrée « Désactiver » (id -1) est retirée : le panneau propose son propre « Désactivés ».
        return list.orEmpty().filter { it.id >= 0 }.mapIndexed { i, t ->
            TrackInfo(t.id.toString(), tc.humanLabel(null, t.name, null, 0, false, i + 1, ui), t.id == selected, tc.languageFromLabel(t.name))
        }
    }
    override fun audioTracks(): List<TrackInfo> = if (released) emptyList() else vlcTracks(mp.audioTracks, mp.audioTrack)
    override fun subtitleTracks(): List<TrackInfo> = if (released) emptyList() else vlcTracks(mp.spuTracks, mp.spuTrack)
    override fun selectAudio(id: String) { audioManual = true; mp.audioTrack = id.toInt() }
    override fun selectSubtitle(id: String?) { textManual = true; mp.spuTrack = id?.toInt() ?: -1 }

    override fun setAspect(mode: AspectMode) {
        mp.setAspectRatio(null)
        when (mode) {
            AspectMode.FIT -> mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectMode.FILL -> mp.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
            AspectMode.ZOOM -> mp.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            AspectMode.R16_9 -> mp.setAspectRatio("16:9")
            AspectMode.R4_3 -> mp.setAspectRatio("4:3")
        }
    }
    override fun setSpeed(speed: Float) { mp.rate = speed }
    override val hasVideo get() = !released && mp.videoTracksCount > 0
    private var maxHeight = Int.MAX_VALUE
    private var maxBitrateBps: Int? = null

    /** LibVLC n'a pas de plafond dur : on borne la variante HLS/DASH choisie (pris en compte au prochain [load]). */
    override fun limitQuality(maxHeight: Int, maxBitrateBps: Int?) { this.maxHeight = maxHeight; this.maxBitrateBps = maxBitrateBps }

    override fun stats(): EngineStats {
        if (released) return EngineStats()
        val v = mp.currentVideoTrack
        val s = mp.media?.stats
        return EngineStats(
            resolution = v?.let { "${it.width}×${it.height}" }, videoCodec = v?.codec,
            frameRate = v?.frameRateNum?.takeIf { it > 0 && v.frameRateDen > 0 }?.let { it.toFloat() / v.frameRateDen },
            videoBitrateKbps = s?.inputBitrate?.let { (it * 8000).toInt() }, audioCodec = null, audioChannels = null,
            bufferedSeconds = null, droppedFrames = s?.lostPictures, hardwareDecoding = config.decoder != DecoderMode.SOFTWARE,
        )
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { mp.setEventListener(null) }
        // Arrêt / détachement / libération NATIFS hors du fil principal : avec l'affichage direct MediaCodec (gardé en
        // 1.2.48), mp.stop() et detachViews() attendent le fil de sortie vidéo de LibVLC, parfois plusieurs secondes —
        // le fil principal bloqué, l'écran suivant restait sur « chargement » et la relance de l'appli ne répondait plus.
        // C'est la pratique de VLC lui-même (libération dans un fil dédié).
        Thread({
            runCatching { mp.stop() }
            runCatching { mp.detachViews() }
            runCatching { mp.release() }
            runCatching { libVlc.release() }
        }, "vlc-release").apply { isDaemon = true }.start()
    }
}
