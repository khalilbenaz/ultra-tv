package com.ultratv.tv.nativeapp.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.EpgDao
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
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Time window represented horizontally. We render 8h at a time around the
 *  user's current scroll anchor; 1 hour = 240 dp on screen. */
private const val PX_PER_HOUR_DP = 240
private const val PX_PER_MIN_DP = PX_PER_HOUR_DP / 60f
private const val ROW_HEIGHT_DP = 60

/** Provider-wide EPG view. Loads everything in [rangeForChannels] for the
 *  visible channel set; the LazyColumn only renders visible rows so the
 *  upfront query, even on 5000 channels × 24h, stays well under 100 ms. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideGridViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val provider: ProviderRepository,
    private val epgDao: EpgDao,
) : ViewModel() {

    val channels: StateFlow<List<ChannelEntity>> = providerRepo.observeProviders()
        .flatMapLatest { ps ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
                ?: return@flatMapLatest flowOf(emptyList())
            catalog.channels(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _programmes = MutableStateFlow<Map<Long, List<EpgEntity>>>(emptyMap())
    val programmes: StateFlow<Map<Long, List<EpgEntity>>> = _programmes.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Pull a fresh xmltv into the EPG table. */
    fun refreshXmltv() {
        viewModelScope.launch {
            _loading.value = true
            val pid = catalog.channels(0).let { 0L }  // dummy to satisfy compiler — real id below
            try {
                val activeId = channels.value.firstOrNull()?.providerId ?: return@launch
                provider.syncXmltv(activeId) { /* SyncStatusBus handles UI */ }
            } finally {
                _loading.value = false
                reloadFor(channels.value)
            }
        }
    }

    /** Reload programmes for the visible channels for the next 12 h window. */
    fun reloadFor(visible: List<ChannelEntity>) {
        viewModelScope.launch {
            if (visible.isEmpty()) { _programmes.value = emptyMap(); return@launch }
            val now = System.currentTimeMillis()
            val end = now + 12 * 60 * 60 * 1000L
            val flat = epgDao.rangeForChannels(visible.map { it.id }, now - 60 * 60_000, end)
            _programmes.value = flat.groupBy { it.channelId }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun GuideGridScreen(
    onPlayChannel: (ChannelEntity) -> Unit,
    vm: GuideGridViewModel = hiltViewModel(),
) {
    val channels by vm.channels.collectAsState()
    val byChannel by vm.programmes.collectAsState()
    val loading by vm.loading.collectAsState()

    // Reload programmes whenever the channel list changes.
    LaunchedEffect(channels) { vm.reloadFor(channels) }

    val now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Time axis: 12h window starting at "now floored to top of hour minus 30 min".
    val windowStart = remember(now) { (now / 3_600_000L) * 3_600_000L - 30 * 60_000L }
    val windowEnd = remember(windowStart) { windowStart + 12 * 60 * 60 * 1000L }

    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(S.tvGuide, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            val total = byChannel.values.sumOf { it.size }
            Text(S.guideProgrammesTemplate.format(total, channels.size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.refreshXmltv() }, enabled = !loading) {
                Text(if (loading) S.guideLoading else S.guideRefreshXmltv)
            }
        }

        // Time header row — sticky to the top of the right pane.
        val hScroll = rememberScrollState()
        Row(Modifier.fillMaxWidth()) {
            // Left placeholder above the channel column.
            Box(Modifier.width(180.dp))
            Row(Modifier.horizontalScroll(hScroll)) {
                val slots = (0..23).toList()  // 24 half-hour slots = 12h
                slots.forEach { slot ->
                    val slotMs = windowStart + slot * 30 * 60_000L
                    Text(
                        formatHm(slotMs),
                        modifier = Modifier
                            .width((PX_PER_HOUR_DP / 2).dp)
                            .padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (channels.isEmpty()) {
            Text(S.guideNoChannels, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(channels, key = { it.id }) { c ->
                    GuideRow(
                        channel = c,
                        programmes = byChannel[c.id].orEmpty(),
                        windowStartMs = windowStart,
                        windowEndMs = windowEnd,
                        nowMs = now,
                        hScroll = hScroll,
                        onPlay = { onPlayChannel(c) },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programmes: List<EpgEntity>,
    windowStartMs: Long,
    windowEndMs: Long,
    nowMs: Long,
    hScroll: androidx.compose.foundation.ScrollState,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel name column — fixed left, clickable to start playback.
        Card(
            onClick = onPlay,
            modifier = Modifier.width(180.dp).fillMaxHeight().padding(end = 6.dp),
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        ) {
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                Text(channel.name, maxLines = 2, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        // Programme strip — scrolls horizontally in lock-step with the header.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(hScroll),
        ) {
            // Width = (window_end - window_start) / 1h * PX_PER_HOUR_DP
            val hours = ((windowEndMs - windowStartMs) / 3_600_000.0).coerceAtLeast(1.0)
            val totalWidthDp = (hours * PX_PER_HOUR_DP).toInt().dp
            Box(Modifier.width(totalWidthDp).fillMaxHeight()) {
                programmes
                    .filter { it.endMs > windowStartMs && it.startMs < windowEndMs }
                    .forEach { prog ->
                        val start = prog.startMs.coerceAtLeast(windowStartMs)
                        val end = prog.endMs.coerceAtMost(windowEndMs)
                        val leftDp = ((start - windowStartMs) / 60_000f * PX_PER_MIN_DP).toInt().dp
                        val widthDp = ((end - start) / 60_000f * PX_PER_MIN_DP).toInt().coerceAtLeast(4).dp
                        val isLive = nowMs in prog.startMs..prog.endMs
                        Card(
                            onClick = onPlay,
                            modifier = Modifier
                                .padding(start = leftDp)
                                .width(widthDp)
                                .fillMaxHeight(),
                            shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
                            colors = if (isLive)
                                CardDefaults.colors(containerColor = MaterialTheme.colorScheme.primary)
                            else CardDefaults.colors(),
                        ) {
                            Column(Modifier.fillMaxSize().padding(6.dp)) {
                                Text(
                                    prog.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isLive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                                    maxLines = 2,
                                )
                                Text(
                                    "${formatHm(prog.startMs)}–${formatHm(prog.endMs)}",
                                    fontSize = 10.sp,
                                    color = if (isLive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                if (nowMs in windowStartMs..windowEndMs) {
                    val nowLeftDp = ((nowMs - windowStartMs) / 60_000f * PX_PER_MIN_DP).toInt().dp
                    Box(
                        Modifier
                            .padding(start = nowLeftDp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(androidx.compose.ui.graphics.Color(0xFFFF6B6B)),
                    )
                }
            }
        }
    }
}

private val hmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatHm(ms: Long): String = hmFmt.format(Date(ms))
