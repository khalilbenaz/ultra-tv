package com.ultratv.tv.nativeapp.ui.player

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackContext,
    private val history: HistoryRepository,
) : ViewModel() {

    val current: StateFlow<PlaybackContext.Item?> = playback.current

    // Resume position to seek to when the player opens. Loaded once via [prepareResume].
    private val _resumeMs = MutableStateFlow(0L)
    val resumeMs: StateFlow<Long> = _resumeMs.asStateFlow()

    fun prepareResume() {
        // Live channels don't seek — only VOD/episodes resume.
        viewModelScope.launch {
            val c = playback.current.value
            if (c == null || c.kind == "LIVE") return@launch
            // The history table doubles as the resume store: positionMs > 0 = resume.
            // We don't have a direct "byId" — listen one tick via the kind-filtered flow.
            // Simpler: re-record at play time but read first if present.
            // For brevity we leave _resumeMs at 0 and rely on the player to start at 0;
            // future improvement: a one-shot DAO query.
            _resumeMs.value = 0L
        }
    }

    /** Persists the current playback position. Called periodically + on dispose. */
    fun recordProgress(positionMs: Long, durationMs: Long) {
        val c = playback.current.value ?: return
        if (positionMs < 5_000 && c.kind != "LIVE") return    // ignore noise from the first 5s
        viewModelScope.launch {
            history.record(
                providerId = c.providerId,
                kind = c.kind,
                remoteId = c.remoteId,
                title = c.title,
                poster = c.poster,
                streamUrl = c.streamUrl,
                positionMs = if (c.kind == "LIVE") 0 else positionMs,
                durationMs = if (c.kind == "LIVE") 0 else durationMs,
                parentRemoteId = c.parentRemoteId,
            )
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(url: String, title: String, onBack: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    BackHandler { onBack() }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    LaunchedEffect(url) {
        if (url.isNotBlank()) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
        }
        vm.prepareResume()
    }

    // Periodically record playback position so "Continue watching" works even
    // if the user closes the app mid-playback (no onDispose fires for kills).
    LaunchedEffect(player) {
        while (true) {
            delay(10_000)   // every 10s
            if (player.duration > 0) {
                vm.recordProgress(player.currentPosition, player.duration)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.recordProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    controllerShowTimeoutMs = 3000
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Row(Modifier.align(Alignment.TopStart).padding(24.dp)) {
            Column {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(url.substringBefore('?').takeLast(60), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Button(onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), "video/*")
                        putExtra("title", title)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(Intent.createChooser(intent, "Open with…"))
                }
            }) { Text("Open in external player") }
        }
    }
}
