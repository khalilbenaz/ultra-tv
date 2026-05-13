package com.ultratv.tv.nativeapp.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.EpgEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {

    val channels: StateFlow<List<ChannelEntity>> = providerRepo.observeProviders()
        .flatMapLatest { ps ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.channels(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _epgByChannel = MutableStateFlow<Map<Long, List<EpgEntity>>>(emptyMap())
    val epgByChannel: StateFlow<Map<Long, List<EpgEntity>>> = _epgByChannel.asStateFlow()

    fun loadEpgFor(channelId: Long) {
        viewModelScope.launch {
            catalog.refreshShortEpg(channelId)
            catalog.upcomingEpg(channelId).collect { rows ->
                _epgByChannel.value = _epgByChannel.value + (channelId to rows)
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(vm: GuideViewModel = hiltViewModel()) {
    val channels by vm.channels.collectAsState()
    val epgMap by vm.epgByChannel.collectAsState()
    var expanded by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TV Guide", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Click a channel to load its next programmes.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(channels, key = { it.id }) { c ->
                val rows = epgMap[c.id].orEmpty()
                Card(
                    onClick = {
                        if (expanded == c.id) expanded = null
                        else {
                            expanded = c.id
                            if (rows.isEmpty()) vm.loadEpgFor(c.id)
                        }
                    },
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(c.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (expanded == c.id) {
                            if (rows.isEmpty()) Text("Loading EPG…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            else rows.take(5).forEach { e ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(formatTime(e.startMs), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                                    Text(e.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(ms: Long): String = fmt.format(java.util.Date(ms))
