package com.ultratv.tv.nativeapp.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.prefs.HiddenCategoriesStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LiveViewModel @Inject constructor(
    private val provider: ProviderRepository,
    catalog: CatalogRepository,
    private val hiddenStore: HiddenCategoriesStore,
    private val playback: PlaybackContext,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val providers = provider.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: StateFlow<List<ChannelEntity>> =
        combine(providers, hiddenStore.hidden) { ps, hidden -> ps to hidden }
            .flatMapLatest { (ps, hidden) ->
                val pid = ps.firstOrNull { it.active }?.id ?: ps.firstOrNull()?.id
                if (pid == null) flowOf(emptyList())
                else catalog.channels(pid).map { list ->
                    list.filter { ch ->
                        val cid = ch.categoryId ?: return@filter true
                        hiddenStore.keyFor("LIVE", pid, cid) !in hidden
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun resolveAndPlay(channel: ChannelEntity, onReady: (url: String, title: String) -> Unit) {
        fun register(url: String) {
            playback.set(PlaybackContext.Item(
                providerId = channel.providerId,
                kind = "LIVE",
                remoteId = channel.remoteId,
                title = channel.name,
                poster = channel.logo,
                streamUrl = url,
            ))
        }
        if (!channel.streamUrl.startsWith("stalker://")) {
            register(channel.streamUrl)
            onReady(channel.streamUrl, channel.name)
            return
        }
        viewModelScope.launch {
            _resolving.value = true
            try {
                val resolved = provider.resolvePlayUrl(channel.id, channel.streamUrl)
                register(resolved)
                onReady(resolved, channel.name)
            } finally {
                _resolving.value = false
            }
        }
    }
}
