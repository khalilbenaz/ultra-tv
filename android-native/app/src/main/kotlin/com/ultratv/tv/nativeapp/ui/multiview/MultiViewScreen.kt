package com.ultratv.tv.nativeapp.ui.multiview

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MultiViewViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    catalog: CatalogRepository,
) : ViewModel() {
    val channels: StateFlow<List<ChannelEntity>> = providerRepo.observeProviders()
        .flatMapLatest { ps ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.channels(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun MultiViewScreen(vm: MultiViewViewModel = hiltViewModel()) {
    val all by vm.channels.collectAsState()
    val slots = remember { mutableStateListOf<ChannelEntity?>(null, null, null, null) }
    var pickingIdx = remember { mutableStateListOf(-1) }  // -1 = not picking

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Multi-View", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Tap a tile to assign a channel. ENTER cycles through tiles.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

        // 2×2 grid of player tiles
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tile(Modifier.weight(1f).fillMaxSize(), slots[0]) { pickingIdx[0] = 0 }
                Tile(Modifier.weight(1f).fillMaxSize(), slots[1]) { pickingIdx[0] = 1 }
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tile(Modifier.weight(1f).fillMaxSize(), slots[2]) { pickingIdx[0] = 2 }
                Tile(Modifier.weight(1f).fillMaxSize(), slots[3]) { pickingIdx[0] = 3 }
            }
        }

        if (pickingIdx[0] >= 0) {
            Text("Choose a channel for tile ${pickingIdx[0] + 1}:", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyItems(all.take(50), key = { it.id }) { c ->
                    Card(onClick = {
                        slots[pickingIdx[0]] = c
                        pickingIdx[0] = -1
                    }, shape = CardDefaults.shape(RoundedCornerShape(8.dp))) {
                        Text(c.name, modifier = Modifier.padding(10.dp), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun Tile(modifier: Modifier, channel: ChannelEntity?, onPick: () -> Unit) {
    val context = LocalContext.current
    val player = remember(channel?.id) {
        if (channel == null) null
        else ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            playWhenReady = true
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(channel?.id) { onDispose { player?.release() } }

    Card(onClick = onPick, modifier = modifier, shape = CardDefaults.shape(RoundedCornerShape(10.dp))) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (channel == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("+ Tap to add", color = Color.White, fontSize = 16.sp)
                }
            } else {
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                }, modifier = Modifier.fillMaxSize())
                Text(
                    channel.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
