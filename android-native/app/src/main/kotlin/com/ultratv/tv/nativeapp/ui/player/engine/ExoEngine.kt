package com.ultratv.tv.nativeapp.ui.player.engine

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Moteur Media3 / ExoPlayer. Décodage : matériel (extensions OFF), logiciel (FFmpeg audio + codecs logiciels), auto. */
@UnstableApi
class ExoEngine(private val ctx: Context, override val config: EngineConfig) : PlayerEngine {
    override val kind = EngineKind.EXO
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<EngineEvent> = _events

    private val player: ExoPlayer
    private val playerView: PlayerView = PlayerView(ctx).apply { useController = false; setKeepContentOnPlayerReset(false) }
    override val view: View get() = playerView

    private val hardwareFirst = MediaCodecSelector { mime, secure, tunneling ->
        val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        val hw = all.filter { !it.softwareOnly }
        if (hw.isNotEmpty()) hw else all
    }
    private val softwareFirst = MediaCodecSelector { mime, secure, tunneling ->
        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
    }

    init {
        val b = config.buffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b.minMs, b.maxMs.coerceAtLeast(b.minMs), b.startMs, b.rebufferMs)
            .setTargetBufferBytes(b.maxBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val http = DefaultHttpDataSource.Factory().setUserAgent(config.userAgent).setAllowCrossProtocolRedirects(true)
            // Direct : délais courts — une chaîne morte ou un serveur qui ne répond plus est détecté vite, puis la
            // session se reconnecte toute seule (avant : 10 s + 1,5 s + 10 s avant l'écran d'erreur).
            .setConnectTimeoutMs(if (config.isLive) 6_000 else 10_000).setReadTimeoutMs(if (config.isLive) 8_000 else 15_000)
        val mediaSources = DefaultMediaSourceFactory(ctx).setDataSourceFactory(
            androidx.media3.datasource.DefaultDataSource.Factory(ctx, http),
        )
        val renderers = DefaultRenderersFactory(ctx).apply {
            setEnableDecoderFallback(true)
            when (config.decoder) {
                DecoderMode.HARDWARE -> { setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF); setMediaCodecSelector(hardwareFirst) }
                DecoderMode.SOFTWARE -> { setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER); setMediaCodecSelector(softwareFirst) }
                DecoderMode.AUTO -> setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
        }
        player = ExoPlayer.Builder(ctx, renderers).setLoadControl(loadControl).setMediaSourceFactory(mediaSources).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> _events.tryEmit(EngineEvent.Buffering)
                        Player.STATE_READY -> _events.tryEmit(EngineEvent.Ready)
                        Player.STATE_ENDED -> _events.tryEmit(EngineEvent.Ended)
                    }
                }
                override fun onRenderedFirstFrame() { _events.tryEmit(EngineEvent.FirstFrame) }
                override fun onPlayerError(error: PlaybackException) {
                    val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    _events.tryEmit(EngineEvent.Error(PlaybackPlanner.classifyExo(error.errorCode, status)))
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) { applyFrameRate() }
                // Les pistes ne sont connues qu'une fois le conteneur analysé (et peuvent arriver plus tard) : le choix
                // automatique se refait à chaque changement, tant que l'utilisateur n'a rien choisi à la main.
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { autoSelectTracks() }
            })
        }
        playerView.player = player
        playerView.subtitleView?.apply { setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false) }
        applySubtitleStyle(config.subtitleStyle)
        // Choix automatique des pistes : listes ordonnées de langues préférées (la première disponible l'emporte).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*config.preferredAudio.flatMap { languageVariants(it) }.distinct().toTypedArray())
            .setPreferredTextLanguages(*config.preferredText.flatMap { languageVariants(it) }.distinct().toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, config.textOff)
            .build()
    }

    /** « fr » → fr, fra, fre : les flux étiquettent en 639-1, 639-2/T ou 639-2/B. */
    private fun languageVariants(code: String): List<String> {
        val c = com.ultratv.tv.nativeapp.data.subtitles.TrackChoice.normalizeLanguage(code) ?: return emptyList()
        val t = runCatching { java.util.Locale(c).isO3Language }.getOrNull()
        val b = when (c) { "fr" -> "fre"; "de" -> "ger"; "nl" -> "dut"; "zh" -> "chi"; "cs" -> "cze"; "el" -> "gre"; "ro" -> "rum"; "sk" -> "slo"; "fa" -> "per"; else -> null }
        return listOfNotNull(c, t, b)
    }

    private var audioManual = false
    private var textManual = false
    private var lastAutoAudio: String? = null
    private var lastAutoText: String? = null

    private data class Flat(val gi: Int, val ti: Int, val group: androidx.media3.common.Tracks.Group, val cand: com.ultratv.tv.nativeapp.data.subtitles.TrackChoice.Candidate)

    private fun flat(type: Int): List<Flat> = player.currentTracks.groups.withIndex().filter { it.value.type == type }.flatMap { (gi, g) ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { ti -> val f = g.getTrackFormat(ti); Flat(gi, ti, g, com.ultratv.tv.nativeapp.data.subtitles.TrackChoice.Candidate(f.language, f.label)) }
    }

    /**
     * Media3 ne compare que la balise de langue : une piste sans balise (« und ») mais nommée « French » / « VFF »
     * lui échappe. On complète donc par le nom, sans jamais écraser un choix manuel.
     */
    private fun autoSelectTracks() {
        val tc = com.ultratv.tv.nativeapp.data.subtitles.TrackChoice
        if (!audioManual && config.preferredAudio.isNotEmpty()) {
            val a = flat(C.TRACK_TYPE_AUDIO)
            if (a.size > 1) tc.bestIndex(a.map { it.cand }, config.preferredAudio)?.let { a[it] }?.takeIf { !it.group.isTrackSelected(it.ti) && lastAutoAudio != "${it.gi}:${it.ti}" }?.let {
                lastAutoAudio = "${it.gi}:${it.ti}"
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.ti)).build()
            }
        }
        if (!textManual && !config.textOff && config.preferredText.isNotEmpty()) {
            val t = flat(C.TRACK_TYPE_TEXT)
            if (t.isNotEmpty()) tc.bestIndex(t.map { it.cand }, config.preferredText)?.let { t[it] }?.takeIf { !it.group.isTrackSelected(it.ti) && lastAutoText != "${it.gi}:${it.ti}" }?.let {
                lastAutoText = "${it.gi}:${it.ti}"
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.ti)).build()
            }
        }
    }

    override fun applySubtitleStyle(style: com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyle): Boolean {
        val c = com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyleMapper.toCaption(style)
        playerView.subtitleView?.apply {
            setStyle(androidx.media3.ui.CaptionStyleCompat(c.foregroundArgb, c.backgroundArgb, android.graphics.Color.TRANSPARENT, c.edgeType, c.edgeArgb, null))
            setFractionalTextSize(c.textSizeFraction)
            setBottomPaddingFraction(c.bottomPaddingFraction)
        }
        return true
    }

    override fun addExternalSubtitle(path: String): Boolean {
        val cur = player.currentMediaItem ?: return false
        val sub = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(java.io.File(path)))
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val pos = player.currentPosition
        textManual = true
        player.setMediaItem(cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), pos)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        player.prepare()
        return true
    }

    override val reusable: Boolean get() = true

    override fun load(url: String, startPositionMs: Long) {
        // Flux précédent arrêté et libéré AVANT d'ouvrir le suivant (connexion unique du fournisseur).
        audioManual = false; textManual = false; lastAutoAudio = null; lastAutoText = null
        if (player.mediaItemCount > 0) {
            player.stop()
            // Moteur réutilisé (zap) : les choix de pistes faits à la main sur la chaîne précédente ne la suivent pas.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, config.textOff)
                .build()
        }
        val item = MediaItem.Builder().setUri(url).apply {
            if (config.isLive) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(config.buffer.liveOffsetMs).build())
        }.build()
        player.setMediaItem(item)
        player.prepare()
        if (startPositionMs > 5_000) player.seekTo(startPositionMs)
        player.play()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) = player.seekTo(ms)
    override val isPlaying get() = player.isPlaying
    override val positionMs get() = player.currentPosition
    override val durationMs get() = if (player.duration == C.TIME_UNSET) -1L else player.duration

    private fun tracks(type: Int): List<TrackInfo> = player.currentTracks.groups.withIndex().filter { it.value.type == type }.flatMap { (gi, g) ->
        (0 until g.length).map { ti ->
            val f = g.getTrackFormat(ti)
            val tc = com.ultratv.tv.nativeapp.data.subtitles.TrackChoice
            val label = tc.humanLabel(
                f.language, f.label, if (type == C.TRACK_TYPE_AUDIO) tc.codecLabel(f.sampleMimeType) else null, if (type == C.TRACK_TYPE_AUDIO) f.channelCount else 0,
                forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0, index = ti + 1, ui = java.util.Locale(config.uiLanguage),
            )
            TrackInfo("$gi:$ti", label, g.isTrackSelected(ti), tc.normalizeLanguage(f.language) ?: tc.languageFromLabel(f.label))
        }
    }
    override fun audioTracks() = tracks(C.TRACK_TYPE_AUDIO)
    override fun subtitleTracks() = tracks(C.TRACK_TYPE_TEXT)

    private fun select(id: String, type: Int, enableText: Boolean = false) {
        val (gi, ti) = id.split(':').map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(gi) ?: return
        // Ne toucher à l'état des sous-titres que pour une piste de sous-titres (choisir un audio ne doit pas les réactiver).
        val b = player.trackSelectionParameters.buildUpon()
        if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enableText)
        player.trackSelectionParameters = b
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti)).build()
    }
    override fun selectAudio(id: String) { audioManual = true; select(id, C.TRACK_TYPE_AUDIO) }
    override fun selectSubtitle(id: String?) {
        textManual = true
        if (id == null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        else select(id, C.TRACK_TYPE_TEXT, enableText = true)
    }

    override fun setAspect(mode: AspectMode) {
        playerView.resizeMode = when (mode) {
            AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectMode.R16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectMode.R4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        }
    }
    override fun setSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }
    override val hasVideo get() = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
    override fun limitQuality(maxHeight: Int, maxBitrateBps: Int?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxHeight.coerceAtLeast(240))
            .setMaxVideoBitrate(maxBitrateBps ?: Int.MAX_VALUE)
            .build()
    }

    override fun stats(): EngineStats {
        val v = player.videoFormat; val a = player.audioFormat
        val counters = player.videoDecoderCounters
        val hw = v?.sampleMimeType?.let { true }
        return EngineStats(
            resolution = v?.let { "${it.width}×${it.height}" }, videoCodec = v?.sampleMimeType?.substringAfter('/'),
            frameRate = v?.frameRate?.takeIf { it > 0 }, videoBitrateKbps = v?.bitrate?.takeIf { it > 0 }?.div(1000),
            audioCodec = a?.sampleMimeType?.substringAfter('/'), audioChannels = a?.channelCount?.takeIf { it > 0 },
            bufferedSeconds = ((player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000).toInt(),
            droppedFrames = counters?.let { it.maxConsecutiveDroppedBufferCount.let { _ -> it.droppedBufferCount } },
            hardwareDecoding = hw?.let { config.decoder != DecoderMode.SOFTWARE },
        )
    }

    /** Adapte la fréquence de l'écran à celle de la vidéo (24/25/50 ips), si l'option est active. */
    private fun applyFrameRate() {
        if (!config.autoFrameRate) return
        val act = (ctx as? Activity) ?: return
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: return
        @Suppress("DEPRECATION") val display = act.windowManager.defaultDisplay ?: return
        // Fréquence actuelle déjà compatible (multiple entier, ex. 50 Hz pour 25 i/s) : pas de bascule HDMI — elle
        // coûte 1 à 3 s d'écran noir et se produisait à chaque zap entre chaînes à 25 et 50 i/s.
        val currentHz = display.mode?.refreshRate ?: 0f
        if (currentHz > 0f) {
            val ratio = currentHz / fps
            if (ratio >= 0.99f && kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.01f) return
        }
        val target = display.supportedModes.minByOrNull { m ->
            val multiple = (m.refreshRate / fps).coerceAtLeast(1f)
            kotlin.math.abs(m.refreshRate - fps * kotlin.math.round(multiple))
        } ?: return
        val lp = act.window.attributes
        if (lp.preferredDisplayModeId != target.modeId) { lp.preferredDisplayModeId = target.modeId; act.window.attributes = lp }
    }

    override fun release() {
        runCatching { playerView.player = null }
        runCatching { player.release() }
    }
}
