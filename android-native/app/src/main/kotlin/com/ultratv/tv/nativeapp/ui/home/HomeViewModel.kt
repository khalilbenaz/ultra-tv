package com.ultratv.tv.nativeapp.ui.home

import kotlinx.coroutines.flow.take
import com.ultratv.tv.nativeapp.data.repo.atMostEvery
import com.ultratv.tv.nativeapp.data.repo.snapshotWhile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.EpgDao
import com.ultratv.tv.nativeapp.data.db.EpgEntity
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.data.repo.SyncStatusBus
import com.ultratv.tv.nativeapp.data.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Élément « À la une » : uniquement des métadonnées RÉELLES de la source (jamais inventées). */
data class HeroItem(
    val kind: Kind,
    val id: Long,
    val title: String,
    val year: Int?,
    val genre: String?,
    val rating: Double?,
    /** Image PAYSAGE si la source en fournit une (backdrop), sinon null. */
    val backdrop: String?,
    val poster: String?,
) { enum class Kind { SERIES, MOVIE } }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val syncCoordinator: SyncCoordinator,
    provider: ProviderRepository,
    private val catalog: CatalogRepository,
    private val history: HistoryRepository,
    private val playback: PlaybackContext,
    private val epgDao: EpgDao,
    bus: SyncStatusBus,
    private val traktLibrary: com.ultratv.tv.nativeapp.data.trakt.TraktLibraryRepository,
    traktMatcher: com.ultratv.tv.nativeapp.data.trakt.TraktCatalogMatcher,
    private val prefsStore: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore,
) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> = provider.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `null` tant que Room n'a pas répondu (évite de montrer « aucune source » une fraction de seconde). */
    // Dérivé de [providers] (même observateur Room) plutôt qu'une seconde souscription à la table.
    val providersLoaded: StateFlow<Boolean> = provider.observeProviders().take(1).map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val syncStatus = bus.status
    /** Seul le pourcentage est affiché : l'accueil n'est plus recomposé à chaque message de progression. */
    val syncPercent: StateFlow<Int?> = bus.status.map { it?.percent }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Synchro en cours : les requêtes de l'accueil ne sont lues qu'une fois (instantané) au lieu d'être relancées à chaque lot inséré. */
    private val syncing: Flow<Boolean> = bus.status.map { it != null }.distinctUntilChanged()

    private val pid: Flow<Long?> = providers.map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }.distinctUntilChanged()

    val continueWatching: StateFlow<List<WatchHistoryEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.continueWatching(id, 12) }
        // Reçu d'un autre appareil sans adresse locale (épisode d'une série jamais ouverte ici) : pas de carte cliquable.
        // Épisode sans image (anciennes lignes) : affiche de la série.
        .map { l -> l.filter { it.streamUrl.isNotBlank() }.map { h ->
            if (h.poster.isNullOrBlank() && h.kind == "EPISODE" && h.parentRemoteId != null)
                catalog.seriesByRemote(h.providerId, h.parentRemoteId)?.let { s -> h.copy(poster = s.poster ?: s.backdrop) } ?: h
            else h
        } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Rangées Trakt (watchlist, recommandations) : UNIQUEMENT les éléments présents dans la source active, dans l'ordre
     * de Trakt. Vides si le compte n'est pas lié à Trakt, si l'appareil n'est pas appairé ou si rien n'est disponible.
     */
    val trakt: StateFlow<com.ultratv.tv.nativeapp.data.trakt.TraktRows> = traktMatcher.rows(pid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.ultratv.tv.nativeapp.data.trakt.TraktRows.EMPTY)

    init {
        // Changement de source active → relecture si la bibliothèque a plus de 15 min (sinon sans effet).
        viewModelScope.launch { pid.collect { refreshTrakt() } }
    }

    /** Appelé aussi au retour au premier plan : la bibliothèque n'est relue qu'au bout de 15 min. */
    fun refreshTrakt() {
        viewModelScope.launch {
            val lang = com.ultratv.tv.nativeapp.data.trakt.TraktLibraryRepository.langParam(
                prefsStore.flow.first().language, java.util.Locale.getDefault().language,
            )
            traktLibrary.refreshIfStale(lang)
        }
    }

    /** Derniers films / séries ajoutés par le fournisseur. */
    val latestMovies: StateFlow<List<com.ultratv.tv.nativeapp.ui.catalog.PosterItem>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.latestMovies(id, 15).atMostEvery(1_000).snapshotWhile(syncing) }
        .map { l -> l.map { com.ultratv.tv.nativeapp.ui.catalog.PosterItem(it.id, it.title, it.poster, it.year, it.rating) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latestSeries: StateFlow<List<com.ultratv.tv.nativeapp.ui.catalog.PosterItem>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.latestSeries(id, 15).atMostEvery(1_000).snapshotWhile(syncing) }
        .map { l -> l.map { com.ultratv.tv.nativeapp.ui.catalog.PosterItem(it.id, it.title, it.poster, it.year, it.rating) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dernières chaînes regardées (historique du profil). */
    val recentChannels: StateFlow<List<WatchHistoryEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.recentByKind(id, "LIVE", 12) }
        .map { l -> l.filter { it.streamUrl.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** « À la une » : la série la mieux notée avec image paysage, à défaut le film le mieux noté. */
    val hero: StateFlow<HeroItem?> = pid.flatMapLatest { id ->
        if (id == null) flowOf(null)
        // Pendant la synchro, les tables changent sans arrêt : au plus une relecture par seconde.
        else combine(catalog.heroSeries(id).atMostEvery(1_000).snapshotWhile(syncing), catalog.heroMovie(id).atMostEvery(1_000).snapshotWhile(syncing)) { s, m ->
            when {
                s != null -> HeroItem(HeroItem.Kind.SERIES, s.id, s.title, s.year, s.genre?.substringBefore(','), s.rating, s.backdrop, s.poster)
                m != null -> HeroItem(HeroItem.Kind.MOVIE, m.id, m.title, m.year, m.genre?.substringBefore(','), m.rating, m.backdrop, m.poster)
                else -> null
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Favorites en premier ; sans favori, des chaînes avec logo. [favorite] dit lequel des deux. */
    val favoriteChannels: StateFlow<List<ChannelEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.favoriteChannels(id, 12).atMostEvery(1_000).snapshotWhile(syncing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Chaînes de repli observées SEULEMENT quand il n'y a aucun favori (avant : toujours abonnées).
    val channels: StateFlow<List<ChannelEntity>> = combine(pid, favoriteChannels) { id, fav -> id to fav }
        .flatMapLatest { (id, fav) ->
            if (fav.isNotEmpty() || id == null) flowOf(fav) else catalog.channelsWithLogo(id, 12).atMostEvery(1_000).snapshotWhile(syncing)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showingFavorites: StateFlow<Boolean> = favoriteChannels.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Programme en cours par chaîne affichée, rafraîchi toutes les minutes. */
    val nowPlaying: StateFlow<Map<Long, EpgEntity>> = channels.flatMapLatest { chans ->
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                val rows = if (chans.isEmpty()) emptyList() else epgDao.rangeForChannels(chans.map { it.id }, now, now + 60_000)
                emit(rows.filter { it.startMs <= now && it.endMs > now }.associateBy { it.channelId })
                delay(60_000)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Sets the playback context from a history entry so the player can record proper context. */
    fun playFromHistory(h: WatchHistoryEntity) {
        playback.set(PlaybackContext.Item(
            providerId = h.providerId, kind = h.kind, remoteId = h.remoteId, title = h.title,
            poster = h.poster, streamUrl = h.streamUrl, parentRemoteId = h.parentRemoteId,
        ))
    }

    fun dismiss(h: WatchHistoryEntity) { viewModelScope.launch { history.remove(h.providerId, h.kind, h.remoteId) } }

    fun refresh() {
        val id = providers.value.firstOrNull { it.active }?.id ?: providers.value.firstOrNull()?.id ?: return
        syncCoordinator.request(id, force = true)
    }
}
