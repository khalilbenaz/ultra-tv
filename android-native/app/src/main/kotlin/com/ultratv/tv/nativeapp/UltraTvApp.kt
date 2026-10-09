package com.ultratv.tv.nativeapp

import kotlinx.coroutines.cancel
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application root. Implements Coil's [ImageLoaderFactory] for app-wide image
 * caching and WorkManager's [Configuration.Provider] so [SyncWorker] can be
 * instantiated through Hilt with its repository dependencies.
 *
 * Also hooks the uncaught-exception handler to ship crashes directly to the
 * Cloudflare Worker via [RemoteLog.crashSync], with no local file buffer.
 */
@HiltAndroidApp
class UltraTvApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var deviceMac: com.ultratv.tv.nativeapp.data.config.DeviceMac
    // Lazy : résolus en tâche de fond, pas pendant Application.onCreate (graphe Hilt — base, réseau — construit avant
    // le premier écran sinon).
    @Inject lateinit var secretsMigrator: dagger.Lazy<com.ultratv.tv.nativeapp.data.security.ProviderSecretsMigrator>
    @Inject lateinit var adaptive: com.ultratv.tv.nativeapp.adaptive.AdaptiveProfile
    @Inject lateinit var prefsStore: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore

    @Inject lateinit var googleTv: dagger.Lazy<com.ultratv.tv.nativeapp.data.tv.GoogleTvSync>
    @Inject lateinit var hiddenCategories: dagger.Lazy<com.ultratv.tv.nativeapp.data.prefs.HiddenCategoriesStore>
    @Inject lateinit var okHttp: dagger.Lazy<okhttp3.OkHttpClient>
    @Inject lateinit var traktRepo: dagger.Lazy<com.ultratv.tv.nativeapp.data.trakt.TraktLibraryRepository>
    @Inject lateinit var traktMatcher: dagger.Lazy<com.ultratv.tv.nativeapp.data.trakt.TraktCatalogMatcher>
    @Inject lateinit var providerRepo: dagger.Lazy<com.ultratv.tv.nativeapp.data.repo.ProviderRepository>
    @Inject lateinit var posters: dagger.Lazy<com.ultratv.tv.nativeapp.data.tmdb.PosterResolver>

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        val auto = adaptive.state.value.auto
        val low = auto.lowRam
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (low) 0.08 else 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (low) 96L * 1024 * 1024 else 256L * 1024 * 1024)
                    .build()
            }
            // Client réseau PARTAGÉ (un seul pool de connexions et de fils) ; décodages limités à 2 sur l'entrée de gamme
            // (défilement rapide d'une grille de logos : pics de mémoire sinon).
            // Connexions partagées avec le reste de l'appli, mais SANS l'intercepteur de mesure du débit (les images ne
            // doivent pas fausser le profil adaptatif du lecteur ni appeler ConnectivityManager à chaque vignette) et avec
            // sa propre file de requêtes (les logos n'attendent pas derrière une synchro).
            .okHttpClient { okHttp.get().newBuilder().apply { networkInterceptors().clear() }.dispatcher(okhttp3.Dispatcher()).build() }
            .apply { if (low) decoderDispatcher(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)) }
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // Entrée de gamme : bitmaps logiciels RGB_565 (moitié moins de mémoire). Les bitmaps matériels l'empêchaient
            // (un bitmap HARDWARE n'est jamais converti en RGB_565) : on ne les garde que sur les appareils confortables.
            .allowHardware(!auto.imageRgb565)
            .allowRgb565(auto.imageRgb565)
            // Pas de fondu sur l'entrée de gamme : c'est une animation par image chargée.
            // Ni fondu sur télévision : le défilement à la télécommande déclenche des dizaines de chargements, chacun avec
            // une animation d'alpha par image (SlotImage fait de même).
            .crossfade(!low && !com.ultratv.tv.nativeapp.ui.common.isTelevision(this))
            .build()
    }

    /** Pression mémoire : on rend d'abord les caches d'images (le plus facile à reconstruire depuis le disque). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val low = runCatching { adaptive.state.value.auto.lowRam }.getOrDefault(false)
        if (level >= TRIM_MEMORY_RUNNING_LOW || (low && level >= TRIM_MEMORY_UI_HIDDEN)) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
            runCatching { posters.get().trim() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Départ de la mesure « démarrage à froid » (jusqu'à la première image affichée sur un écran).
        com.ultratv.tv.nativeapp.perf.PerfTelemetry.markAppCreate()

        // Tell RemoteLog who we are before anyone calls it.
        // Version lue dans BuildConfig : pas d'appel système (Binder) synchrone avant le premier écran.
        RemoteLog.init(
            ctx = this,
            mac = deviceMac.mac,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        RemoteLog.info("app", "onCreate")

        // Mirror the telemetry toggle from DataStore into RemoteLog's volatile
        // flag so disabling it from Settings takes effect immediately for
        // every subsequent event/crash POST.
        bgScope.launch {
            prefsStore.flow.collect { p ->
                RemoteLog.telemetryEnabled = p.telemetryEnabled
                RemoteLog.workerUrlOverride = p.workerBaseUrl
                com.ultratv.tv.nativeapp.ui.common.EpgClock.offsetMinutes = p.epgTimeOffsetMin
                com.ultratv.tv.nativeapp.ui.common.EpgClock.zoneId = p.timeZone
                com.ultratv.tv.nativeapp.data.repo.LocalLogos.treeUri = p.localLogosFolderUri
            }
        }

        // Google TV : Watch Next + chaîne Favoris, alimentés par la base (sans effet hors Android TV).
        // Démarrée après le premier écran : ses observateurs de base n'ont rien d'urgent.
        // runCatching : sous Robolectric, l'application est recréée à chaque test et ce réveil différé tombait pendant le
        // test SUIVANT (environnement détruit) — exception attribuée à un autre test (UncaughtExceptionsBeforeTest).
        bgScope.launch { kotlinx.coroutines.delay(8_000); runCatching { googleTv.get().start(bgScope) } }

        // Trakt : recharge la dernière bibliothèque du disque et calcule les rangées de la source active dès le lancement
        // (l'accueil les trouve déjà prêtes), puis relit le réseau selon la règle des 15 min. runCatching : jamais bloquant.
        bgScope.launch {
            runCatching {
                val repo = traktRepo.get()
                repo.restore()
                if (repo.library.value.linked) {
                    val pid = providerRepo.get().observeProviders()
                        .map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }.distinctUntilChanged()
                    traktMatcher.get().warmUp(bgScope, pid)
                    val p = prefsStore.flow.first()
                    repo.refreshIfStale(com.ultratv.tv.nativeapp.data.trakt.TraktLibraryRepository.langParam(p.language, java.util.Locale.getDefault().language))
                }
            }
        }

        // Chiffre les mots de passe fournisseurs hérités (clair -> AES-GCM Keystore).
        bgScope.launch { runCatching { secretsMigrator.get().migrate() } }
        // Anciennes catégories masquées (globales) → profil Principal, une seule fois.
        bgScope.launch { runCatching { hiddenCategories.get().importLegacyOnce() } }

        // Pipe every uncaught crash straight to the worker. crashSync blocks
        // briefly (≤ 3 s) so the request actually leaves the device before the
        // process is replaced by the system death dialog. The previous handler
        // (if any) is invoked afterwards so the OS still gets to log + kill.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            RemoteLog.crashSync(t, e)
            previous?.uncaughtException(t, e)
        }

        // Initialise the Cast SDK eagerly so the first time the player asks
        // for CastContext.getSharedInstance() it doesn't block. We swallow the
        // exception when Google Play Services are absent (some Android TV
        // builds strip them) — the player will just not show the Cast button.
        // En arrière-plan : l'initialisation de Play Services Cast coûte plusieurs
        // dizaines de ms sur le thread principal au démarrage.
        bgScope.launch {
            runCatching {
                com.google.android.gms.cast.framework.CastContext.getSharedInstance(
                    this@UltraTvApp, java.util.concurrent.Executors.newSingleThreadExecutor(),
                )
            }
        }
    }

    override fun onTerminate() {
        // Rarely called on real devices, but useful when the simulator quits.
        RemoteLog.info("app", "onTerminate")
        // Jamais appelé en production ; appelé par Robolectric à la fin de chaque test : les tâches de fond (différées
        // jusqu'à 8 s) ne doivent pas survivre à leur application.
        bgScope.cancel()
        anrWatchdog.interrupt()
        super.onTerminate()
    }

    /**
     * Cheap ANR detector: a background thread pings the main looper every 2 s
     * and waits 5 s for the ping to come back. If it doesn't, we know the main
     * thread has been blocked at least that long and we ship an event with
     * the active thread dump. Beats getting silent "app closed" reports with
     * no logs (Android kills frozen UIs without going through our crash hook).
     */
    /** Fil de surveillance des blocages : interrompu dans [onTerminate] (tests : une application par test). */
    private val anrWatchdog: Thread

    init {
        anrWatchdog = Thread({
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            while (true) {
                // Toutes les 5 s (et en veille longue si la télémétrie est coupée : le rapport n'irait nulle part) —
                // avant : un réveil toutes les 2 s en permanence, y compris box en veille.
                try { Thread.sleep(if (RemoteLog.telemetryEnabled) 5_000 else 30_000) } catch (_: InterruptedException) { return@Thread }
                if (!RemoteLog.telemetryEnabled) continue
                val ack = java.util.concurrent.atomic.AtomicBoolean(false)
                mainHandler.post { ack.set(true) }
                var waited = 0
                while (!ack.get() && waited < 5_000) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                    waited += 250
                }
                if (!ack.get()) {
                    val mainTrace = android.os.Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { "  at $it" }
                    // Aussi en local : sans jeton de télémétrie, la trace du thread principal
                    // n'irait nulle part et l'ANR resterait inexplicable.
                    android.util.Log.w("UltraANR", "main thread blocked >= 5 s\n$mainTrace")
                    RemoteLog.error(
                        "anr",
                        "main thread blocked ≥ 5 s\n$mainTrace",
                    )
                    // Don't spam: back off until main responds again.
                    while (!ack.get()) {
                        try { Thread.sleep(2_000) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }
        }, "ultra-anr-watchdog").apply { isDaemon = true; start() }
    }
}
