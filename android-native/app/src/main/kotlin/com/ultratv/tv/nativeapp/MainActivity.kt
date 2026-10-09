package com.ultratv.tv.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.ultratv.tv.nativeapp.ui.design.spx
import com.ultratv.tv.nativeapp.ui.common.design
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.ultratv.tv.nativeapp.data.prefs.SidebarPosition
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.data.sync.SyncScheduler
import com.ultratv.tv.nativeapp.nav.Routes
import com.ultratv.tv.nativeapp.ui.AppViewModel
import com.ultratv.tv.nativeapp.ui.categories.CategoriesScreen
import com.ultratv.tv.nativeapp.ui.common.ScreenFocusHost
import com.ultratv.tv.nativeapp.ui.components.SidebarNav
import com.ultratv.tv.nativeapp.ui.favorites.FavoritesScreen
import com.ultratv.tv.nativeapp.ui.guide.GuideGridScreen
import com.ultratv.tv.nativeapp.ui.home.HomeScreen
import com.ultratv.tv.nativeapp.ui.live.LiveScreen
import com.ultratv.tv.nativeapp.ui.movies.MovieDetailScreen
import com.ultratv.tv.nativeapp.ui.player.PlayerScreen
import com.ultratv.tv.nativeapp.ui.search.SearchScreen
import com.ultratv.tv.nativeapp.ui.series.SeriesDetailScreen
import com.ultratv.tv.nativeapp.ui.settings.SettingsScreen
import com.ultratv.tv.nativeapp.ui.theme.UltraTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Carries a one-shot "open this URL+title in the player as soon as the
 * Composition is up" intent. Set by [MainActivity.kickoffStartupTasks] when
 * `autoPlayLastOnLaunch` is enabled; consumed by [UltraTvAppRoot] once.
 */
object StartupNav {
    data class Pending(val url: String, val title: String)
    val pending = MutableStateFlow<Pending?>(null)
    /** Route à ouvrir une fois l'application affichée (ex. « Regarder le direct » depuis le chargement). */
    val pendingRoute = MutableStateFlow<String?>(null)
    /** Débogage uniquement : rubrique de Réglages à ouvrir. */
    val debugRub = MutableStateFlow<Int?>(null)
    /** Demande d'appairage cloud depuis un autre écran (accueil vide) : Réglages › Sources la consomme. */
    val cloudPairRequest = MutableStateFlow(false)
    /** Demande d'ouverture de la Recherche (touche Recherche / micro de la télécommande), depuis n'importe quel écran. */
    val searchRequest = MutableStateFlow(0)
    /** Build debug : requête préremplie (Recherche) et thème forcé (captures). */
    val debugQuery = MutableStateFlow<String?>(null)
    val debugTheme = MutableStateFlow<String?>(null)
    /** Accueil vide (tactile) : ouvrir Réglages et lancer l'appairage cloud. */
    val startPairing = MutableStateFlow(false)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object { const val REQ_CHANNEL_BROWSABLE = 4107 }

    @Inject lateinit var prefsStore: UserPreferencesStore
    @Inject lateinit var providerRepo: ProviderRepository
    @Inject lateinit var historyRepo: HistoryRepository
    @Inject lateinit var playback: PlaybackContext
    @Inject lateinit var syncCoordinator: com.ultratv.tv.nativeapp.data.sync.SyncCoordinator
    @Inject lateinit var deepLinks: com.ultratv.tv.nativeapp.nav.DeepLinkHandler
    @Inject lateinit var recordingScheduler: com.ultratv.tv.nativeapp.data.recording.RecordingScheduler
    @Inject lateinit var cloudSync: com.ultratv.tv.nativeapp.data.config.CloudSyncManager
    @Inject lateinit var remindersScheduler: com.ultratv.tv.nativeapp.data.reminders.RemindersScheduler
    @Inject lateinit var googleTv: dagger.Lazy<com.ultratv.tv.nativeapp.data.tv.GoogleTvSync>

    override fun onStop() {
        super.onStop()
        com.ultratv.tv.nativeapp.ui.common.AppForeground.visible = false
        com.ultratv.tv.nativeapp.perf.PerfTelemetry.setTracking(false)
    }

