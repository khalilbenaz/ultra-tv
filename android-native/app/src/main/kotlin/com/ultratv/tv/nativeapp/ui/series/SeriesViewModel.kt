package com.ultratv.tv.nativeapp.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.EpisodeEntity
import com.ultratv.tv.nativeapp.data.db.SeriesEntity
import com.ultratv.tv.nativeapp.data.prefs.HiddenCategoriesStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesListViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val hiddenStore: HiddenCategoriesStore,
) : ViewModel() {

    private val _sel = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _sel.asStateFlow()

    private val providers = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = combine(
        providers, hiddenStore.hidden,
    ) { ps, hidden -> ps to hidden }
        .flatMapLatest { (ps, hidden) ->
            val pid = ps.firstOrNull()?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.categories(pid, "SERIES").map { list ->
                list.filter { hiddenStore.keyFor("SERIES", pid, it.remoteId) !in hidden }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<SeriesEntity>> = combine(
        providers, _sel, hiddenStore.hidden,
    ) { ps, cat, hidden -> Triple(ps, cat, hidden) }
        .flatMapLatest { (ps, cat, hidden) ->
            val pid = ps.firstOrNull()?.id ?: return@flatMapLatest flowOf(emptyList())
            val all: Flow<List<SeriesEntity>> = catalog.seriesList(pid)
            all.map { list ->
                list.filter { s ->
                    val cid = s.categoryId
                    if (cid != null && hiddenStore.keyFor("SERIES", pid, cid) in hidden) return@filter false
                    if (cat == null) true else s.categoryId == cat
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectCategory(remoteId: String?) { _sel.value = remoteId }
}

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val playback: PlaybackContext,
) : ViewModel() {

    fun registerPlay(seriesName: String, seriesRemoteId: String, providerId: Long, episode: EpisodeEntity) {
        playback.set(PlaybackContext.Item(
            providerId = providerId,
            kind = "EPISODE",
            remoteId = episode.remoteId,
            title = "$seriesName · S${"%02d".format(episode.season)}E${"%02d".format(episode.episode)} · ${episode.title}",
            poster = null,
            streamUrl = episode.streamUrl,
            parentRemoteId = seriesRemoteId,
        ))
    }

    private val _series = MutableStateFlow<SeriesEntity?>(null)
    val series: StateFlow<SeriesEntity?> = _series.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _episodes = MutableStateFlow<List<EpisodeEntity>>(emptyList())
    val episodes: StateFlow<List<EpisodeEntity>> = _episodes.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            _series.value = catalog.seriesById(id)
            _loading.value = true
        }
        viewModelScope.launch {
            runCatching { catalog.loadEpisodes(id) }
            _loading.value = false
        }
        viewModelScope.launch {
            catalog.episodes(id).collect { _episodes.value = it }
        }
    }
}
