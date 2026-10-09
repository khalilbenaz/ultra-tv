package com.ultratv.tv.nativeapp.ui.player

import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.flow.distinctUntilChanged
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.adaptive.AdaptiveProfile
import com.ultratv.tv.nativeapp.adaptive.NetworkMonitor
import com.ultratv.tv.nativeapp.adaptive.PlaybackResolver
import com.ultratv.tv.nativeapp.adaptive.ResolvedPlayback
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.EpgDao
import com.ultratv.tv.nativeapp.data.db.EpgEntity
import com.ultratv.tv.nativeapp.data.prefs.UserPrefs
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import com.ultratv.tv.nativeapp.data.recording.RecordingRepository
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.data.repo.TitleCleaner
import com.ultratv.tv.nativeapp.i18n.DesignStrings
import com.ultratv.tv.nativeapp.i18n.LocalDs
import com.ultratv.tv.nativeapp.i18n.LocalStrings
import com.ultratv.tv.nativeapp.i18n.recConnectionBusy
import com.ultratv.tv.nativeapp.ui.common.EpgClock
import com.ultratv.tv.nativeapp.ui.common.ModalFocusScope
import com.ultratv.tv.nativeapp.ui.common.Toaster
import com.ultratv.tv.nativeapp.ui.common.design
import com.ultratv.tv.nativeapp.ui.design.DIcon
import com.ultratv.tv.nativeapp.ui.mobile.lockOrientation
import com.ultratv.tv.nativeapp.ui.mobile.MobileControls
import com.ultratv.tv.nativeapp.ui.mobile.GestureHudView
import com.ultratv.tv.nativeapp.ui.mobile.enterPip
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import com.ultratv.tv.nativeapp.ui.design.FocusSurface
import com.ultratv.tv.nativeapp.ui.design.LiveBadge
import com.ultratv.tv.nativeapp.ui.design.LogoBox
import com.ultratv.tv.nativeapp.ui.design.Manrope
import com.ultratv.tv.nativeapp.ui.design.PillButton
import com.ultratv.tv.nativeapp.ui.design.Sora
import com.ultratv.tv.nativeapp.ui.design.Ux
import com.ultratv.tv.nativeapp.ui.design.spx
import com.ultratv.tv.nativeapp.ui.player.engine.AspectMode
import com.ultratv.tv.nativeapp.ui.player.engine.BufferPreset
import com.ultratv.tv.nativeapp.ui.player.engine.ChannelPlaybackMemory
import com.ultratv.tv.nativeapp.ui.player.engine.Combo
import com.ultratv.tv.nativeapp.ui.player.engine.DecoderMode
import com.ultratv.tv.nativeapp.ui.player.engine.EngineKind
import com.ultratv.tv.nativeapp.ui.player.engine.Notice
import com.ultratv.tv.nativeapp.ui.player.engine.Phase
import com.ultratv.tv.nativeapp.ui.player.engine.PlayErrorKind
import com.ultratv.tv.nativeapp.ui.player.engine.PlaybackSession
import com.ultratv.tv.nativeapp.ui.player.engine.PrefsChannelPlaybackMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackContext,
    private val history: HistoryRepository,
    private val zapQueue: LivePlaybackQueue,
    private val provider: ProviderRepository,
    private val epgDao: EpgDao,
    private val recordings: RecordingRepository,
    private val prefs: UserPreferencesStore,
    private val channelDao: ChannelDao,
    private val adaptive: AdaptiveProfile,
    private val categoryManager: com.ultratv.tv.nativeapp.data.repo.CategoryManager,
    private val catalogRepo: com.ultratv.tv.nativeapp.data.repo.CatalogRepository,
    private val episodeDao: com.ultratv.tv.nativeapp.data.db.EpisodeDao,
    private val trakt: com.ultratv.tv.nativeapp.data.trakt.TraktScrobbler,
    val memory: PrefsChannelPlaybackMemory,
    val network: NetworkMonitor,
) : ViewModel() {

    val prefsFlow: Flow<UserPrefs> = prefs.flow
    suspend fun playbackPrefs(): UserPrefs = prefs.flow.first()

    /** Réglages EFFECTIFS : les choix manuels de l'utilisateur l'emportent sur l'automatique. */
    fun resolved(p: UserPrefs): ResolvedPlayback = PlaybackResolver.resolve(adaptive.state.value.auto, p, adaptive.heapClassMb)
    val adaptiveState get() = adaptive.state

    val current: StateFlow<PlaybackContext.Item?> = playback.current

    /**
     * Programme en cours de la chaîne regardée (guide). Relu à la FIN du programme (borné 15 s – 2 min), pas toutes les
     * 20 s. Sans guide : programme court du fournisseur demandé 2 s après le zap, pas pendant l'ouverture du flux.
     */
    val nowProgramme: StateFlow<EpgEntity?> = playback.current.flatMapLatest { item ->
        if (item == null || item.kind != "LIVE") flowOf(null)
        else flow<EpgEntity?> {
            var first = true
            while (true) {
                val ch = channelDao.byRemoteId(item.providerId, item.remoteId)
                val now = System.currentTimeMillis()
                var cur = ch?.let { epgDao.rangeForChannels(listOf(it.id), now, now + 1).firstOrNull { p -> p.startMs <= now && p.endMs > now } }
                if (cur == null && ch != null) {
                    if (first) { emit(null); delay(2_000) }
                    // Pas de guide pour cette chaîne : programme court du fournisseur (limité, voir ensureShortEpg).
                    if (catalogRepo.ensureShortEpg(listOf(ch.id))) {
                        val t = System.currentTimeMillis()
                        cur = epgDao.rangeForChannels(listOf(ch.id), t, t + 1).firstOrNull { p -> p.startMs <= t && p.endMs > t }
                    }
                }
                first = false
                emit(cur)
                delay(((cur?.endMs ?: 0L) - System.currentTimeMillis() + 1_000).coerceIn(15_000L, 120_000L))
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun recordLive(maxMinutes: Int = 120, toastTemplate: String = "Recording queued (max %1\$d min)") {
        val c = playback.current.value ?: return
        if (c.kind != "LIVE") return
        viewModelScope.launch {
            recordings.enqueue(c.providerId, "LIVE", c.remoteId, c.title, c.streamUrl, maxMinutes)
            Toaster.ok(toastTemplate.format(maxMinutes))
        }
    }

    private fun setLive(target: com.ultratv.tv.nativeapp.data.db.ChannelEntity, url: String) {
        playback.set(PlaybackContext.Item(
            providerId = target.providerId, kind = "LIVE", remoteId = target.remoteId, title = target.title, poster = target.logo, streamUrl = url,
            badge = TitleCleaner.prefixBadge(target.name, TitleCleaner.clean(target.name, live = true).title),
        ))
    }

    /** Chaîne suivante / précédente de la file de zapping (live) ; renvoie la nouvelle URL ou null. */
    suspend fun zap(forward: Boolean): String? {
        val target = (if (forward) zapQueue.next() else zapQueue.previous()) ?: return null
        val resolved = target.streamUrl
        setLive(target, resolved)
        return resolved
    }

    data class DrawerEntry(
        val channel: com.ultratv.tv.nativeapp.data.db.ChannelEntity,
        val now: EpgEntity?, val next: EpgEntity?, val isCurrent: Boolean,
    )

    private suspend fun entriesFor(channels: List<com.ultratv.tv.nativeapp.data.db.ChannelEntity>, currentId: Long?): List<DrawerEntry> {
        val now = System.currentTimeMillis()
        val rows = channels.map { it.id }.chunked(500).flatMap { epgDao.rangeForChannels(it, now - 30 * 60_000, now + 4 * 60 * 60_000) }.groupBy { it.channelId }
        return channels.map { c ->
            val list = rows[c.id].orEmpty()
            DrawerEntry(c, list.firstOrNull { it.startMs <= now && it.endMs > now }, list.firstOrNull { it.startMs > now }, c.id == currentId)
        }
    }

    /** File de zapping courante (la liste que l'utilisateur parcourait avant d'ouvrir le lecteur). */
    // Recalculée quand la LISTE change, pas à chaque zap (seule la position bouge alors) : la chaîne en cours est
    // marquée dans `queue` d'après la lecture. Avant : jusqu'à 400 chaînes + leur guide relus en base à chaque appui.
    private val queueEntries: StateFlow<List<DrawerEntry>> = zapQueue.state.map { it?.channels.orEmpty() }
        .distinctUntilChanged { a, b -> a.size == b.size && a.map { it.id } == b.map { it.id } }
        // Programmes relus chaque minute : la liste reste abonnée pendant la lecture, ils ne doivent pas rester figés.
        .flatMapLatest { list -> flow { while (true) { emit(entriesFor(list, null)); delay(60_000) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Catégorie parcourue dans le tiroir (null = la file de zapping courante). */
    private val browsedCategory = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val browsedEntries = kotlinx.coroutines.flow.MutableStateFlow<List<DrawerEntry>?>(null)

    /** Programmes obtenus par le secours « programme court » (guide complet absent), par chaîne. */
    private val shortProgrammes = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, Pair<EpgEntity?, EpgEntity?>>>(emptyMap())

    val queue: StateFlow<List<DrawerEntry>> = kotlinx.coroutines.flow.combine(queueEntries, browsedEntries, playback.current, shortProgrammes) { q, b, cur, sp ->
        val base = (b ?: q).map { it.copy(isCurrent = it.channel.remoteId == cur?.remoteId) }
        if (sp.isEmpty()) base else base.map { e -> if (e.now == null) sp[e.channel.id]?.let { (n, x) -> e.copy(now = n, next = x) } ?: e else e }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Catégories LIVE actives (colonne de gauche du tiroir). */
    val categories: StateFlow<List<com.ultratv.tv.nativeapp.data.repo.CategoryRow>> = playback.current.flatMapLatest { item ->
        // Seulement les catégories ACTIVES : une catégorie désactivée n'a plus de chaînes (liste vide si on la choisit).
        if (item == null) flowOf(emptyList()) else categoryManager.observe(item.providerId, "LIVE", "").map { rows -> rows.filter { it.enabled } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeCategory: StateFlow<String?> = browsedCategory

    /** Tiroir : complète les lignes sans programme (chaînes visibles ou focalisée) via le programme court du fournisseur. */
    fun fillProgrammes(channels: List<com.ultratv.tv.nativeapp.data.db.ChannelEntity>) {
        if (channels.isEmpty()) return
        viewModelScope.launch {
            if (!catalogRepo.ensureShortEpg(channels.map { it.id })) return@launch
            val got = entriesFor(channels, null).filter { it.now != null || it.next != null }
            if (got.isNotEmpty()) shortProgrammes.value = shortProgrammes.value + got.associate { it.channel.id to (it.now to it.next) }
        }
    }

    /** Charge les chaînes d'une catégorie dans le tiroir (sans zapper). */
    fun browse(categoryId: String) {
        val pid = playback.current.value?.providerId ?: return
        browsedCategory.value = categoryId
        viewModelScope.launch { browsedEntries.value = entriesFor(channelDao.windowCategory(pid, categoryId, 300, 0).filter { !it.junk && !it.isSeparator }, null) }
    }

    fun resetBrowse() { browsedCategory.value = null; browsedEntries.value = null }

    suspend fun zapTo(channel: com.ultratv.tv.nativeapp.data.db.ChannelEntity): String? {
        val browsed = browsedEntries.value
        if (browsed != null) {
            // Chaîne choisie dans une autre catégorie : la file de zapping devient cette catégorie.
            zapQueue.set(browsed.map { it.channel }, channel)
        } else {
            val s = zapQueue.state.value ?: return null
            if (s.channels.none { it.id == channel.id }) return null
            zapQueue.set(s.channels, channel)
        }
        val resolved = channel.streamUrl
        setLive(channel, resolved)
        return resolved
    }

    /** Épisode suivant (saison suivante comprise) devenu l'élément courant ; null en fin de série ou hors épisode. */
    suspend fun nextEpisode(): PlaybackContext.Item? {
        val c = playback.current.value ?: return null
        if (c.kind != "EPISODE") return null
        val cur = episodeDao.byRemoteId(c.providerId, c.remoteId) ?: return null
        val all = episodeDao.observeForSeries(cur.seriesId).first()
        val nx = all.getOrNull(all.indexOfFirst { it.id == cur.id } + 1) ?: return null
        val tag = "S${"%02d".format(java.util.Locale.ROOT, nx.season)}E${"%02d".format(java.util.Locale.ROOT, nx.episode)}"
        val item = c.copy(remoteId = nx.remoteId, title = "${c.title.substringBefore(" · ")} · $tag · ${nx.title}", streamUrl = nx.streamUrl, poster = nx.image ?: c.poster)
        playback.set(item)
        return item
    }

    suspend fun prepareResume(): Long {
        val c = playback.current.value ?: return 0L
        if (c.kind == "LIVE") return 0L
        return history.resumePositionMs(c.providerId, c.kind, c.remoteId)
    }

    /** Scrobble Trakt (film / épisode seulement ; sans effet sur le direct). Jamais bloquant. */
    fun scrobbleTick(playing: Boolean, positionMs: Long, durationMs: Long) = trakt.onTick(playback.current.value, playing, positionMs, durationMs)
    fun scrobblePause(positionMs: Long, durationMs: Long) = trakt.onPause(playback.current.value, positionMs, durationMs)
    fun scrobbleStop(positionMs: Long, durationMs: Long) = trakt.onStop(playback.current.value, positionMs, durationMs)

    fun recordProgress(positionMs: Long, durationMs: Long) {
        val c = playback.current.value ?: return
        if (positionMs < 5_000 && c.kind != "LIVE") return
        // NonCancellable : à la sortie du lecteur le ViewModel est détruit juste après onDispose et son viewModelScope annulé —
        // la dernière position (celle qui alimente « Continuer à regarder ») pouvait être perdue avant d'être écrite.
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            history.record(
                providerId = c.providerId, kind = c.kind, remoteId = c.remoteId, title = c.title, poster = c.poster, streamUrl = c.streamUrl,
                positionMs = if (c.kind == "LIVE") 0 else positionMs, durationMs = if (c.kind == "LIVE") 0 else durationMs, parentRemoteId = c.parentRemoteId,
            )
        }
    }

    fun setEngine(v: String) { viewModelScope.launch { prefs.setPlayerEngine(v) } }
    fun setDecoder(v: String) { viewModelScope.launch { prefs.setDecoderMode(v) } }
}

private enum class Panel { None, Options, Tracks, Subtitles }

/** Onglets du panneau de réglages (maquette LecteurReglages). */
private enum class SideTab { TRACKS, DISPLAY, PLAYER, STATS }

/**
 * Lecteur (maquette Lecteur.dc.html). La surcouche est la même quel que soit le moteur (Media3 / LibVLC) :
 * en-tête (badge EN DIRECT, chaîne, programme, heure), pied (progression, pause 96 px focalisée, pilules).
 * Elle disparaît après 5 s d'inactivité. Aucune URL n'est jamais affichée, ni dans l'interface ni dans les messages d'erreur.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(url: String, title: String, onBack: () -> Unit, onHome: (() -> Unit)? = null, vm: PlayerViewModel = hiltViewModel()) {
    // Le lecteur reste sombre quel que soit le thème de l'application.
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val D = LocalDs.current
    val S = LocalStrings.current
    val item by vm.current.collectAsState()
    val isLive = item?.kind == "LIVE"
    val prefs by vm.prefsFlow.collectAsState(initial = null)
    val p = prefs ?: run { Box(Modifier.fillMaxSize().background(Color.Black)); return }

    var currentUrl by remember { mutableStateOf(url) }
    var panel by remember { mutableStateOf(Panel.None) }
    var drawerOpen by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var aspect by remember { mutableStateOf(AspectMode.FIT) }
    var speed by remember { mutableStateOf(1f) }
    var statsOpen by remember { mutableStateOf(false) }
    // Tactile : orientation, gestes et niveaux (luminosité / volume).
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    val M = com.ultratv.tv.nativeapp.ui.mobile.LocalMobileStrings.current
    val portrait = touch && androidx.compose.ui.platform.LocalConfiguration.current.let { it.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
    val levels = remember { com.ultratv.tv.nativeapp.ui.mobile.PlayerLevels(context) }
    var hud by remember { mutableStateOf<com.ultratv.tv.nativeapp.ui.mobile.GestureHud?>(null) }
    var dragStartLevel by remember { mutableStateOf(0f) }
    var lockedLandscape by remember { mutableStateOf(false) }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    if (touch) {
        com.ultratv.tv.nativeapp.ui.mobile.ImmersiveWhen(fullscreen = !portrait)
        DisposableEffect(Unit) { onDispose { levels.resetBrightness(); context.lockOrientation(null) } }
        LaunchedEffect(portrait) { lockedLandscape = !portrait && lockedLandscape }
    }
    var sleepDeadline by remember { mutableLongStateOf(0L) }
    val latestPrefs by androidx.compose.runtime.rememberUpdatedState(p)

    // [B2·sous-titres] style, langues préférées (DataStore) et recherche en ligne.
    val subVm: com.ultratv.tv.nativeapp.ui.player.subtitles.SubtitleViewModel = hiltViewModel()
    val container = remember { FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) } }
    val session = remember {
        PlaybackSession(
            ctx = context, scope = scope, container = container,
            settings = { vm.resolved(latestPrefs) }, memory = vm.memory, network = vm.network,
            isLive = isLive, autoFrameRate = p.autoFrameRate, userAgent = "UltraTV/1.0 (Android TV)",
            subtitles = { subVm.current },
            uiLanguage = { if (latestPrefs.language == "system") java.util.Locale.getDefault().language else latestPrefs.language },
        )
    }
    val state by session.state.collectAsState()
    // Quitter le lecteur : la vidéo est une couche à part (SurfaceView) qui restait affichée jusqu'à la destruction de
    // l'écran — après la composition de l'écran suivant et la libération du moteur. On la met en pause et on la MASQUE
    // tout de suite, puis on quitte (la libération se fait ensuite, dans onDispose).
    val leave: () -> Unit = {
        session.engine?.pause()
        session.container.visibility = android.view.View.INVISIBLE
        onBack()
    }
    // Liste des chaînes (OK) maintenue À JOUR pendant toute la lecture du direct : abonnée seulement à l'ouverture du
    // tiroir, elle relisait la file, son guide et les compteurs de catégories à chaque OK (tiroir vide un moment).
    // Collecte sans lecture de valeur : aucune recomposition du lecteur.
    // Préparée 1,5 s APRÈS la première image : pas de lecture de la base pendant le démarrage du flux.
    if (isLive) LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { state.phase }.first { it == Phase.PLAYING }
        delay(1_500)
        launch { vm.queue.collect {} }
        launch { vm.categories.collect {} }
    }
    // [B2·zapping] saisie du numéro, chaîne précédente, récentes (ZapViewModel).
    val zap: com.ultratv.tv.nativeapp.ui.player.zap.ZapViewModel = hiltViewModel()
    val X = com.ultratv.tv.nativeapp.ui.player.playerExtras()
    val zapPreview by zap.preview.collectAsState()
    val zapRecent by zap.recent.collectAsState()
    LaunchedEffect(item) { zap.onPlaying(item) }
    LaunchedEffect(zapPreview != null) { if (zapPreview != null) zap.loadRecent() }
    // [B2·timeshift] pause du direct : tampon disque via un relais local (une seule connexion fournisseur).
    val ts: com.ultratv.tv.nativeapp.ui.player.timeshift.TimeshiftViewModel = hiltViewModel()
    var tsActive by remember { mutableStateOf(false) }
    var tsSnap by remember { mutableStateOf<com.ultratv.tv.nativeapp.ui.player.timeshift.TimeshiftSnapshot?>(null) }
    var liveUrlBeforeTs by remember { mutableStateOf<String?>(null) }
    var pendingPause by remember { mutableStateOf(false) }
    fun startTimeshift() {
        when (ts.support(currentUrl)) {
            com.ultratv.tv.nativeapp.ui.player.timeshift.TimeshiftSupport.OK -> {
                val live = currentUrl
                val pidTs = item?.providerId
                // Avec une seule connexion, le flux direct est fermé AVANT d'ouvrir celle du relais ; avec 2 ou plus, on peut enchaîner sans trou.
                if (pidTs == null || ts.maxConnections(pidTs) <= 1) session.release()
                if (pidTs != null) scope.launch { if (ts.connectionConflict(pidTs)) Toaster.show(D.recConnectionBusy) }
                ts.activate(live, "UltraTV/1.0 (Android TV)")?.let { liveUrlBeforeTs = live; tsActive = true; pendingPause = true; currentUrl = it }
                    ?: run { session.start(live, item?.let { "${it.providerId}:${it.remoteId}" }, 0) }
            }
            com.ultratv.tv.nativeapp.ui.player.timeshift.TimeshiftSupport.NO_SPACE -> Toaster.show(X.tsNoSpace)
            else -> Toaster.show(X.tsHls)
        }
    }
    fun tsJump(sec: Int) { ts.jump(sec)?.let { currentUrl = it } }
    fun tsBackToLive() { ts.goLive()?.let { currentUrl = it; pendingPause = false } }
    LaunchedEffect(state.phase) {
        if (!tsActive) return@LaunchedEffect
        if (state.phase == Phase.PLAYING && pendingPause) { session.engine?.pause(); pendingPause = false }
        if (state.phase == Phase.ERROR || state.phase == Phase.ENDED) {
            ts.deactivate(); tsActive = false; tsSnap = null; Toaster.show(X.tsFailed)
            liveUrlBeforeTs?.let { currentUrl = it }
        }
    }
    DisposableEffect(Unit) {
        onDispose { session.engine?.let { vm.recordProgress(it.positionMs, it.durationMs.coerceAtLeast(0)); vm.scrobbleStop(it.positionMs, it.durationMs.coerceAtLeast(0)) }; session.release(); ts.deactivate() }
    }
    // Épisode terminé : marqué vu, puis l'épisode suivant démarre (Réglages › Lecture › Épisode suivant automatique).
    LaunchedEffect(state.phase) {
        if (state.phase != Phase.ENDED || isLive || item?.kind != "EPISODE" || !latestPrefs.autoPlayNextEpisode) return@LaunchedEffect
        session.engine?.let { e -> val d = e.durationMs.coerceAtLeast(0); if (d > 0) { vm.recordProgress(d, d); vm.scrobbleStop(d, d) } }
        vm.nextEpisode()?.let { currentUrl = it.streamUrl }
    }
    // Application quittée (Accueil, autre appli, veille) hors image dans l'image : plus de son en arrière-plan.
    // Au retour, le direct repart au bord du direct ; un film reprend où il en était.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stoppedByApp = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val inPip = (context as? android.app.Activity)?.isInPictureInPictureMode == true
                    if (!inPip) session.engine?.let { e -> vm.recordProgress(e.positionMs, e.durationMs.coerceAtLeast(0)); vm.scrobblePause(e.positionMs, e.durationMs.coerceAtLeast(0)); e.pause(); stoppedByApp = true }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (stoppedByApp) {
                    stoppedByApp = false
                    if (isLive && !tsActive) session.retry() else session.engine?.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        session.notices.collect { n ->
            Toaster.show(when (n) { Notice.USING_VLC -> D.noticeVlc; Notice.USING_EXO -> D.noticeExo; Notice.USING_SOFTWARE -> D.noticeSoftware; Notice.RETRYING -> D.noticeRetry })
        }
    }
    LaunchedEffect(currentUrl) {
        val it = vm.current.value
        val resume = vm.prepareResume()
        subVm.ensureLoaded()
        if (tsActive && !ts.isLocalUrl(currentUrl)) { ts.deactivate(); tsActive = false; tsSnap = null }   // [B2·timeshift] autre chaîne : le relais lâche la connexion d'abord
        session.start(currentUrl, it?.let { x -> "${x.providerId}:${x.remoteId}" }, resume)
    }
    // Progression enregistrée toutes les 10 s (« Reprendre la lecture »).
    // (La progression VOD est enregistrée toutes les 30 s par la boucle de la surcouche ; plus de doublon à 10 s.)
    // Minuterie de sommeil.
    LaunchedEffect(sleepDeadline) {
        if (sleepDeadline <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < sleepDeadline) delay(5_000)
        session.engine?.pause(); onBack()
    }
    // [B2·replay] « Depuis le début » sur le programme en cours ; l'URL (identifiants inclus) n'est jamais affichée.
    val replayVm: com.ultratv.tv.nativeapp.ui.player.replay.ReplayViewModel = hiltViewModel()
    val nowProg by vm.nowProgramme.collectAsState()
    // Replay lancé depuis le guide (« Revoir ») : on reprend le programme pour que le repli d'URL fonctionne aussi.
    val guideReplay = remember { replayVm.takePending() }
    var replayProg by remember { mutableStateOf<EpgEntity?>(guideReplay?.first) }
    var liveUrlBeforeReplay by remember { mutableStateOf<String?>(null) }
    // Reconnexion automatique : vrai direct seulement (pas un replay « Depuis le début » ni le relais du différé).
    // SideEffect : appliqué à chaque composition, avant le démarrage du lancement (LaunchedEffect(currentUrl)).
    androidx.compose.runtime.SideEffect { session.liveReconnect = replayProg == null && !tsActive }
    var canReplay by remember { mutableStateOf(false) }
    LaunchedEffect(nowProg?.id) { canReplay = replayVm.canReplay(nowProg) }
    LaunchedEffect(item?.remoteId) { if (replayProg !== guideReplay?.first) replayProg = null }
    LaunchedEffect(state.phase) {
        val rp = replayProg ?: return@LaunchedEffect
        val pid = item?.providerId ?: guideReplay?.second ?: return@LaunchedEffect
        if (state.phase == Phase.PLAYING) replayVm.worked(pid)
        else if (state.phase == Phase.ERROR) replayVm.retryUrl(rp, pid)?.let { currentUrl = it }
    }
    // [B2·veille] minuterie de la pilule « Veille » : « Toujours là ? » 1 min avant, puis arrêt (flux fermé) et accueil.
    val sleepTimer = remember { com.ultratv.tv.nativeapp.ui.player.sleep.SleepTimer() }
    var sleepPhase by remember { mutableStateOf(com.ultratv.tv.nativeapp.ui.player.sleep.SleepPhase.IDLE) }
    var sleepLeft by remember { mutableStateOf(0) }
    var sleepMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            sleepPhase = sleepTimer.phase(now); sleepLeft = sleepTimer.secondsLeft(now)
            if (sleepPhase == com.ultratv.tv.nativeapp.ui.player.sleep.SleepPhase.EXPIRED) {
                sleepTimer.cancel()
                session.release()      // libère la connexion unique du fournisseur
                (onHome ?: onBack)()
                break
            }
            delay(1_000)
        }
    }
    // Position / durée / horloge (500 ms) ; masquage de la surcouche après 5 s sans action.
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(-1L) }
    var playing by remember { mutableStateOf(true) }
    var clock by remember { mutableStateOf(EpgClock.wall(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        var lastSave = System.currentTimeMillis()
        while (true) {
            // Position écrite seulement en VOD (le direct affiche l'horaire du programme) et arrondie à la seconde :
            // chaque écriture recompose tout le lecteur — avant, 2 fois par seconde y compris en direct.
            session.engine?.let { e -> if (!isLive) { pos = e.positionMs / 1_000 * 1_000; dur = e.durationMs }; playing = e.isPlaying; if (!isLive) vm.scrobbleTick(e.isPlaying, e.positionMs, e.durationMs.coerceAtLeast(0)) }
            // Film / épisode : position enregistrée toutes les 30 s (box éteinte ou appli fermée en force : la reprise tient).
            if (!isLive && playing && System.currentTimeMillis() - lastSave > 30_000) { lastSave = System.currentTimeMillis(); session.engine?.let { vm.recordProgress(it.positionMs, it.durationMs.coerceAtLeast(0)) } }
            clock = EpgClock.wall(System.currentTimeMillis())
            if (tsActive) tsSnap = ts.snapshot()
            if (overlayVisible && panel == Panel.None && !drawerOpen && System.currentTimeMillis() - lastInteraction > 5_000) overlayVisible = false
            delay(500)
        }
    }
    // Pas de veille ni d'économiseur d'écran pendant la lecture (la box s'endormait au milieu d'un épisode).
    val activity = context as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(playing, state.phase) {
        val keep = playing || state.phase == Phase.LOADING
        activity?.window?.let { w -> if (keep) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(aspect) { session.engine?.setAspect(aspect) }
    LaunchedEffect(speed) { session.engine?.setSpeed(speed) }
    LaunchedEffect(state.combo, state.phase) { session.engine?.setAspect(aspect) }

    fun touch() { lastInteraction = System.currentTimeMillis(); overlayVisible = true }
    // Direct : la surcouche sert d'abord de BANDEAU (chaîne, programme) ; Haut/Bas zappent, OK ouvre la liste.
    // Les commandes ne prennent la main (focus) qu'avec Gauche/Droite, Menu ou Info. Sans cette séparation,
    // le 1er zap affichait les commandes et la touche suivante y déplaçait le focus (plus de zapping, OK = pause).
    var controlsEngaged by remember { mutableStateOf(false) }
    LaunchedEffect(overlayVisible) { if (!overlayVisible) controlsEngaged = false }
    BackHandler {
        when {
            zap.isEntering -> zap.cancelEntry()
            // Retour ferme ce qui est ouvert puis QUITTE le lecteur (retour au menu). Il ne ramène plus à la chaîne
            // précédente : touche « chaîne précédente » de la télécommande pour cela (KEYCODE_LAST_CHANNEL).
            panel != Panel.None -> panel = Panel.None
            drawerOpen -> drawerOpen = false
            // Direct : seul le panneau de commandes (gauche/droite/menu) se referme d'abord ; le simple bandeau
            // d'information affiché après un zap ne retient pas Retour.
            overlayVisible && state.phase == Phase.PLAYING && (!isLive || controlsEngaged) -> overlayVisible = false
            else -> leave()
        }
    }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    val pauseFocus = remember { FocusRequester() }
    LaunchedEffect(overlayVisible) { if (overlayVisible && panel == Panel.None) runCatching { pauseFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).androidx_focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // [B2·zapping] chiffres 0-9 / pavé numérique, OK valide la saisie.
                if (isLive && panel == Panel.None && !drawerOpen) {
                    com.ultratv.tv.nativeapp.ui.player.zap.NumberEntry.digitOfKeyCode(ev.nativeKeyEvent.keyCode)?.let { d -> zap.digit(d) { u -> currentUrl = u }; return@onPreviewKeyEvent true }
                    if (zap.isEntering && (ev.key == Key.Enter || ev.key == Key.DirectionCenter || ev.key == Key.NumPadEnter)) { zap.commitNow(); return@onPreviewKeyEvent true }
                }
                val hidden = !overlayVisible && panel == Panel.None && !drawerOpen
                val okKey = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.ButtonSelect || ev.key == Key.ButtonA
                if (isLive && panel == Panel.None && !drawerOpen) {
                    // Touche « chaîne précédente » : rappel de la dernière chaîne regardée (Retour, lui, quitte le lecteur).
                    if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_LAST_CHANNEL) {
                        if (zap.hasPrevious()) { touch(); zap.recallPrevious { currentUrl = it } }
                        return@onPreviewKeyEvent true
                    }
                    // Touche TV (télécommandes Google TV, ex. Mecool G10) : liste des chaînes.
                    if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_TV || ev.key == Key.Guide) { drawerOpen = true; return@onPreviewKeyEvent true }
                    // Touches chaîne +/- : zap, quel que soit l'état de la surcouche.
                    if (ev.key == Key.ChannelUp || ev.key == Key.ChannelDown || ev.key == Key.PageUp || ev.key == Key.PageDown) {
                        val next = ev.key == Key.ChannelDown || ev.key == Key.PageDown
                        touch(); controlsEngaged = false; scope.launch { vm.zap(next)?.let { currentUrl = it } }; return@onPreviewKeyEvent true
                    }
                    if (!controlsEngaged) {
                        when {
                            // OK ouvre la liste des chaînes (sans afficher les commandes derrière).
                            okKey -> { drawerOpen = true; return@onPreviewKeyEvent true }
                            ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> {
                                touch(); scope.launch { vm.zap(ev.key == Key.DirectionDown)?.let { currentUrl = it } }; return@onPreviewKeyEvent true
                            }
                            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.Menu || ev.key == Key.Info -> {
                                if (tsActive && hidden && (ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight)) { tsJump(if (ev.key == Key.DirectionLeft) -30 else 30); touch(); return@onPreviewKeyEvent true }
                                touch(); controlsEngaged = true; runCatching { pauseFocus.requestFocus() }; return@onPreviewKeyEvent true
                            }
                            ev.key == Key.Back || ev.key == Key.Escape -> return@onPreviewKeyEvent false
                            else -> { touch(); return@onPreviewKeyEvent true }
                        }
                    }
                }
                touch()
                if (!hidden) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> if (isLive) { scope.launch { vm.zap(false)?.let { currentUrl = it } }; true } else false
                    Key.DirectionDown -> if (isLive) { scope.launch { vm.zap(true)?.let { currentUrl = it } }; true } else false
                    Key.DirectionLeft -> if (tsActive) { tsJump(-30); true } else if (!isLive) { session.engine?.let { it.seekTo((it.positionMs - 10_000).coerceAtLeast(0)) }; true } else false
                    Key.DirectionRight -> if (tsActive) { tsJump(30); true } else if (!isLive) { session.engine?.let { it.seekTo(it.positionMs + 10_000) }; true } else false
                    else -> true      // OK (VOD) / Info / Menu / autres : on affiche la surcouche des commandes
                }
            },
    ) {
        if (touch) {
            val dpPx = androidx.compose.ui.platform.LocalDensity.current.density
            val progressNow = System.currentTimeMillis()
            val prog = vm.nowProgramme.collectAsState().value
            val liveProgress = isLive && !tsActive
            val fraction: Float; val startLabel: String; val endLabel: String
            if (tsActive && tsSnap != null) { fraction = tsSnap!!.fraction; startLabel = EpgClock.hm(progressNow - tsSnap!!.windowSec * 1000L); endLabel = X.directMark }
            else if (liveProgress) {
                val st = prog?.startMs; val en = prog?.endMs
                fraction = if (st != null && en != null && en > st) ((progressNow - st).toFloat() / (en - st)).coerceIn(0f, 1f) else 0f
                startLabel = st?.let { EpgClock.hm(it) }.orEmpty(); endLabel = en?.let { EpgClock.hm(it) }.orEmpty()
            } else { fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f; startLabel = fmt(pos); endLabel = if (dur > 0) fmt(dur) else "" }
            val togglePlay: () -> Unit = {
                if (isLive && replayProg == null && !tsActive) startTimeshift() else session.engine?.let { if (it.isPlaying) it.pause() else it.play() }
                touch()
            }
            val replayLabel = if (replayProg != null) X.backToLive else if (canReplay) X.fromStart else null
            val pillList = buildList {
                if (isLive && !portrait) add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(M.channels, "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01", { drawerOpen = true }))
                add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(M.tracks, "M4 6h16M4 12h10M4 18h6", { panel = Panel.Tracks }))
                add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(X.subsPill, com.ultratv.tv.nativeapp.ui.mobile.MobileIcons.Subs, { panel = Panel.Subtitles }))
                if (isLive && replayLabel != null) add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(replayLabel, com.ultratv.tv.nativeapp.ui.mobile.MobileIcons.Replay, {
                    val rp = replayProg
                    if (rp != null) { liveUrlBeforeReplay?.let { currentUrl = it }; replayProg = null }
                    else nowProg?.let { np -> scope.launch { replayVm.urlFor(np)?.let { u -> liveUrlBeforeReplay = currentUrl; replayProg = np; currentUrl = u } } }
                    touch()
                }, active = replayProg != null))
                if (isLive) add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(D.pRecord, "M12 6a6 6 0 1 0 0 12 6 6 0 0 0 0-12z", { vm.recordLive(120, S.recordingQueuedTemplate) }))
                if (tsActive) add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(X.backToLive, "M7 4v16l13-8z", { tsBackToLive(); touch() }, active = true))
                add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(X.sleepPill, com.ultratv.tv.nativeapp.ui.mobile.MobileIcons.Moon, { sleepMenu = true }, active = sleepPhase != com.ultratv.tv.nativeapp.ui.player.sleep.SleepPhase.IDLE))
                add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(M.pip, com.ultratv.tv.nativeapp.ui.mobile.MobileIcons.Pip, { if (!context.enterPip()) Toaster.show(M.pipUnavailable) }))
                add(com.ultratv.tv.nativeapp.ui.mobile.ControlPill(D.pPlayer, "M3 5h18v12H3zM8 21h8M12 17v4", { panel = Panel.Options }))
            }
            val headTitle = if (isLive) (prog?.title ?: item?.title ?: title) else (item?.title ?: title)
            val badge = if (isLive) com.ultratv.tv.nativeapp.ui.mobile.liveBadgeText(D.live, null, item?.title ?: title) else null
            val videoArea: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { container.also { v -> (v.parent as? android.view.ViewGroup)?.removeView(v) } }, modifier = Modifier.fillMaxSize())
                    if (!com.ultratv.tv.nativeapp.ui.mobile.PipState.active) {
                        com.ultratv.tv.nativeapp.ui.mobile.GestureLayer(
                            isLive = isLive, modifier = Modifier.fillMaxSize(),
                            onTap = { if (overlayVisible) overlayVisible = false else touch() },
                            onDoubleTap = { zone ->
                                val d = com.ultratv.tv.nativeapp.ui.mobile.doubleTapSeekMs(zone, isLive && !tsActive)
                                if (d != null) { session.engine?.let { it.seekTo(com.ultratv.tv.nativeapp.ui.mobile.seekTarget(it.positionMs, d, it.durationMs)) }; hud = com.ultratv.tv.nativeapp.ui.mobile.GestureHud.Seek(d > 0) }
                                else if (zone == com.ultratv.tv.nativeapp.ui.mobile.TapZone.CENTER) togglePlay()
                            },
                            onDragStart = { role -> dragStartLevel = when (role) { com.ultratv.tv.nativeapp.ui.mobile.DragRole.BRIGHTNESS -> levels.brightness(); com.ultratv.tv.nativeapp.ui.mobile.DragRole.VOLUME -> levels.volume(); else -> 0f } },
                            onDrag = { role, total, h ->
                                if (role == com.ultratv.tv.nativeapp.ui.mobile.DragRole.BRIGHTNESS || role == com.ultratv.tv.nativeapp.ui.mobile.DragRole.VOLUME) {
                                    val lv = com.ultratv.tv.nativeapp.ui.mobile.levelAfterDrag(dragStartLevel, total, h)
                                    if (role == com.ultratv.tv.nativeapp.ui.mobile.DragRole.BRIGHTNESS) levels.setBrightness(lv) else levels.setVolume(lv)
                                    hud = com.ultratv.tv.nativeapp.ui.mobile.GestureHud.Level(role, lv)
                                }
                            },
                            onDragEnd = { role, total ->
                                if (role == com.ultratv.tv.nativeapp.ui.mobile.DragRole.ZAP) com.ultratv.tv.nativeapp.ui.mobile.zapFor(total, 80f * dpPx)?.let { dir ->
                                    hud = com.ultratv.tv.nativeapp.ui.mobile.GestureHud.Zap(dir > 0)
                                    scope.launch { vm.zap(dir > 0)?.let { currentUrl = it } }
                                }
                            },
                            onPinch = { sc -> val next = com.ultratv.tv.nativeapp.ui.mobile.aspectAfterPinch(aspect, sc); if (next != aspect) { aspect = next; hud = com.ultratv.tv.nativeapp.ui.mobile.GestureHud.Aspect(next) } },
                        )
                        if (state.phase == Phase.LOADING) LoadingVisual(item?.poster, item?.title ?: title)
                        if (state.phase == Phase.ERROR) ErrorPanel(
                            kind = state.error ?: PlayErrorKind.UNKNOWN, canNext = isLive, D = D,
                            onRetry = { session.retry() }, onNext = { scope.launch { vm.zap(true)?.let { currentUrl = it } } }, onClose = leave,
                        )
                        if (overlayVisible && state.phase != Phase.ERROR && !drawerOpen && panel == Panel.None) run {
                            MobileControls(
                                isLive = isLive && !tsActive, badge = badge, title = headTitle, subtitle = null, playing = playing,
                                fraction = fraction, startLabel = startLabel, endLabel = endLabel, seekable = !isLive && dur > 0, compact = portrait,
                                onBack = leave, onTogglePlay = togglePlay,
                                onSeekBy = { d -> if (tsActive) { tsJump((d / 1000).toInt()) } else session.engine?.let { it.seekTo(com.ultratv.tv.nativeapp.ui.mobile.seekTarget(it.positionMs, d, it.durationMs)) }; touch() },
                                onSeekTo = { f -> if (dur > 0) session.engine?.seekTo((dur * f).toLong()); touch() },
                                onSettings = { panel = Panel.Options }, pills = pillList, jumpSec = if (tsActive) 30 else 10,
                                onFullscreen = if (portrait) ({ context.lockOrientation(true) }) else if (lockedLandscape) ({ context.lockOrientation(false) }) else null,
                            )
                        }
                        GestureHudView(hud)
                    }
                }
            }
            if (portrait && !com.ultratv.tv.nativeapp.ui.mobile.PipState.active) {
                val queueEntries by vm.queue.collectAsState()
                Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { videoArea() }
                    com.ultratv.tv.nativeapp.ui.mobile.PortraitInfo(
                        live = isLive, liveLabel = D.live, title = headTitle, subtitle = if (isLive) item?.title else null, pills = pillList,
                        entries = queueEntries, channelsLabel = M.channels,
                        onPick = { ch -> scope.launch { vm.zapTo(ch)?.let { currentUrl = it } } }, modifier = Modifier.weight(1f),
                    )
                }
            } else videoArea()
        } else {
        AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())

        // Chargement : visuel de la chaîne avec anneau de progression.
        if (state.phase == Phase.LOADING) LoadingVisual(item?.poster, item?.title ?: title)
        if (state.phase == Phase.ERROR) ErrorPanel(
            kind = state.error ?: PlayErrorKind.UNKNOWN, canNext = isLive, D = D,
            onRetry = { session.retry() },
            onNext = { scope.launch { vm.zap(true)?.let { currentUrl = it } } },
            onClose = leave,
        )

        if (overlayVisible && state.phase != Phase.ERROR && !drawerOpen && panel == Panel.None) {
            Header(item, title, vm, clock, isLive, D, tsSnap?.behindSec, X)
            if (tsActive && tsSnap != null) com.ultratv.tv.nativeapp.ui.player.timeshift.TimeshiftFooter(
                tsSnap!!, playing, X, pauseFocus,
                onToggle = { session.engine?.let { if (it.isPlaying) it.pause() else it.play() }; touch() },
                onJump = { tsJump(it); touch() }, onLive = { tsBackToLive(); touch() },
            ) else Footer(
                isLive = isLive, pos = pos, dur = dur, playing = playing, programme = vm.nowProgramme.collectAsState().value, D = D, pauseFocus = pauseFocus,
                onToggle = { if (isLive && replayProg == null) startTimeshift() else session.engine?.let { if (it.isPlaying) it.pause() else it.play() }; touch() },
                onSeek = { d -> session.engine?.let { it.seekTo((it.positionMs + d).coerceAtLeast(0)) }; touch() },
                onTracks = { panel = Panel.Tracks }, onOptions = { panel = Panel.Options },
                onRecord = { vm.recordLive(120, S.recordingQueuedTemplate) }, onChannels = { drawerOpen = true },
                sleepLabel = X.sleepPill, onSleepPill = { sleepMenu = true }, subLabel = X.subsPill, onSubs = { panel = Panel.Subtitles },
                replayLabel = if (replayProg != null) X.backToLive else if (canReplay) X.fromStart else null,
                onReplay = {
                    val rp = replayProg
                    if (rp != null) { liveUrlBeforeReplay?.let { currentUrl = it }; replayProg = null }
                    else nowProg?.let { np -> scope.launch { replayVm.urlFor(np)?.let { u -> liveUrlBeforeReplay = currentUrl; replayProg = np; currentUrl = u } } }
                    touch()
                },
            )
        }
        }
        zapPreview?.let { pv ->
            com.ultratv.tv.nativeapp.ui.player.zap.ZapNumberBox(pv, X, Modifier.align(Alignment.TopEnd).padding(top = 54.design, end = 96.design))
            com.ultratv.tv.nativeapp.ui.player.zap.ZapRecentStrip(zapRecent, item?.remoteId, X, Modifier.align(Alignment.BottomStart))
        }
        if (sleepMenu) com.ultratv.tv.nativeapp.ui.player.sleep.SleepMenu(
            X, vm.nowProgramme.collectAsState().value?.endMs, sleepTimer.choice != null,
            onPick = { c -> if (c == null) sleepTimer.cancel() else sleepTimer.arm(c, System.currentTimeMillis()); sleepMenu = false; touch() }, onClose = { sleepMenu = false },
        )
        if (sleepPhase == com.ultratv.tv.nativeapp.ui.player.sleep.SleepPhase.WARNING && !sleepMenu) com.ultratv.tv.nativeapp.ui.player.sleep.AreYouThereDialog(
            X, sleepLeft, onStay = { sleepTimer.confirmPresence(System.currentTimeMillis()); sleepPhase = com.ultratv.tv.nativeapp.ui.player.sleep.SleepPhase.IDLE },
            onStop = { sleepTimer.arm(com.ultratv.tv.nativeapp.ui.player.sleep.SleepChoice.Minutes(0), 0L) },
        )
        if (statsOpen) StatsCard(session, D, Modifier.align(Alignment.TopEnd).padding(top = 220.design, end = 96.design))
        if (drawerOpen && isLive) LiveDrawer(vm = vm, onPick = { ch -> scope.launch { vm.zapTo(ch)?.let { currentUrl = it }; drawerOpen = false } }, onDismiss = { drawerOpen = false })
        if (panel == Panel.Subtitles) {
            val eng = session.engine
            var subTracks by remember { mutableStateOf(eng?.subtitleTracks().orEmpty()) }
            androidx.compose.runtime.LaunchedEffect(eng) { while (true) { kotlinx.coroutines.delay(1_000); eng?.subtitleTracks()?.let { subTracks = it } } }
            var needsRestart by remember { mutableStateOf(false) }
            val closeSubs = { panel = Panel.None; if (needsRestart) session.retry() }     // VLC : le style se fixe à la création du moteur
            com.ultratv.tv.nativeapp.ui.player.subtitles.SubtitlePanel(
                X, subVm, subTracks, delaySupported = eng?.kind == EngineKind.VLC, isMovie = item?.kind == "MOVIE", movieTitle = item?.title ?: title,
                onSelectTrack = { id -> eng?.selectSubtitle(id); subVm.setAutoOn(id != null); subTracks = subTracks.map { it.copy(selected = it.id == id) } },
                onStyle = { st -> if (eng?.applySubtitleStyle(st) == false) needsRestart = true; eng?.setSubtitleDelay(st.delayMs) },
                onDownloaded = { path -> if (eng?.addExternalSubtitle(path) == true) Toaster.ok(X.subtitleAdded) },
                onClose = closeSubs,
            )
        }
        if (panel == Panel.Options || panel == Panel.Tracks) PlayerSidePanel(
            initial = if (panel == Panel.Tracks) SideTab.TRACKS else SideTab.PLAYER,
            title = item?.title ?: title, p = p, vm = vm, session = session, state = state, aspect = aspect, speed = speed, isLive = isLive, statsOpen = statsOpen, sleepActive = sleepDeadline > 0, D = D,
            onAspect = { aspect = it }, onSpeed = { speed = it }, onStats = { statsOpen = !statsOpen },
            onSleep = { min -> sleepDeadline = if (min > 0) System.currentTimeMillis() + min * 60_000L else 0L },
            onSwitch = { c -> session.switchTo(c) }, onBuffer = { b -> session.setBufferPreset(b) },
            onExternal = { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(currentUrl), "video/*"); flags = Intent.FLAG_ACTIVITY_NEW_TASK }, S.recordingsOpenWith)) } },
            onClose = { panel = Panel.None }, onSubsChoice = { subVm.setAutoOn(it) })
    }
}

private fun Modifier.androidx_focusable() = this.focusable()

// ───────────────────────── Surcouche ─────────────────────────

@Composable
private fun Header(item: PlaybackContext.Item?, fallbackTitle: String, vm: PlayerViewModel, clock: String, isLive: Boolean, D: DesignStrings, tsBehindSec: Int? = null, X: com.ultratv.tv.nativeapp.ui.player.PlayerExtraStrings? = null) {
    val programme by vm.nowProgramme.collectAsState()
    Row(
        // Hauteur MINIMALE (et non fixe) : badge + titre dépassaient de quelques pixels et le bas des lettres était coupé,
        // davantage avec la taille de police agrandie de certaines box.
        Modifier.fillMaxWidth().heightIn(min = 200.design).background(Color(0xD10A0A0C)).padding(horizontal = 96.design, vertical = 44.design),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.design), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.design)) {
                if (isLive) { if (tsBehindSec != null && X != null) com.ultratv.tv.nativeapp.ui.player.timeshift.TimeshiftBadge(tsBehindSec, X) else LiveBadge(D.live) }
                // Un seul titre : avec un programme du guide la chaîne passe en petit ; sinon son nom EST le titre.
                val name = item?.title ?: fallbackTitle
                if (programme != null || !isLive) Text(name, color = Ux.Text2, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                item?.badge?.let { b -> Text(b, color = Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, modifier = Modifier.clip(RoundedCornerShape(6.design)).background(Ux.Surface2).padding(horizontal = 8.design, vertical = 2.design)) }
            }
            Text(
                if (isLive) (programme?.title ?: item?.title ?: fallbackTitle) else (item?.title ?: fallbackTitle),
                color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 44.spx, lineHeight = 48.spx, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(clock, color = Color(0xFFE4E4E7), fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 36.spx, maxLines = 1)
    }
}

@Composable
private fun Footer(
    isLive: Boolean, pos: Long, dur: Long, playing: Boolean, programme: EpgEntity?, D: DesignStrings, pauseFocus: FocusRequester,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onTracks: () -> Unit, onOptions: () -> Unit, onRecord: () -> Unit, onChannels: () -> Unit,
    sleepLabel: String, onSleepPill: () -> Unit, subLabel: String, onSubs: () -> Unit, replayLabel: String?, onReplay: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val frac: Float; val startLabel: String; val endLabel: String
    if (isLive) {
        val s = programme?.startMs; val e = programme?.endMs
        frac = if (s != null && e != null && e > s) ((now - s).toFloat() / (e - s)).coerceIn(0f, 1f) else 0f
        startLabel = s?.let { EpgClock.hm(it) }.orEmpty(); endLabel = e?.let { EpgClock.hm(it) }.orEmpty()
    } else {
        frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        startLabel = fmt(pos); endLabel = if (dur > 0) fmt(dur) else ""
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xE00A0A0C)).padding(start = 96.design, end = 96.design, top = 40.design, bottom = 54.design),
        verticalArrangement = Arrangement.spacedBy(28.design),
    ) {
        if (startLabel.isNotEmpty() || endLabel.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.design)) {
            Text(startLabel, color = Ux.Text2, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 22.spx, maxLines = 1)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).height(28.design)) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(10.design).clip(RoundedCornerShape(5.design)).background(Ux.Line))
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(10.design).clip(RoundedCornerShape(5.design)).background(Ux.Accent))
                Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * frac - 14.design).size(28.design).clip(CircleShape).background(Color.White))
            }
            Text(endLabel, color = Ux.Text2, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 22.spx, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.design), verticalAlignment = Alignment.CenterVertically) {
                if (!isLive) RoundButton(72, "M11 5L4 12l7 7M20 5l-7 7 7 7", onClick = { onSeek(-10_000) })
                PauseButton(playing, pauseFocus, onToggle)
                if (!isLive) RoundButton(72, "M13 5l7 7-7 7M4 5l7 7-7 7", onClick = { onSeek(10_000) })
            }
            // Une seule ligne (défilante si l'écran est étroit). « Affichage » ouvrait le même panneau que « Lecteur » : fusionnés.
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.weight(1f).padding(start = 32.design),
                horizontalArrangement = Arrangement.spacedBy(14.design, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLive) item { OptionPill(D.pChannels, "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01", onChannels) }
                if (isLive && replayLabel != null) item { OptionPill(replayLabel, "M3 12a9 9 0 1 0 3-6.7M3 4v5h5", onReplay) }
                item { OptionPill(D.pTracks, "M4 6h16M4 12h10M4 18h6", onTracks) }
                item { OptionPill(subLabel, "M3 6h18v12H3zM7 11h3M12 11h5M7 15h6", onSubs) }
                if (isLive) item { OptionPill(D.pRecord, "M12 6a6 6 0 1 0 0 12 6 6 0 0 0 0-12z", onRecord) }
                item { OptionPill(sleepLabel, "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z", onSleepPill) }
                item { OptionPill(D.pPlayer, "M3 5h18v12H3zM8 21h8M12 17v4", onOptions) }
            }
        }
    }
    }
}

private fun fmt(ms: Long): String { val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec) }

@Composable
private fun RoundButton(sizePx: Int, icon: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = CircleShape, bg = Ux.Surface2, modifier = Modifier.size(sizePx.design)) { f ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DIcon(icon, 30.design, if (f) Ux.TextOnLight else Ux.Text) }
    }
}

@Composable
private fun PauseButton(playing: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = CircleShape, bg = Ux.Cta, modifier = Modifier.size(96.design).focusRequester(focus)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (playing) DIcon("M7 4h3.5v16H7zM13.5 4H17v16h-3.5z", 36.design, Ux.TextOnLight, fill = true) else DIcon("M7 4v16l13-8z", 36.design, Ux.TextOnLight, fill = true)
        }
    }
}

@Composable
private fun OptionPill(label: String, icon: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(30.design), bg = Ux.Surface2, ringWidth = 5.design, modifier = Modifier.height(60.design)) { f ->
        Row(Modifier.padding(horizontal = 22.design).height(60.design), verticalAlignment = Alignment.CenterVertically) {
            DIcon(icon, 24.design, if (f) Ux.TextOnLight else Ux.Text)
            Spacer(Modifier.width(10.design))
            Text(label, color = if (f) Ux.TextOnLight else Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 22.spx, maxLines = 1)
        }
    }
}

/**
 * Chargement : l'image du programme (ou le logo) en plein écran, floutée et assombrie, le visuel net au centre,
 * le titre et une fine barre animée — au lieu d'une petite vignette dans un cadre sur fond noir.
 */
@Composable
private fun LoadingVisual(logo: String?, name: String) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Flou plein écran affiché à CHAQUE zap, au moment où le décodeur démarre : réservé aux appareils qui le
        // rendent vraiment (API 31+, hors low-RAM), comme BlurredFill ; ailleurs un simple fond.
        val richBackdrop = !com.ultratv.tv.nativeapp.ui.common.LocalLowRam.current && android.os.Build.VERSION.SDK_INT >= 31
        if (richBackdrop && !logo.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(logo).size(320, 180).build(),
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.45f),
            )
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x660A0A0C), Color(0xE60A0A0C)))))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.design)) {
            LogoBox(logo, name, Modifier.width(360.design).height(240.design), radius = 28, pad = 20, bg = Color(0x33FFFFFF))
            Text(name, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 40.spx, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1200.design))
            androidx.compose.material3.LinearProgressIndicator(
                color = Ux.Accent, trackColor = Color(0x33FFFFFF),
                modifier = Modifier.width(320.design).height(6.design).clip(RoundedCornerShape(3.design)),
            )
        }
    }
}