    override fun onStart() {
        super.onStart()
        com.ultratv.tv.nativeapp.ui.common.AppForeground.visible = true
        com.ultratv.tv.nativeapp.perf.PerfTelemetry.setTracking(true)
        // Retour sur l'appli (sortie de veille comprise) : guide rechargé s'il est périmé — l'appli reste en mémoire
        // des jours sur une box et le guide ne couvre que les 24 h suivant sa synchro.
        if (::providerRepo.isInitialized) providerRepo.refreshEpgIfStale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API : affiche le thème de lancement tout de suite et évite
        // l'écran noir pendant l'init Hilt/Room.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!com.ultratv.tv.nativeapp.ui.common.isTelevision(this)) {
            // Téléphone / tablette : barres système transparentes (le contenu passe dessous, les marges viennent des insets).
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
        RemoteLog.info("activity", "onCreate restoredState=${savedInstanceState != null}")
        handleDebugIntent(intent)
        handleDeepLink(intent)
        // Ré-arme rappels et enregistrements programmés (alarmes perdues après une mise à jour ou un arrêt forcé).
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { remindersScheduler.rescheduleAll() }
            runCatching { recordingScheduler.rearmAll() }
        }
        setContent { Root() }
        com.ultratv.tv.nativeapp.perf.PerfTelemetry.attach(this)
        // Android 12+ : le geste « accueil » entre en image dans l'image sans passer par onUserLeaveHint, tant que le lecteur est affiché.
        if (android.os.Build.VERSION.SDK_INT >= 31 && !com.ultratv.tv.nativeapp.ui.common.isTelevision(this)) {
            lifecycleScope.launch {
                androidx.compose.runtime.snapshotFlow { com.ultratv.tv.nativeapp.ui.design.Ux.playerActive }.collect { shown ->
                    runCatching {
                        setPictureInPictureParams(
                            android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).setAutoEnterEnabled(shown).build(),
                        )
                    }
                }
            }
        }
        kickoffStartupTasks()
        // Google TV : invite système « Afficher la chaîne Nouveautés sur l'accueil ? », une seule fois, quand la chaîne
        // existe sans être affichée. Après l'accueil (30 s), jamais pendant une lecture ni appli en arrière-plan.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(45_000)
            if (!com.ultratv.tv.nativeapp.ui.common.AppForeground.visible || com.ultratv.tv.nativeapp.ui.design.Ux.playerActive) return@launch
            runCatching {
                val intent = kotlinx.coroutines.withContext(Dispatchers.IO) { googleTv.get().browsableRequest() } ?: return@runCatching
                @Suppress("DEPRECATION") startActivityForResult(intent, REQ_CHANNEL_BROWSABLE)
            }
        }
        // Auto-update flow: query GitHub Releases on launch and, if a newer
        // version is found, download + fire the system install Intent without
        // asking the user first. They still get the OS's "Install this app?"
        // prompt — that one can't be skipped without device-owner privileges.
        lifecycleScope.launch {
            // Pas pendant le démarrage : on laisse l'interface devenir interactive d'abord
            // (et l'invite système d'installation ne vole pas le focus au lancement).
            kotlinx.coroutines.delay(30_000)
            val info = com.ultratv.tv.nativeapp.update.UpdateChecker.checkForUpdate()
                ?: return@launch
            com.ultratv.tv.nativeapp.RemoteLog.info(
                "update",
                "auto-installing ${info.tag}",
            )
            runCatching {
                com.ultratv.tv.nativeapp.update.UpdateChecker
                    .downloadAndInstall(this@MainActivity, info)
            }.onFailure {
                com.ultratv.tv.nativeapp.RemoteLog.warn(
                    "update",
                    "auto-install failed: ${it.javaClass.simpleName} ${it.message}",
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CHANNEL_BROWSABLE) runCatching { googleTv.get().onBrowsableResult(resultCode == RESULT_OK) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        handleDeepLink(intent)
    }

    /** Lien profond `ultratv://…` (notification de rappel, Google TV) : résolu dans le catalogue local. */
    private fun handleDeepLink(intent: android.content.Intent?) {
        // Recherche vocale : ACTION_SEARCH + requête ; sinon lien profond ultratv://… (VIEW).
        val voiceQuery = if (intent?.action == android.content.Intent.ACTION_SEARCH) intent.getStringExtra(android.app.SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() } else null
        val link = voiceQuery?.let { com.ultratv.tv.nativeapp.nav.DeepLink.Search(it) }
            ?: com.ultratv.tv.nativeapp.nav.DeepLink.parse(intent?.data?.toString()) ?: return
        lifecycleScope.launch { runCatching { deepLinks.handle(link) } }
    }

    /** Build debug seulement : `am start --es debug_route settings --ei debug_rub 1` ouvre un écran précis (captures, tests). */
    private fun handleDebugIntent(intent: android.content.Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("debug_route")?.let { StartupNav.pendingRoute.value = it }
        intent.getStringExtra("debug_query")?.let { StartupNav.debugQuery.value = it }
        intent.getStringExtra("debug_theme")?.let { StartupNav.debugTheme.value = it }
        com.ultratv.tv.nativeapp.ui.common.DebugConnectivity.forceOffline = intent.getBooleanExtra("debug_offline", false)
        if (intent.hasExtra("debug_rub")) StartupNav.debugRub.value = intent.getIntExtra("debug_rub", 0)
        // Mesures (debug) : moteur (auto|exo|vlc), décodage (auto|hw|sw) et préréglage de tampon.
        intent.getStringExtra("debug_lang")?.let { v -> lifecycleScope.launch { prefsStore.setLanguage(v) } }
        intent.getStringExtra("debug_engine")?.let { v -> lifecycleScope.launch { prefsStore.setPlayerEngine(v) } }
        intent.getStringExtra("debug_decoder")?.let { v -> lifecycleScope.launch { prefsStore.setDecoderMode(v) } }
        intent.getStringExtra("debug_buffer")?.let { v -> lifecycleScope.launch { prefsStore.setBufferPreset(v) } }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.ultratv.tv.nativeapp.ui.mobile.PipState.active = isInPictureInPictureMode
    }

    /**
     * When the user presses Home while a stream is playing, enter PiP so the
     * stream keeps going in a corner. Falls back silently on devices that
     * don't support it (some TV firmwares).
     */
    /** Touches Recherche et micro de la télécommande : ouvrent la Recherche par-dessus l'écran courant (Retour y revient). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            com.ultratv.tv.nativeapp.ui.common.InputClock.lastKeyCode = event.keyCode
            com.ultratv.tv.nativeapp.ui.common.InputClock.lastKeyMs = android.os.SystemClock.uptimeMillis()
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH || event.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) StartupNav.searchRequest.value += 1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSearchRequested(): Boolean { StartupNav.searchRequest.value += 1; return true }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (playback.current.value == null) return
        // TV : jamais d'image dans l'image. Beaucoup de box l'« acceptent » sans rien afficher : le son continuait
        // après avoir quitté l'application. Le lecteur se met en pause à l'arrêt de l'activité (PlayerScreen).
        if (com.ultratv.tv.nativeapp.ui.common.isTelevision(this)) return
        // Téléphone / tablette : image dans l'image seulement depuis l'écran du lecteur (jamais depuis l'accueil).
        if (!com.ultratv.tv.nativeapp.ui.mobile.shouldEnterPip(hasPlayback = true, playerShown = com.ultratv.tv.nativeapp.ui.design.Ux.playerActive)) return
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /**
     * Best-effort startup tasks. Both are gated by user prefs.
     *
     *  1. Auto-sync providers if `autoSyncOnLaunch` is on AND the configured
     *     [UserPrefs.syncIntervalHours] interval has elapsed since the last
     *     successful sync. Interval 0 means "every launch".
     *  2. Auto-play the most recently watched item by emitting a Pending
     *     entry on [StartupNav.pending], which the NavGraph picks up.
     */
    private fun kickoffStartupTasks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = prefsStore.flow.first()
            // « Reprendre au lancement » : tout de suite (avant le délai et la synchro cloud qui suivent).
            if (prefs.autoPlayLastOnLaunch) {
                val firstProvider = providerRepo.observeProviders().first().firstOrNull()
                if (firstProvider != null) {
                    val last = historyRepo.recent(firstProvider.id, 1).first().firstOrNull()
                    if (last != null) {
                        playback.set(PlaybackContext.Item(
                            providerId = last.providerId, kind = last.kind, remoteId = last.remoteId,
                            title = last.title, poster = last.poster, streamUrl = last.streamUrl,
                            parentRemoteId = last.parentRemoteId,
                        ))
                        StartupNav.pending.value = StartupNav.Pending(last.streamUrl, last.title)
                    }
                }
            }

            // Appli déjà remplie : synchro cloud, catalogue, rappels… attendent que l'accueil soit affiché (3 s) au lieu
            // de concurrencer son premier rendu sur un CPU modeste. Première installation : rien n'est retardé.
            if (providerRepo.observeProviders().first().any { it.lastLiveSyncAt > 0 }) kotlinx.coroutines.delay(3_000)

            // (Re-)apply the background sync schedule from the stored prefs
            // every time the app starts so a re-install / OS restart picks up
            // where we left off.
            // Une seule décision par mode (avant : posé puis annulé à chaque lancement en mode manuel).
            SyncScheduler.schedule(this@MainActivity, if (prefs.syncMode == "auto" || prefs.syncMode == "scheduled") prefs.syncIntervalHours else 0)
            // Sources du compte cloud : à l'ouverture, puis toutes les 6 h (travail périodique).
            com.ultratv.tv.nativeapp.data.config.CloudSyncWorker.schedule(this@MainActivity)
            cloudSync.watchDisplayPrefs()
            cloudSync.watchSharedState()
            runCatching { cloudSync.sync() }

            // Synchro incrémentale : le TTL (par partie du catalogue) décide de ce qui est rechargé ;
            // une source jamais synchronisée ou vide l'est TOUJOURS, même si la synchro auto est coupée.
            val all = providerRepo.observeProviders().first()
            // Mode : auto (TTL selon l'appareil/le réseau) · à chaque lancement · planifiée (travail périodique) · manuelle.
            all.forEach { p -> if (p.lastLiveSyncAt == 0L || (prefs.syncMode != "manual" && prefs.syncMode != "scheduled" && prefs.autoSyncOnLaunch)) syncCoordinator.request(p.id) }
            if (prefs.syncMode == "scheduled") SyncScheduler.scheduleDaily(this@MainActivity, prefs.syncHour, prefs.syncUnmeteredOnly)

        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun Root(vm: AppViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsState()
    val ctxForDevice = androidx.compose.ui.platform.LocalContext.current
    val lang = com.ultratv.tv.nativeapp.i18n.AppLang.fromCode(prefs.language)
    val strings = com.ultratv.tv.nativeapp.i18n.stringsFor(lang)
    // « Système » résout la langue de l'appareil : un téléphone en arabe doit aussi passer en RTL.
    val resolvedLang = if (lang == com.ultratv.tv.nativeapp.i18n.AppLang.System &&
        androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0)?.language == "ar")
        com.ultratv.tv.nativeapp.i18n.AppLang.Arabic else lang
    val direction = if (resolvedLang == com.ultratv.tv.nativeapp.i18n.AppLang.Arabic)
        androidx.compose.ui.unit.LayoutDirection.Rtl
    else
        androidx.compose.ui.unit.LayoutDirection.Ltr
    val adaptiveVm: com.ultratv.tv.nativeapp.adaptive.AdaptiveViewModel = hiltViewModel()
    val adaptive by adaptiveVm.state.collectAsState()
    val lowRam = adaptive.auto.lowRam
    androidx.compose.runtime.CompositionLocalProvider(
        com.ultratv.tv.nativeapp.ui.common.LocalLowRam provides lowRam,
        com.ultratv.tv.nativeapp.adaptive.LocalAdaptive provides adaptive,
        com.ultratv.tv.nativeapp.i18n.LocalStrings provides strings,
        com.ultratv.tv.nativeapp.i18n.LocalDs provides com.ultratv.tv.nativeapp.i18n.designStringsFor(lang),
        com.ultratv.tv.nativeapp.ui.mobile.LocalMobileStrings provides com.ultratv.tv.nativeapp.ui.mobile.mobileStringsFor(lang),
        androidx.compose.ui.platform.LocalLayoutDirection provides direction,
    ) {
        val dbgTheme by StartupNav.debugTheme.collectAsState()
        com.ultratv.tv.nativeapp.ui.theme.ApplyUxTheme(dbgTheme?.let { com.ultratv.tv.nativeapp.data.prefs.AppTheme.parse(it) } ?: prefs.theme)
        com.ultratv.tv.nativeapp.ui.common.ProvideUiScale {
        UltraTvTheme {
            // L'assistant de premier lancement REMPLACE l'application au lieu de
            // se superposer : avant, l'accueil restait composé derrière (premier
            // focalisable = barre latérale), donc la télécommande pilotait un
            // écran invisible et « Suivant » ne recevait jamais le focus.
            val onboarding: com.ultratv.tv.nativeapp.ui.onboarding.OnboardingViewModel = hiltViewModel()
            val showOnboarding by onboarding.show.collectAsState()
            val profileGate: com.ultratv.tv.nativeapp.ui.profile.ProfileGateViewModel = hiltViewModel()
            val askProfile by profileGate.needsSelection.collectAsState()
            when {
                askProfile == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                askProfile == true && showOnboarding == false -> com.ultratv.tv.nativeapp.ui.profile.WhoIsWatchingScreen()
                else -> when (showOnboarding) {
                null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                true -> {
                    // L'assistant est hors de la racine qui fournit l'OverlayHost : sans le sien,
                    // ses modales (appairage cloud, formulaires) étaient composées DANS le volet
                    // de l'étape, sous son en-tête — logo en double, boutons écrasés.
                    val onboardingOverlays = androidx.compose.runtime.remember { com.ultratv.tv.nativeapp.ui.common.OverlayHost() }
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.runtime.CompositionLocalProvider(com.ultratv.tv.nativeapp.ui.common.LocalOverlayHost provides onboardingOverlays) {
                            com.ultratv.tv.nativeapp.ui.onboarding.OnboardingWizard(
                                onOpenSettings = { /* user can re-enter Settings via sidebar */ },
                                vm = onboarding,
                            )
                        }
                        com.ultratv.tv.nativeapp.ui.common.OverlayLayer(onboardingOverlays)
                    }
                }
                false -> {
                    val first: com.ultratv.tv.nativeapp.ui.sync.FirstSyncViewModel = hiltViewModel()
                    val firstState by first.state.collectAsState()
                    val ui = firstState?.ui
                    when {
                        // Jamais d'écran noir muet : sur une box lente en pleine écriture du catalogue,
                        // la première réponse de la base peut prendre plusieurs secondes.
                        firstState == null -> StartupLoading()
                        ui != null -> com.ultratv.tv.nativeapp.ui.sync.FirstSyncScreen(
                            ui,
                            onWatchLive = { StartupNav.pendingRoute.value = Routes.LIVE; first.leaveToLive() },
                            onRetry = { first.retry(ui.providerId) },
                            onFixSource = { StartupNav.pendingRoute.value = Routes.SETTINGS; first.dismiss() },
                        )
                        else -> Box(Modifier.fillMaxSize()) { UltraTvAppRoot(prefs.sidebarPosition) }
                    }
                }
                }
            }
        }
        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun UltraTvAppRoot(sidebarPosition: SidebarPosition) {
    val nav = rememberNavController()
    // Le lecteur reste sombre : état DÉRIVÉ des destinations visibles (écrit avant NavHost, donc
    // avant toute lecture des jetons par le lecteur ; aucun compteur à tenir à jour).
    val visibleEntries by nav.visibleEntries.collectAsState()
    val playerShown = com.ultratv.tv.nativeapp.ui.theme.isPlayerShown(visibleEntries.map { it.destination.route })
    if (com.ultratv.tv.nativeapp.ui.design.Ux.playerActive != playerShown) com.ultratv.tv.nativeapp.ui.design.Ux.playerActive = playerShown
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    val layout = com.ultratv.tv.nativeapp.ui.mobile.navLayoutFor(
        tv = !touch,
        widthDp = com.ultratv.tv.nativeapp.ui.common.LocalUiWidthDp.current,
        heightDp = com.ultratv.tv.nativeapp.ui.mobile.LocalUiHeightDp.current,
    )
    // Barres système : icônes sombres sur fond clair (thème clair), toujours claires dans le lecteur.
    if (touch) {
        val view = androidx.compose.ui.platform.LocalView.current
        val lightBars = com.ultratv.tv.nativeapp.ui.theme.isLightEffective(com.ultratv.tv.nativeapp.ui.design.Ux.themeLight, playerShown)
        androidx.compose.runtime.SideEffect {
            val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = lightBars
            c.isAppearanceLightNavigationBars = lightBars
        }
    }

    // One-shot: as soon as we have a NavController, consume any pending
    // auto-play request set during startup.
    val pending by StartupNav.pending.collectAsState()
    val pendingRoute by StartupNav.pendingRoute.collectAsState()
    LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        nav.navigate(r)
        StartupNav.pendingRoute.value = null
    }
    val searchReq by StartupNav.searchRequest.collectAsState()
    LaunchedEffect(searchReq) {
        if (searchReq > 0 && nav.currentBackStackEntry?.destination?.route != Routes.SEARCH) nav.navigate(Routes.SEARCH) { launchSingleTop = true }
    }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        nav.navigate(Routes.player(p.url, p.title))
        StartupNav.pending.value = null
    }

    val overlays = androidx.compose.runtime.remember { com.ultratv.tv.nativeapp.ui.common.OverlayHost() }
    val openSearch: () -> Unit = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
    val navBack: () -> Unit = { nav.popBackStack() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
      androidx.compose.runtime.CompositionLocalProvider(com.ultratv.tv.nativeapp.ui.common.LocalOverlayHost provides overlays, com.ultratv.tv.nativeapp.ui.mobile.LocalOpenSearch provides openSearch, com.ultratv.tv.nativeapp.ui.mobile.LocalNavBack provides navBack) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        val backEntryRoute by nav.currentBackStackEntryAsState()
        val onPlayer = com.ultratv.tv.nativeapp.ui.mobile.isFullscreenRoute(backEntryRoute?.destination?.route)
        when (layout) {
            com.ultratv.tv.nativeapp.ui.mobile.NavLayout.BOTTOM_TABS, com.ultratv.tv.nativeapp.ui.mobile.NavLayout.SIDE_RAIL -> {
                val profileVm: com.ultratv.tv.nativeapp.ui.profile.ProfileViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val prof by profileVm.current.collectAsState()
                com.ultratv.tv.nativeapp.ui.mobile.MobileScaffold(
                    layout = layout, navController = nav, fullscreen = onPlayer,
                    profile = prof?.let { com.ultratv.tv.nativeapp.ui.mobile.ProfileChip(it.initial, it.color) },
                    onProfile = { profileVm.requestSwitch() },
                    banner = { com.ultratv.tv.nativeapp.ui.common.SyncStatusBanner(onFixSource = { nav.navigate(Routes.SETTINGS) }) },
                ) { NavGraph(nav) }
            }
            com.ultratv.tv.nativeapp.ui.mobile.NavLayout.TV_RAIL -> Column(Modifier.fillMaxSize()) {
                com.ultratv.tv.nativeapp.ui.common.SyncStatusBanner(onFixSource = { nav.navigate(Routes.SETTINGS) })
                // Le contenu est décalé de la largeur REPLIÉE du rail ; le rail déplié passe par-dessus
                // (même Box) sans jamais décaler ni re-mesurer le contenu.
                // Le lecteur est plein écran : ni rail ni marge (sinon le rail volerait le focus et recouvrirait la vidéo).
                val backEntry by nav.currentBackStackEntryAsState()
                val fullscreen = backEntry?.destination?.route?.startsWith("player") == true
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = if (fullscreen) 0.dp else com.ultratv.tv.nativeapp.ui.components.RAIL_COLLAPSED_PX.let { (it / 2f).dp }),
                    ) { NavGraph(nav) }
                    if (!fullscreen) SidebarNav(navController = nav)
                }
            }
        }
        com.ultratv.tv.nativeapp.ui.common.OverlayLayer(overlays)
        com.ultratv.tv.nativeapp.ui.common.ToasterHost()
        com.ultratv.tv.nativeapp.ui.common.ReminderBannerHost()
        }
      }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun NavGraph(nav: androidx.navigation.NavHostController) {
    // Ship the current route to the worker on every back-stack change. Lets us
    // see in /logs which screen the user was on right before a silent crash.
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            RemoteLog.info("nav", "→ ${entry.destination.route ?: "(unknown)"}")
            com.ultratv.tv.nativeapp.perf.PerfTelemetry.onRoute(entry.destination.route)
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        // Pas de fondu de 700 ms entre écrans : sur un CPU de Chromecast c'est
        // du travail de composition en double, et pendant la transition le focus
        // pouvait atterrir dans l'écran sortant.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        screen(Routes.HOME) {
            HomeScreen(
                onGoLive = { nav.navigate(Routes.LIVE) },
                onGoMovies = { nav.navigate(Routes.MOVIES) },
                onGoSeries = { nav.navigate(Routes.SERIES) },
                onGoSettings = { nav.navigate(Routes.SETTINGS) },
                onGoCloud = { StartupNav.cloudPairRequest.value = true; nav.navigate(Routes.SETTINGS) },
                onGoGuide = { nav.navigate(Routes.GUIDE) },
                onGoSearch = { nav.navigate(Routes.SEARCH) },
                onGoFavorites = { nav.navigate(Routes.FAVORITES) },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.LIVE) {
            LiveScreen(onPlay = { url, title -> nav.navigate(Routes.player(url, title)) })
        }
        screen(Routes.MOVIES) {
            androidx.compose.runtime.CompositionLocalProvider(com.ultratv.tv.nativeapp.ui.design.LocalPosterKind provides com.ultratv.tv.nativeapp.data.tmdb.TmdbKind.MOVIE) {
                com.ultratv.tv.nativeapp.ui.catalog.CatalogGridScreen(com.ultratv.tv.nativeapp.ui.catalog.CatalogKind.MOVIES, onOpen = { id -> nav.navigate(Routes.movieDetail(id)) })
            }
        }
        screen(
            Routes.MOVIE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            MovieDetailScreen(
                movieId = id,
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
                onBack = { nav.popBackStack() },
            )
        }
        screen(Routes.SERIES) {
            androidx.compose.runtime.CompositionLocalProvider(com.ultratv.tv.nativeapp.ui.design.LocalPosterKind provides com.ultratv.tv.nativeapp.data.tmdb.TmdbKind.TV) {
                com.ultratv.tv.nativeapp.ui.catalog.CatalogGridScreen(com.ultratv.tv.nativeapp.ui.catalog.CatalogKind.SERIES, onOpen = { id -> nav.navigate(Routes.seriesDetail(id)) })
            }
        }
        screen(
            Routes.SERIES_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            SeriesDetailScreen(
                seriesId = id,
                onPlayEpisode = { url, title -> nav.navigate(Routes.player(url, title)) },
                onBack = { nav.popBackStack() },
            )
        }
        screen(Routes.SEARCH) {
            SearchScreen(
                onOpenChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.GUIDE) {
            GuideGridScreen(
                onPlayChannel = { ch -> nav.navigate(Routes.player(ch.streamUrl, ch.name)) },
                onPlayUrl = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        screen("categories") { CategoriesScreen(onBack = { nav.popBackStack() }) }
        screen("account") { com.ultratv.tv.nativeapp.ui.account.AccountScreen() }
        screen("diagnostic") { com.ultratv.tv.nativeapp.ui.settings.DiagnosticScreen() }
        screen("locked-channels") { com.ultratv.tv.nativeapp.ui.parental.LockedChannelsScreen(onBack = { nav.popBackStack() }) }
        screen("recordings") {
            com.ultratv.tv.nativeapp.ui.recordings.RecordingsScreen(
                onPlayLocal = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        screen(Routes.FAVORITES) {
            FavoritesScreen(
                onPlayChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
                onBrowseLive = { nav.navigate(Routes.LIVE) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.SETTINGS) { SettingsScreen(onNavigate = { route -> nav.navigate(route) }) }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val rawUrl = entry.arguments?.getString("url").orEmpty()
            val rawTitle = entry.arguments?.getString("title").orEmpty()
            val url = java.net.URLDecoder.decode(rawUrl, "UTF-8")
            val title = java.net.URLDecoder.decode(rawTitle, "UTF-8")
            PlayerScreen(url = url, title = title, onBack = { nav.popBackStack() }, onHome = { nav.popBackStack(Routes.HOME, false) })
        }
    }
}


/** `composable` + focus D-pad initial et restauration (voir [ScreenFocusHost]). */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(route, arguments = arguments) { entry ->
        ScreenFocusHost { content(entry) }
    }
}

/** Attente sobre (logo + « Chargement… ») pendant que la base répond, au lieu d'un écran noir. */
@Composable
private fun StartupLoading() {
    Box(Modifier.fillMaxSize().background(com.ultratv.tv.nativeapp.ui.design.Ux.Bg), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.design)) {
            com.ultratv.tv.nativeapp.ui.design.LogoMark(96)
            androidx.compose.material3.CircularProgressIndicator(color = com.ultratv.tv.nativeapp.ui.design.Ux.Accent, strokeWidth = 5.design, modifier = Modifier.size(56.design))
            androidx.tv.material3.Text(com.ultratv.tv.nativeapp.i18n.LocalDs.current.loadingLabel, color = com.ultratv.tv.nativeapp.ui.design.Ux.Text2, fontSize = 24.spx)
        }
    }
}
