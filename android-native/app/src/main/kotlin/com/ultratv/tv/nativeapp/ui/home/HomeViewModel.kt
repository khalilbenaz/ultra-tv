package com.ultratv.tv.nativeapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.config.DeviceMac
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.db.SeriesEntity
import com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val provider: ProviderRepository,
    private val catalog: CatalogRepository,
    private val history: HistoryRepository,
    private val deviceMac: DeviceMac,
    private val playback: PlaybackContext,
) : ViewModel() {

    val mac: String = deviceMac.mac

    val providers: StateFlow<List<ProviderEntity>> = provider.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val pid = providers.map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }

    val continueWatching: StateFlow<List<WatchHistoryEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.continueWatching(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyWatched: StateFlow<List<WatchHistoryEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.recent(id, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featuredMovies: StateFlow<List<MovieEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.movies(id).map { l -> l.take(20) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featuredSeries: StateFlow<List<SeriesEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.seriesList(id).map { l -> l.take(20) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featuredChannels: StateFlow<List<ChannelEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.channels(id).map { l -> l.take(30) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sets the playback context from a history entry so the player can record proper context. */
    fun playFromHistory(h: WatchHistoryEntity) {
        playback.set(PlaybackContext.Item(
            providerId = h.providerId,
            kind = h.kind,
            remoteId = h.remoteId,
            title = h.title,
            poster = h.poster,
            streamUrl = h.streamUrl,
            parentRemoteId = h.parentRemoteId,
        ))
    }
}
