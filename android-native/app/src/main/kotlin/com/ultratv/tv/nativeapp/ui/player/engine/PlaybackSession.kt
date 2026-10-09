package com.ultratv.tv.nativeapp.ui.player.engine

import android.content.Context
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import com.ultratv.tv.nativeapp.adaptive.NetworkMonitor
import com.ultratv.tv.nativeapp.adaptive.PlaybackAdapter
import com.ultratv.tv.nativeapp.adaptive.ResolvedPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Phase { LOADING, PLAYING, ENDED, ERROR }

/** Avis discret à l'utilisateur (toast) ; le texte est localisé par l'interface. */
enum class Notice { USING_EXO, USING_VLC, USING_SOFTWARE, RETRYING }

data class SessionState(
    val phase: Phase = Phase.LOADING,
    val error: PlayErrorKind? = null,
    val combo: Combo = Combo(EngineKind.EXO, DecoderMode.AUTO),
    val bufferPreset: BufferPreset = BufferPreset.AUTO,
    val hasPicture: Boolean = false,
)

/**
 * Une session de lecture : UN seul moteur vivant à la fois (connexion unique du fournisseur : `release()`
 * précède toujours l'ouverture suivante). Gère le repli automatique moteur/décodage, la mémorisation par
 * chaîne, la nouvelle tentative après un 403 et l'adaptation du débit pendant la lecture.
 */
/** Direct bloqué en chargement après avoir joué : délai avant reconnexion. */
private const val STALL_MS = 12_000L

