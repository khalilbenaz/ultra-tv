package com.ultratv.tv.nativeapp.data.tv

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.FavoriteDao
import com.ultratv.tv.nativeapp.data.db.WatchHistoryDao
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Intégration Google TV / Android TV : « Continuer à regarder » (Watch Next) et chaîne de prévisualisation
 * « Favoris » de l'écran d'accueil. Elle observe la base (historique, favoris) : la lecture n'a rien à appeler,
 * la fin d'une lecture met l'historique à jour, donc Watch Next aussi. Sans effet hors appareil Android TV.
 */
@Singleton
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GoogleTvSync @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val providers: ProviderRepository,
    private val history: WatchHistoryDao,
    private val favorites: FavoriteDao,
    private val channels: ChannelDao,
    private val profiles: com.ultratv.tv.nativeapp.data.profile.ProfileRepository,
    private val movies: com.ultratv.tv.nativeapp.data.db.MovieDao,
    private val seriesDao: com.ultratv.tv.nativeapp.data.db.SeriesDao,
    private val prefsStore: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore,
) {
    private val isTv = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private val prefs = ctx.getSharedPreferences("google_tv", Context.MODE_PRIVATE)
    private var started = false
    @Volatile private var lastWatchNextSync = 0L

    private val activeProvider = providers.observeProviders().map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }.distinctUntilChanged()

    /** (profil courant, source active) : Watch Next et la chaîne Favoris suivent le profil, pas seulement la source. */
    private val scope_ = combine(profiles.currentId, activeProvider) { prof, pid -> prof to pid }.distinctUntilChanged()

    private val gate = TelemetryGate()

    /** Télémétrie « googletv » : émise seulement quand le message change pour sa clé (échecs et changements d'état). */
    private fun report(key: String, message: String, warn: Boolean = false) {
        if (!gate.shouldEmit(key, message)) return
        if (warn) com.ultratv.tv.nativeapp.RemoteLog.warn("googletv", message) else com.ultratv.tv.nativeapp.RemoteLog.info("googletv", message)
    }

    private fun fail(key: String, what: String, e: Throwable) {
        android.util.Log.w("UltraGoogleTv", what, e)
        report("fail:$key", "$what failed: ${e.javaClass.simpleName} ${e.message?.take(160)}", warn = true)
    }

    fun start(scope: CoroutineScope) {
        if (!isTv) { report("skip", "sync skipped: not a leanback device"); return }
        if (started) return
        started = true
        report("start", "sync started")
        scope.launch {
            scope_.flatMapLatest { (prof, pid) -> if (pid == null) { report("skip:wn", "watch next skipped: no active provider"); flowOf(emptyList()) } else history.observeRecent(prof, pid, 40) }
                // 1.2.42 avait mis un debounce de 45 s : tant que l'historique bouge (position enregistrée toutes les 30 s,
                // synchro d'appareils), RIEN n'était publié, et rien non plus si l'appli était quittée / tuée dans les 45 s
                // suivant la fin d'une lecture — « Continuer à regarder » restait vide. Maintenant : 2 s de calme, puis
                // publication immédiate si la dernière écriture date de plus de 45 s, sinon au terme de ce délai (au plus
                // une écriture toutes les 45 s dans Google TV, mais la première ne se fait jamais attendre).
                .debounce(2_000).collectLatest { plan ->
                    delay(WatchNextPlan.waitMs(lastWatchNextSync, System.currentTimeMillis()))
                    runCatching { syncWatchNext(WatchNextPlan.select(plan) + WatchNextPlan.selectLive(plan)) }.onFailure { e -> fail("watchnext", "watch next", e) }
                    lastWatchNextSync = System.currentTimeMillis()
                }
        }
        scope.launch {
            scope_.flatMapLatest { (prof, pid) ->
                if (pid == null) flowOf(emptyList())
                else combine(favorites.observeForKind(prof, pid, "LIVE"), history.observeRecent(prof, pid, 40)) { favs, hist ->
                    val fav = orderedByRemote(pid, favs.map { it.remoteId })
                    val recent = orderedByRemote(pid, WatchNextPlan.selectLive(hist).map { it.remoteId })
                    FavoritesChannelPlan.select(fav, recent)
                }
            }.debounce(3_000).collect { runCatching { syncFavoritesChannel(it) }.onFailure { e -> fail("favorites", "favorites channel", e) } }
        }
        // Chaîne « Nouveautés » : suit la source active. Les tables film / série bougent pendant toute la synchro du
        // catalogue : on attend 5 s de calme (fin de synchro), puis la signature évite toute réécriture inutile.
        scope.launch {
            activeProvider.flatMapLatest { pid -> if (pid == null) { report("skip:news", "news channel skipped: no active provider"); flowOf(emptyList()) } else latestFlow(pid) }
                .debounce(5_000).collect { runCatching { syncNewsChannel(it) }.onFailure { e -> fail("news", "news channel", e) } }
        }
    }

    private fun latestFlow(pid: Long) = combine(movies.observeLatest(pid, NewsChannelPlan.FETCH), seriesDao.observeLatest(pid, NewsChannelPlan.FETCH)) { ms, ss ->
        NewsChannelPlan.select(
            ms.map { NewsChannelPlan.Item(NewsChannelPlan.Kind.MOVIE, it.providerId, it.remoteId, it.title, it.poster, it.addedKey) },
            ss.map { NewsChannelPlan.Item(NewsChannelPlan.Kind.SERIES, it.providerId, it.remoteId, it.title, it.poster, it.addedKey) },
        )
    }

    /** Nom de la chaîne dans la langue de l'appli (« système » : langue de l'appareil). */
    private suspend fun newsChannelName(): String {
        val code = prefsStore.flow.first().language
        val lang = com.ultratv.tv.nativeapp.i18n.AppLang.fromCode(code).let { l ->
            if (l != com.ultratv.tv.nativeapp.i18n.AppLang.System) l
            else com.ultratv.tv.nativeapp.i18n.AppLang.entries.firstOrNull { it.code == java.util.Locale.getDefault().language } ?: com.ultratv.tv.nativeapp.i18n.AppLang.English
        }
        return com.ultratv.tv.nativeapp.i18n.DesignStrings(lang).newsChannelName
    }

    /** Synchro ponctuelle (demande du système à l'initialisation des programmes). */
    suspend fun syncOnce() {
        if (!isTv) return
        val pid = activeProvider.first() ?: return
        val prof = profiles.currentIdNow
        val hist = history.observeRecent(prof, pid, 40).first()
        report("once", "initialize-programs broadcast received")
        runCatching { syncWatchNext(WatchNextPlan.select(hist) + WatchNextPlan.selectLive(hist)) }.onFailure { fail("watchnext", "watch next", it) }
        runCatching {
            val favs = favorites.observeForKind(prof, pid, "LIVE").first()
            syncFavoritesChannel(FavoritesChannelPlan.select(orderedByRemote(pid, favs.map { it.remoteId }), orderedByRemote(pid, WatchNextPlan.selectLive(hist).map { it.remoteId })))
        }.onFailure { fail("favorites", "favorites channel", it) }
        runCatching { syncNewsChannel(latestFlow(pid).first()) }.onFailure { fail("news", "news channel", it) }
    }

    /** Chaînes dans l'ordre des identifiants donnés (la requête par lot ne garantit pas l'ordre). */
    private suspend fun orderedByRemote(pid: Long, ids: List<String>): List<com.ultratv.tv.nativeapp.data.db.ChannelEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = channels.byRemoteIds(pid, ids).associateBy { it.remoteId }
        return ids.mapNotNull { byId[it] }
    }

    // ---- Watch Next -----------------------------------------------------------------

    private fun syncWatchNext(selected: List<com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity>) {
        val cr = ctx.contentResolver
        // Lignes déjà publiées par l'application : identifiant interne -> identifiant de ligne.
        val existing = HashMap<String, Long>()
        cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = WatchNextProgram.fromCursor(c)
                p.internalProviderId?.let { existing[it] = p.id }
            }
        }
        val keep = HashSet<String>()
        var failed = 0
        android.util.Log.i("UltraGoogleTv", "watch next: ${selected.size} (${selected.count { it.kind == "LIVE" }} live)")
        for (h in selected) {
            val key = WatchNextPlan.internalId(h)
            keep += key
            if (h.kind == "LIVE") {
                // Chaîne du direct : vignette 16:9 (logo), marquée « en direct », sans progression.
                val live = WatchNextProgram.Builder()
                    .setType(TvContractCompat.WatchNextPrograms.TYPE_CHANNEL)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(h.watchedAt)
                    .setTitle(h.title)
                    .setLive(true)
                    .setPosterArtUri(Uri.parse(h.poster?.takeIf { it.isNotBlank() } ?: "android.resource://${ctx.packageName}/drawable/banner"))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setInternalProviderId(key)
                    .setIntentUri(Uri.parse(WatchNextPlan.deepLink(h)))
                    .build()
                val rowId = existing[key]
                val ok = if (rowId == null) cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, live.toContentValues()) != null
                else cr.update(TvContractCompat.buildWatchNextProgramUri(rowId), live.toContentValues(), null, null) > 0
                if (!ok) failed++
                continue
            }
            val b = WatchNextProgram.Builder()
                .setType(if (h.kind == "EPISODE") TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(h.watchedAt)
                .setTitle(h.title)
                .setPosterArtUri(Uri.parse(h.poster))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setLastPlaybackPositionMillis(h.positionMs.toInt())
                .setInternalProviderId(key)
                .setIntentUri(Uri.parse(WatchNextPlan.deepLink(h)))
            if (h.durationMs > 0) b.setDurationMillis(h.durationMs.toInt())
            val program = b.build()
            val rowId = existing[key]
            val ok = if (rowId == null) cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues()) != null
            else cr.update(TvContractCompat.buildWatchNextProgramUri(rowId), program.toContentValues(), null, null) > 0
            if (!ok) failed++
        }
        // Terminé, supprimé de l'historique ou éclipsé par un épisode plus récent : on le retire de l'accueil.
        existing.filterKeys { it !in keep }.values.forEach { cr.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
        report("watchnext", "watch next published ${selected.size - failed}/${selected.size} (${selected.count { it.kind == "LIVE" }} live, ${existing.size} existing)", warn = failed > 0)
    }

    // ---- Chaîne de prévisualisation « Favoris » -------------------------------------------

    private fun syncFavoritesChannel(selected: List<com.ultratv.tv.nativeapp.data.db.ChannelEntity>) {
        val helper = PreviewChannelHelper(ctx)
        var channelId = prefs.getLong(K_CHANNEL, -1L)
        if (ChannelPublishPlan.mustRecreate(channelId, channelExists(channelId))) {
            report("favorites:gone", "favorites channel $channelId missing in provider: recreating", warn = true)
            prefs.edit().remove(K_CHANNEL).remove(K_SIGNATURE).apply()
            channelId = -1L
        }
        if (selected.isEmpty() && channelId < 0) { report("skip:fav", "favorites channel skipped: no favorites nor recent live"); return }
        if (channelId < 0) {
            val logo = ContextCompat.getDrawable(ctx, com.ultratv.tv.nativeapp.R.mipmap.ic_launcher)!!.toBitmap(160, 160)
            val channel = PreviewChannel.Builder()
                .setDisplayName(ctx.getString(com.ultratv.tv.nativeapp.R.string.tv_favorites_channel))
                .setAppLinkIntentUri(Uri.parse("${com.ultratv.tv.nativeapp.nav.DeepLink.SCHEME}://home"))
                .setLogo(logo)
                .build()
            channelId = publishChannel(helper, channel)
            if (channelId < 0) { report("favorites:publish", "favorites channel publish refused by system (id=$channelId)", warn = true); return }
            prefs.edit().putLong(K_CHANNEL, channelId).remove(K_SIGNATURE).apply()
            report("favorites:created", "favorites channel created id=$channelId default=${prefs.getBoolean(K_DEFAULT_DONE, false)}")
        }
        val signature = FavoritesChannelPlan.signature(selected)
        if (prefs.getString(K_SIGNATURE, null) == signature) return
        ctx.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        for (c in selected) {
            val b = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(c.title)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setInternalProviderId("LIVE:${c.providerId}:${c.remoteId}")
                .setIntentUri(Uri.parse(FavoritesChannelPlan.deepLink(c)))
            b.setPosterArtUri(Uri.parse(c.logo?.takeIf { it.isNotBlank() } ?: "android.resource://${ctx.packageName}/drawable/banner"))
            helper.publishPreviewProgram(b.build())
        }
        prefs.edit().putString(K_SIGNATURE, signature).apply()
        report("favorites:programs", "favorites channel id=$channelId programs=${selected.size}")
    }

    /**
     * La chaîne existe-t-elle encore dans le fournisseur du système ? Requête directe sur son identifiant, jamais
     * `PreviewChannelHelper.getPreviewChannel` : il lève « Unknown URI …/channel/-1 » (Xiaomi) pour un identifiant
     * absent, et plante (NullPointerException) sur une chaîne dont un champ est vide (Google TV). Toute erreur = absente.
     */
    private fun channelExists(channelId: Long): Boolean {
        if (channelId < 0) return false
        return runCatching {
            ctx.contentResolver.query(TvContractCompat.buildChannelUri(channelId), arrayOf(TvContractCompat.Channels._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /** La première chaîne publiée par l'appli est la chaîne par défaut (affichée d'office) ; les suivantes sont ordinaires. */
    private fun publishChannel(helper: PreviewChannelHelper, channel: PreviewChannel): Long {
        val hasDefault = prefs.getBoolean(K_DEFAULT_DONE, false) || prefs.getLong(K_CHANNEL, -1L) >= 0
        return when (ChannelPublishPlan.mode(hasDefault)) {
            ChannelPublishPlan.Mode.DEFAULT -> helper.publishDefaultChannel(channel).also { if (it >= 0) prefs.edit().putBoolean(K_DEFAULT_DONE, true).apply() }
            ChannelPublishPlan.Mode.NORMAL -> helper.publishChannel(channel)
        }
    }

    /**
     * Intent de l'invite système « Afficher la chaîne Nouveautés sur l'accueil ? » (à lancer avec startActivityForResult
     * depuis une Activity), ou null : pas de chaîne, déjà affichée, déjà demandée une fois, ou invite indisponible.
     */
    fun browsableRequest(): Intent? {
        if (!isTv) return null
        val channelId = prefs.getLong(K_NEWS_CHANNEL, -1L)
        if (channelId < 0) return null
        val browsable = runCatching {
            ctx.contentResolver.query(TvContractCompat.buildChannelUri(channelId), arrayOf(TvContractCompat.Channels.COLUMN_BROWSABLE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
        }.getOrNull() ?: return null
        if (!ChannelPublishPlan.shouldAskBrowsable(channelId, browsable, prefs.getBoolean(K_BROWSABLE_ASKED, false))) return null
        val intent = Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)
        if (intent.resolveActivity(ctx.packageManager) == null) { report("browsable:na", "browsable prompt unavailable on this system", warn = true); return null }
        prefs.edit().putBoolean(K_BROWSABLE_ASKED, true).apply()
        report("browsable:ask", "browsable prompt shown for news channel $channelId")
        return intent
    }

    fun onBrowsableResult(resultOk: Boolean) = report("browsable:result", "browsable prompt result=${if (resultOk) "accepted" else "declined"}")

    // ---- Chaîne de prévisualisation « Nouveautés » ------------------------------------------

    /** Les écritures dans le fournisseur de contenu sont sérialisées (collecte continue + demande du système). */
    private val newsLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun syncNewsChannel(selected: List<NewsChannelPlan.Item>) = newsLock.withLock {
        val helper = PreviewChannelHelper(ctx)
        var channelId = prefs.getLong(K_NEWS_CHANNEL, -1L)
        if (ChannelPublishPlan.mustRecreate(channelId, channelExists(channelId))) {
            report("news:gone", "news channel $channelId missing in provider: recreating", warn = true)
            prefs.edit().remove(K_NEWS_CHANNEL).remove(K_NEWS_SIGNATURE).apply()
            channelId = -1L
        }
        if (selected.isEmpty() && channelId < 0) { report("skip:news0", "news channel skipped: no movie/series with poster yet"); return@withLock }
        val name = newsChannelName()
        if (channelId < 0) {
            val logo = ContextCompat.getDrawable(ctx, com.ultratv.tv.nativeapp.R.mipmap.ic_launcher)!!.toBitmap(160, 160)
            val channel = PreviewChannel.Builder()
                .setDisplayName(name)
                .setAppLinkIntentUri(Uri.parse("${com.ultratv.tv.nativeapp.nav.DeepLink.SCHEME}://home"))
                .setLogo(logo)
                .build()
            // Chaîne ordinaire : l'utilisateur l'ajoute à l'accueil depuis « Personnaliser les chaînes » (seule la
            // première chaîne de l'application est affichée d'office par le système).
            channelId = publishChannel(helper, channel)
            if (channelId < 0) { report("news:publish", "news channel publish refused by system (id=$channelId)", warn = true); return@withLock }
            prefs.edit().putLong(K_NEWS_CHANNEL, channelId).putString(K_NEWS_NAME, name).remove(K_NEWS_SIGNATURE).apply()
            report("news:created", "news channel created id=$channelId default=${prefs.getBoolean(K_DEFAULT_DONE, false)} items=${selected.size}")
        } else if (prefs.getString(K_NEWS_NAME, null) != name) {
            // Langue de l'appli changée : on renomme la chaîne.
            runCatching {
                val current = helper.getPreviewChannel(channelId)
                if (current != null) helper.updatePreviewChannel(channelId, PreviewChannel.Builder(current).setDisplayName(name).build())
            }
            prefs.edit().putString(K_NEWS_NAME, name).apply()
        }
        val signature = NewsChannelPlan.signature(selected)
        val cr = ctx.contentResolver
        val existing = HashMap<String, Long>()
        cr.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = PreviewProgram.fromCursor(c)
                p.internalProviderId?.let { existing[it] = p.id }
            }
        }
        if (!ChannelPublishPlan.mustRewrite(prefs.getString(K_NEWS_SIGNATURE, null) == signature, existing.size, selected.size)) return@withLock
        fun program(i: NewsChannelPlan.Item, index: Int) = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(if (i.kind == NewsChannelPlan.Kind.MOVIE) TvContractCompat.PreviewPrograms.TYPE_MOVIE else TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
            .setTitle(i.title)
            .setPosterArtUri(Uri.parse(i.poster))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
            .setWeight(NewsChannelPlan.weight(index, selected.size))
            .setInternalProviderId(NewsChannelPlan.internalId(i))
            .setIntentUri(Uri.parse(NewsChannelPlan.deepLink(i)))
            .build()
        val diff = NewsChannelPlan.diff(existing, selected)
        var failed = 0
        diff.deleteRowIds.forEach { cr.delete(TvContractCompat.buildPreviewProgramUri(it), null, null) }
        selected.forEachIndexed { index, i ->
            val rowId = diff.update.firstOrNull { it.second === i }?.first
            if (rowId != null) cr.update(TvContractCompat.buildPreviewProgramUri(rowId), program(i, index).toContentValues(), null, null)
            else if (helper.publishPreviewProgram(program(i, index)) < 0) failed++
        }
        // Signature mémorisée seulement si tout est passé : un échec partiel est retenté à la synchro suivante.
        if (failed == 0) prefs.edit().putString(K_NEWS_SIGNATURE, signature).apply()
        report("news:programs", "news channel id=$channelId programs=${selected.size} (+${diff.insert.size} -${diff.deleteRowIds.size}) failed=$failed", warn = failed > 0)
        android.util.Log.i("UltraGoogleTv", "news channel: ${selected.size} (+${diff.insert.size} -${diff.deleteRowIds.size})")
    }

    private companion object {
        const val K_NEWS_CHANNEL = "news_channel_id"
        const val K_NEWS_SIGNATURE = "news_signature"
        const val K_NEWS_NAME = "news_name"
        const val K_DEFAULT_DONE = "default_channel_published"
        const val K_BROWSABLE_ASKED = "news_browsable_asked"
        const val K_CHANNEL = "favorites_channel_id"
        const val K_SIGNATURE = "favorites_signature"
    }
}

/** Le lanceur Android TV demande aux applications de publier leurs programmes (premier lancement, chaîne approuvée). */
@AndroidEntryPoint
class TvInitializeReceiver : BroadcastReceiver() {
    @Inject lateinit var sync: GoogleTvSync

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scope.launch { try { sync.syncOnce() } finally { pending.finish(); scope.cancel() } }
    }
}