@Composable
internal fun ErrorPanel(kind: PlayErrorKind, canNext: Boolean, D: DesignStrings, onRetry: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val (title, hint) = when (kind) {
        PlayErrorKind.REFUSED -> D.errRefused to D.errRefusedHint
        PlayErrorKind.NETWORK -> D.errNetwork to null
        PlayErrorKind.FORMAT, PlayErrorKind.DECODER, PlayErrorKind.NO_PICTURE -> D.errFormat to null
        PlayErrorKind.NOT_FOUND -> D.errNotFound to null
        else -> D.errNoResponse to null
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xE60A0A0C)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.design), modifier = Modifier.widthIn(max = 1000.design)) {
            Text(title, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 52.spx, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, color = Ux.Text2, fontFamily = Manrope, fontSize = 26.spx, maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.design)) {
                PillButton(LocalDs.current.retry, onRetry, bg = Ux.Cta, weight = FontWeight.Bold, modifier = Modifier.focusRequester(first))
                if (canNext) PillButton(D.nextChannel, onNext)
                PillButton(D.close, onClose)
            }
        }
    }
}

@Composable
private fun StatsCard(session: PlaybackSession, D: DesignStrings, modifier: Modifier) {
    var s by remember { mutableStateOf(session.engine?.stats()) }
    LaunchedEffect(Unit) { while (true) { s = session.engine?.stats(); delay(1_000) } }
    val st by session.state.collectAsState()
    Column(modifier.width(520.design).clip(RoundedCornerShape(20.design)).background(Color(0xE60F0F12)).padding(28.design), verticalArrangement = Arrangement.spacedBy(8.design)) {
        Text(D.statsLabel.uppercase(), color = Ux.Text3, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, letterSpacing = 2.sp(), maxLines = 1)
        val x = s
        fun row(k: String, v: String?) { }
        listOf(
            D.engine to (if (st.combo.engine == EngineKind.EXO) D.engineExo else D.engineVlc),
            D.decoding to (if (x?.hardwareDecoding == true) D.hardware else D.software),
            "↔" to (x?.resolution ?: "—"), "▶" to (x?.videoCodec ?: "—") + (x?.frameRate?.let { " · %.0f fps".format(it) } ?: ""),
            "♪" to (x?.audioCodec ?: "—") + (x?.audioChannels?.let { " · ${it}ch" } ?: ""),
            D.bufferMemory to (x?.bufferedSeconds?.let { "$it s" } ?: "—"),
            "kbps" to (x?.videoBitrateKbps?.toString() ?: "—"), "⚠" to (x?.droppedFrames?.toString() ?: "—"),
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1)
                Text(v, color = Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 22.spx, maxLines = 1)
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

// ───────────────────────── Panneau de réglages (maquette LecteurReglages) ─────────────────────────

/** Ligne d'option de 64 px : repos #141418 ; choisie = fond #1C1C21 + liseré accent ; focus = blanc + anneau (encre en thème clair, mais le lecteur reste sombre). */
@Composable
private fun OptionRow(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(16.design), bg = if (selected) Ux.Surface else Ux.SurfaceDeep, ringWidth = 4.design, focusedScale = 1f, modifier = Modifier.fillMaxWidth().height(64.design)) { f ->
        Box(Modifier.fillMaxSize().then(if (selected && !f) Modifier.border(2.design, Ux.Accent, RoundedCornerShape(16.design)) else Modifier)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.design), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = if (f) Ux.TextOnLight else if (selected) Ux.Text else Ux.Text2, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 22.spx, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!hint.isNullOrEmpty()) Text(hint, color = if (f) Ux.OnFocus2 else Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OptionGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        Text(title.uppercase(), color = Ux.Text3, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 22.spx, letterSpacing = 2.sp(), maxLines = 1)
        Column(verticalArrangement = Arrangement.spacedBy(8.design)) { content() }
    }
}

@Composable
private fun PlayerSidePanel(
    initial: SideTab, title: String, p: UserPrefs, vm: PlayerViewModel, session: PlaybackSession, state: com.ultratv.tv.nativeapp.ui.player.engine.SessionState,
    aspect: AspectMode, speed: Float, isLive: Boolean, statsOpen: Boolean, sleepActive: Boolean, D: DesignStrings,
    onAspect: (AspectMode) -> Unit, onSpeed: (Float) -> Unit, onStats: () -> Unit, onSleep: (Int) -> Unit, onSwitch: (Combo) -> Unit, onBuffer: (BufferPreset) -> Unit,
    onExternal: () -> Unit, onClose: () -> Unit, onSubsChoice: (Boolean) -> Unit = {},
) {
    var tab by remember { mutableStateOf(initial) }
    val e = session.engine
    // Les pistes se découvrent à l'analyse du flux (parfois après l'ouverture du panneau) : liste relue tant qu'il est ouvert.
    var audio by remember { mutableStateOf(e?.audioTracks().orEmpty()) }
    var subs by remember { mutableStateOf(e?.subtitleTracks().orEmpty()) }
    androidx.compose.runtime.LaunchedEffect(e) { while (true) { audio = e?.audioTracks().orEmpty(); subs = e?.subtitleTracks().orEmpty(); kotlinx.coroutines.delay(1_000) } }
    val labels = listOf(SideTab.TRACKS to D.pTracks, SideTab.DISPLAY to D.pDisplay, SideTab.PLAYER to D.pPlayer, SideTab.STATS to D.statsShort)
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    ModalFocusScope(onBack = onClose, modifier = Modifier.background(androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.CenterEnd) {
        // Tactile : toucher à côté du panneau le ferme.
        if (touch) Box(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose))
        Column(
            Modifier.fillMaxHeight().then(if (touch) Modifier.width(360.dp) else Modifier.width(640.design)).background(Ux.Rail).border(1.design, Ux.Surface2).padding(horizontal = 56.design, vertical = 54.design),
            verticalArrangement = Arrangement.spacedBy(22.design),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.design), modifier = if (touch) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier) {
                labels.forEach { (t, l) ->
                    val sel = t == tab
                    FocusSurface(onClick = { tab = t }, shape = RoundedCornerShape(26.design), bg = if (sel) Ux.Cta else Ux.Surface, ringWidth = 4.design, focusedScale = 1f, modifier = Modifier.height(52.design)) { f ->
                        Box(Modifier.height(52.design).padding(horizontal = 18.design), contentAlignment = Alignment.Center) {
                            Text(l, color = if (f || sel) Ux.TextOnLight else Ux.Text2, fontFamily = Manrope, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 22.spx, maxLines = 1)
                        }
                    }
                }
            }
            Text(D.forThisChannel(title), color = Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.design)) {
                when (tab) {
                    SideTab.TRACKS -> {
                        OptionGroup(D.audio) {
                            if (audio.isEmpty()) OptionRow("—", null, false) {}
                            audio.forEach { t -> OptionRow(t.label, null, t.selected) { session.selectAudio(t.id); onClose() } }
                        }
                        OptionGroup(D.subtitles) {
                            OptionRow(D.off, null, subs.none { it.selected }) { e?.selectSubtitle(null); onSubsChoice(false); onClose() }
                            subs.forEach { t -> OptionRow(t.label, null, t.selected) { e?.selectSubtitle(t.id); onSubsChoice(true); onClose() } }
                        }
                    }
                    SideTab.DISPLAY -> {
                        OptionGroup(D.pDisplay) {
                            listOf(AspectMode.FIT to D.aspectFit, AspectMode.FILL to D.aspectFill, AspectMode.ZOOM to D.aspectZoom, AspectMode.R16_9 to "16:9", AspectMode.R4_3 to "4:3")
                                .forEach { (m, l) -> OptionRow(l, null, aspect == m) { onAspect(m) } }
                        }
                        if (!isLive) OptionGroup(D.speed) { listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { v -> OptionRow("${v}x", null, speed == v) { onSpeed(v) } } }
                        OptionGroup(D.sleepTimer) {
                            listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (m, l) -> OptionRow(l, null, false) { onSleep(m) } }
                            OptionRow(D.off, null, !sleepActive) { onSleep(0) }
                        }
                    }
                    SideTab.PLAYER -> {
                        val engineName = if (state.combo.engine == EngineKind.EXO) D.engineExo else D.engineVlc
                        OptionGroup(D.engine) {
                            OptionRow(D.auto, if (p.playerEngine == "auto") engineName else null, p.playerEngine == "auto") { vm.setEngine("auto") }
                            OptionRow(D.engineExo, null, p.playerEngine == "exo") { vm.setEngine("exo"); onSwitch(Combo(EngineKind.EXO, state.combo.decoder)) }
                            OptionRow(D.engineVlc, D.vlcHint, p.playerEngine == "vlc") { vm.setEngine("vlc"); onSwitch(Combo(EngineKind.VLC, state.combo.decoder)) }
                        }
                        OptionGroup(D.decoding) {
                            OptionRow(D.auto, if (p.decoderMode == "auto") (if (state.combo.decoder == DecoderMode.SOFTWARE) D.software else D.hardware) else null, p.decoderMode == "auto") { vm.setDecoder("auto"); onSwitch(Combo(state.combo.engine, DecoderMode.AUTO)) }
                            OptionRow(D.hardware, null, p.decoderMode == "hw") { vm.setDecoder("hw"); onSwitch(Combo(state.combo.engine, DecoderMode.HARDWARE)) }
                            OptionRow(D.software, D.softwareHint, p.decoderMode == "sw") { vm.setDecoder("sw"); onSwitch(Combo(state.combo.engine, DecoderMode.SOFTWARE)) }
                        }
                        OptionGroup(D.bufferMemory) {
                            listOf(BufferPreset.AUTO to D.auto, BufferPreset.LOW_LATENCY to D.bufLow, BufferPreset.BALANCED to D.bufBalanced, BufferPreset.STABLE to D.bufStable)
                                .forEach { (b, l) -> OptionRow(l, null, state.bufferPreset == b) { onBuffer(b) } }
                        }
                        OptionRow(LocalStrings.current.playerExternal, null, false, onExternal)
                    }
                    SideTab.STATS -> {
                        OptionRow(D.statsLabel + " · " + D.onVideo, null, statsOpen, onStats)
                        StatsRows(session, D)
                    }
                }
            }
            val backToAuto: () -> Unit = {
                vm.setEngine("auto"); vm.setDecoder("auto"); onBuffer(BufferPreset.AUTO)
                onSwitch(Combo(state.combo.engine, DecoderMode.AUTO))
            }
            if (tab == SideTab.PLAYER) PillButton(D.backToAuto, onClick = backToAuto, heightPx = 64, hPadPx = 36, fontPx = 22, weight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            else PillButton(D.close, onClose, heightPx = 64, hPadPx = 36, fontPx = 22, weight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatsRows(session: PlaybackSession, D: DesignStrings) {
    var s by remember { mutableStateOf(session.engine?.stats()) }
    LaunchedEffect(Unit) { while (true) { s = session.engine?.stats(); delay(1_000) } }
    val st by session.state.collectAsState()
    val x = s
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        listOf(
            D.engine to (if (st.combo.engine == EngineKind.EXO) D.engineExo else D.engineVlc),
            D.decoding to (if (x?.hardwareDecoding == true) D.hardware else D.software),
            D.statResolution to (x?.resolution ?: "—") + (x?.frameRate?.let { " · %.0f".format(it) } ?: ""),
            D.statCodec to (x?.videoCodec ?: "—"),
            D.statAudio to (x?.audioCodec ?: "—") + (x?.audioChannels?.let { " · ${it}ch" } ?: ""),
            D.bufferMemory to (x?.bufferedSeconds?.let { "$it s" } ?: "—"),
            D.statBitrate to (x?.videoBitrateKbps?.let { "%.1f Mb/s".format(it / 1000.0) } ?: "—"),
            D.statDropped to (x?.droppedFrames?.toString() ?: "—"),
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1)
                Text(v, color = Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, maxLines = 1)
            }
        }
    }
}