@UnstableApi
class PlaybackSession(
    private val ctx: Context,
    private val scope: CoroutineScope,
    val container: FrameLayout,
    private val settings: () -> ResolvedPlayback,
    private val memory: ChannelPlaybackMemory,
    private val network: NetworkMonitor,
    private val isLive: Boolean,
    private val autoFrameRate: Boolean,
    private val userAgent: String,
    /** Style des sous-titres et langues préférées au moment du lancement du moteur. */
    private val subtitles: () -> com.ultratv.tv.nativeapp.data.prefs.SubtitleSettings = { com.ultratv.tv.nativeapp.data.prefs.SubtitleSettings() },
    /** Langue de l'interface (ISO 639-1) : langue audio par défaut quand aucune préférence n'est réglée. */
    private val uiLanguage: () -> String = { java.util.Locale.getDefault().language },
    private val engineFactory: (EngineKind, EngineConfig) -> PlayerEngine = { k, c -> if (k == EngineKind.EXO) ExoEngine(ctx, c) else VlcEngine(ctx, c) },
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    var engine: PlayerEngine? = null
        private set

    private var url = ""
    private var key: String? = null
    private var resumeMs = 0L
    private var tried = mutableSetOf<Combo>()
    private var sameRetries = 0
    private var combo = Combo(EngineKind.EXO, DecoderMode.AUTO)
    private var presetOverride: BufferPreset? = null
    private var eventsJob: Job? = null
    private var watchdog: Job? = null
    private var rememberJob: Job? = null
    private val adapter = PlaybackAdapter()
    private var firstFrame = false
    private var adaptJob: Job? = null
    private var manualSwitch = false
    private var launchedAtNs = 0L
    // Reconnexion automatique du direct (serveurs qui ferment la session, flux gelé).
    private var reconnects = 0
    private var everPlayed = false
    private var reconnectJob: Job? = null
    private var stallJob: Job? = null
    private var stableJob: Job? = null
    /** Nouvelle tentative / repli programmés par [onError] : annulés par un zap ou un nouveau moteur (double connexion sinon). */
    private var errorJob: Job? = null

    /**
     * Reconnexion automatique autorisée pour la lecture en cours : VRAI direct seulement. Un replay (« Depuis le
     * début ») ou le relais du différé ont une FIN normale — les relancer en boucle n'aurait pas de sens.
     * Fixé par le lecteur avant chaque [start].
     */
    var liveReconnect: Boolean = true
    private val reconnectAllowed get() = isLive && liveReconnect

    /** Ouvre [url]. [channelKey] = « fournisseur:chaîne » pour la mémoire par chaîne (null = pas de mémoire). */
    fun start(url: String, channelKey: String?, resumeMs: Long = 0) {
        this.url = url; this.key = channelKey; this.resumeMs = resumeMs
        tried = mutableSetOf(); sameRetries = 0; manualSwitch = false
        reconnects = 0; everPlayed = false; reconnectJob?.cancel(); stallJob?.cancel(); stableJob?.cancel(); errorJob?.cancel()
        val s = settings()
        presetOverride = channelKey?.let { memory.bufferPreset(it) }
        val next = PlaybackPlanner.initial(s.engine, s.decoder, channelKey?.let { memory.combo(it) })
        val e = engine
        // Zapping : même moteur, même décodage, même tampon → on enchaîne le flux sur le moteur EN PLACE au lieu de le
        // détruire et d'en recréer un (lecteur, vue, décodeurs : plusieurs centaines de ms sur une box modeste).
        // Réglages de sous-titres changés depuis la création du moteur : on le recrée (ils sont fixés à sa création).
        if (isLive && e != null && e.reusable && next == combo && bufferFor(s) == launchedBuffer && subtitles() == launchedSubs && _state.value.phase != Phase.ERROR) {
            watchdog?.cancel(); rememberJob?.cancel()
            tried = mutableSetOf(combo); firstFrame = false; launchedAtNs = System.nanoTime()
            _state.value = SessionState(Phase.LOADING, null, combo, presetOverride ?: s.bufferPreset, false)
            e.load(url, resumeMs)
        } else {
            combo = next
            launch(combo)
        }
        adaptJob?.cancel()
        adaptJob = scope.launch { while (true) { delay(5_000); applyQualityLimit(adapter.tick(System.currentTimeMillis())) } }
    }

    /** « Réessayer » / retour sur l'appli : tout repart de zéro (sinon, compteur à 8 = écran d'erreur à la 1re coupure). */
    fun retry() { tried = mutableSetOf(); sameRetries = 0; reconnects = 0; everPlayed = false; launch(combo) }

    /** Changement manuel (pilule « Lecteur ») : on le mémorise tout de suite pour cette chaîne. */
    fun switchTo(c: Combo) { manualSwitch = true; tried = mutableSetOf(); combo = c; key?.let { memory.remember(it, c, null) }; launch(c) }

    /** Choix manuel de la piste audio : appliqué, puis sa langue est retenue pour ce titre (jamais l'identifiant de piste). */
    fun selectAudio(id: String) {
        val e = engine ?: return
        val lang = e.audioTracks().firstOrNull { it.id == id }?.language
        e.selectAudio(id)
        if (lang != null) key?.let { memory.rememberAudioLang(it, lang) }
    }

    fun setBufferPreset(p: BufferPreset) { presetOverride = p; key?.let { memory.remember(it, null, p) }; launch(combo) }

    private fun launch(c: Combo) {
        eventsJob?.cancel(); watchdog?.cancel(); rememberJob?.cancel()
        // Nouveau moteur : une reconnexion ou une nouvelle tentative encore en attente rechargerait un flux qui joue.
        reconnectJob?.cancel(); stallJob?.cancel(); stableJob?.cancel(); errorJob?.cancel()
        releaseEngine()
        combo = c; tried += c; firstFrame = false; launchedAtNs = System.nanoTime()
        val s = settings()
        val buffer = bufferFor(s)
        launchedBuffer = buffer
        _state.value = SessionState(Phase.LOADING, null, c, presetOverride ?: s.bufferPreset, false)
        // Box basse : on libère les images en mémoire avant que le décodeur ne réclame la sienne.
        if (s.lowRam) runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
        val sub = subtitles()
        launchedSubs = sub
        // Sous-titres : jamais activés d'office ; seulement si l'utilisateur les avait activés la dernière fois.
        // Langue audio : choix manuel mémorisé pour ce titre > réglage Langues > langue de l'interface (jamais « la première piste »).
        val ui = uiLanguage()
        val tc = com.ultratv.tv.nativeapp.data.subtitles.TrackChoice
        val audioPref = tc.effectivePreferred(sub.languages.audio, key?.let { memory.audioLang(it) }, ui)
        val textPref = if (sub.autoOn) tc.effectivePreferred(sub.languages.text, null, ui) else emptyList()
        val cfg = EngineConfig(c.decoder, buffer, isLive, autoFrameRate, userAgent, sub.style, audioPref, textPref, textOff = !sub.autoOn, uiLanguage = ui)
        val e = runCatching { engineFactory(c.engine, cfg) }.getOrElse { onError(PlayErrorKind.UNKNOWN); return }
        engine = e
        container.removeAllViews(); container.addView(e.view, FrameLayout.LayoutParams(-1, -1))
        e.limitQuality(s.maxVideoHeight, PlaybackAdapter.scaleBitrate(s.maxVideoBitrateBps, adapter.level))
        eventsJob = scope.launch { e.events.collect { onEvent(it, e) } }
        e.load(url, resumeMs)
    }

    /** Tampon effectif du moteur lancé : un zap ne réutilise le moteur que si ce tampon n'a pas changé. */
    private var launchedBuffer: BufferParams? = null
    private var launchedSubs: com.ultratv.tv.nativeapp.data.prefs.SubtitleSettings? = null

    private fun bufferFor(s: ResolvedPlayback): BufferParams {
        val preset = presetOverride
        return if (preset != null) BufferPlanner.resolve(preset, CustomBuffer(), s.heapClassMb, s.lowRam) else s.buffer
    }

    private fun applyQualityLimit(level: Int) {
        val s = settings()
        engine?.limitQuality(s.maxVideoHeight, PlaybackAdapter.scaleBitrate(s.maxVideoBitrateBps, level))
    }

    private fun onEvent(ev: EngineEvent, e: PlayerEngine) {
        when (ev) {
            EngineEvent.Buffering -> if (firstFrame) {
                android.util.Log.i("UltraPlay", "rebuffer engine=${combo.engine}"); network.onRebuffer(); applyQualityLimit(adapter.onRebuffer(System.currentTimeMillis()))
                // Direct figé en chargement (serveur qui ne renvoie plus rien sans fermer) : reconnexion au bout de 12 s.
                if (reconnectAllowed && stallJob?.isActive != true) stallJob = scope.launch { delay(STALL_MS); reconnectLive() }
            } else Unit
            EngineEvent.Ready -> { stallJob?.cancel(); if (!firstFrame && watchdog?.isActive != true) armPictureWatchdog(e) }
            EngineEvent.FirstFrame -> {
                firstFrame = true; watchdog?.cancel(); stallJob?.cancel()
                everPlayed = true
                // 30 s de lecture stable : le compteur de reconnexions repart de zéro.
                stableJob?.cancel(); stableJob = scope.launch { delay(30_000); reconnects = 0 }
                // Mesure (jamais d'URL) : moteur, décodage et délai jusqu'à la première image.
                android.util.Log.i("UltraPlay", "firstFrame engine=${combo.engine} decoder=${combo.decoder} ms=${(System.nanoTime() - launchedAtNs) / 1_000_000}")
                _state.value = _state.value.copy(phase = Phase.PLAYING, error = null, hasPicture = true)
                // La combinaison est retenue pour la chaîne si elle a demandé un repli ou un choix manuel et tient 5 s.
                rememberJob?.cancel()
                rememberJob = scope.launch { delay(5_000); if (tried.size > 1 || manualSwitch) key?.let { memory.remember(it, combo, null) } }
                sameRetries = 0
            }
            // Direct : une « fin » est une session fermée par le serveur, pas la fin du programme → on se reconnecte.
            EngineEvent.Ended -> if (reconnectAllowed) reconnectLive() else _state.value = _state.value.copy(phase = Phase.ENDED)
            is EngineEvent.Error -> onError(ev.kind)
        }
    }

    /** Le son passe mais aucune image au bout de 8 s alors que le flux a une piste vidéo : on change de décodeur/moteur. */
    private fun armPictureWatchdog(e: PlayerEngine) {
        watchdog = scope.launch {
            delay(8_000)
            if (!firstFrame && e.hasVideo) onError(PlayErrorKind.NO_PICTURE)
        }
    }

    private fun onError(kind: PlayErrorKind) {
        android.util.Log.i("UltraPlay", "error kind=$kind engine=${combo.engine} decoder=${combo.decoder}")
        // Direct qui jouait : coupure du serveur ou du réseau → reconnexion, pas de changement de moteur.
        // VLC ne donne pas de code : après un rechargement, une coupure serveur arrive classée FORMAT ; si le flux a déjà
        // joué, c'est une coupure, pas un format illisible.
        val k = if (everPlayed && kind == PlayErrorKind.FORMAT && combo.engine == EngineKind.VLC) PlayErrorKind.NETWORK else kind
        if (reconnectAllowed && everPlayed && PlaybackPlanner.shouldReconnectLive(k)) { reconnectLive(); return }
        network.onError()
        val settingsNow = settings()
        errorJob?.cancel()
        errorJob = scope.launch {
            if (PlaybackPlanner.shouldRetrySame(kind) && sameRetries < 1) {
                sameRetries++
                _notices.tryEmit(Notice.RETRYING)
                releaseEngine()          // libère la connexion AVANT de rouvrir (connexion unique)
                delay(1_500)
                launch(combo)
                return@launch
            }
            if (PlaybackPlanner.shouldFallBack(kind)) {
                val next = PlaybackPlanner.next(settingsNow.engine, tried)
                if (next != null) {
                    _notices.tryEmit(if (next.engine == EngineKind.VLC) Notice.USING_VLC else if (next.decoder == DecoderMode.SOFTWARE) Notice.USING_SOFTWARE else Notice.USING_EXO)
                    launch(next)
                    return@launch
                }
            }
            releaseEngine()
            _state.value = _state.value.copy(phase = Phase.ERROR, error = kind)
        }
    }

    /** Reconnexion du direct sur la même adresse (délais croissants) ; au-delà, écran d'erreur. */
    private fun reconnectLive() {
        stallJob?.cancel(); stableJob?.cancel()
        if (reconnectJob?.isActive == true) return
        // Appli en arrière-plan (lecteur en pause) : surtout pas de rechargement — le son repartirait. Le retour sur
        // l'appli relance la lecture (retry) de toute façon.
        if (!com.ultratv.tv.nativeapp.ui.common.AppForeground.visible) return
        val wait = PlaybackPlanner.liveReconnectDelayMs(reconnects) ?: run {
            releaseEngine()
            _state.value = _state.value.copy(phase = Phase.ERROR, error = PlayErrorKind.NETWORK)
            return
        }
        reconnects++
        android.util.Log.i("UltraPlay", "live reconnect #$reconnects in ${wait}ms engine=${combo.engine}")
        _notices.tryEmit(Notice.RETRYING)
        reconnectJob = scope.launch {
            delay(wait)
            firstFrame = false; launchedAtNs = System.nanoTime(); watchdog?.cancel()
            val e = engine
            if (e != null && e.reusable) e.load(url, 0) else launch(combo)
        }
    }

    private fun releaseEngine() {
        engine?.let { runCatching { it.release() } }
        engine = null
        launchedBuffer = null
        container.removeAllViews()
    }

    fun release() {
        eventsJob?.cancel(); watchdog?.cancel(); rememberJob?.cancel(); adaptJob?.cancel()
        reconnectJob?.cancel(); stallJob?.cancel(); stableJob?.cancel(); errorJob?.cancel()
        releaseEngine()
    }
}
