package com.ultratv.tv.nativeapp.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackContext,
    private val history: HistoryRepository,
    private val zapQueue: com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue,
    private val provider: com.ultratv.tv.nativeapp.data.repo.ProviderRepository,
    private val epgDao: com.ultratv.tv.nativeapp.data.db.EpgDao,
    private val recordings: com.ultratv.tv.nativeapp.data.recording.RecordingRepository,
    private val prefs: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore,
    private val channelDao: com.ultratv.tv.nativeapp.data.db.ChannelDao,
) : ViewModel() {

    /** Snapshot of the playback knobs the screen reads at construction time. */
    suspend fun playbackPrefs(): com.ultratv.tv.nativeapp.data.prefs.UserPrefs =
        prefs.flow.first()

    /**
     * Resolves a catchup URL for [prog] on the channel currently set in the
     * PlaybackContext and starts playing it. Used by EPG rows in the past on
     * channels that report catchup support.
     */
    fun playCatchup(prog: com.ultratv.tv.nativeapp.data.db.EpgEntity, onReady: (url: String, title: String) -> Unit) {
        viewModelScope.launch {
            val cur = current.value ?: return@launch
            val ch = channelDao.byRemoteId(cur.providerId, cur.remoteId) ?: return@launch
            val url = com.ultratv.tv.nativeapp.data.repo.Catchup.buildUrl(ch, prog) ?: return@launch
            onReady(url, "${ch.name} — ${prog.title}")
        }
    }

    val current: StateFlow<PlaybackContext.Item?> = playback.current

    /** Queues a Live channel recording for `maxMinutes`. HLS m3u8 → segment
     *  recorder; non-HLS live → single HTTP body grab (won't capture more than
     *  what the server already buffered). */
    fun recordLive(maxMinutes: Int = 120, toastTemplate: String = "Recording queued (max %1\$d min)") {
        val c = playback.current.value ?: return
        if (c.kind != "LIVE") return
        viewModelScope.launch {
            recordings.enqueue(c.providerId, "LIVE", c.remoteId, c.title, c.streamUrl, maxMinutes)
            com.ultratv.tv.nativeapp.ui.common.Toaster.ok(toastTemplate.format(maxMinutes))
        }
    }

    /** Resolves the next/previous channel in the active zap queue (Live only)
     *  and updates [PlaybackContext] so the player swaps stream URL. Returns
     *  the new URL or null when there's nothing queued. */
    suspend fun zap(forward: Boolean): String? {
        val target = (if (forward) zapQueue.next() else zapQueue.previous()) ?: return null
        val resolved = provider.resolvePlayUrl(target.id, target.streamUrl)
        playback.set(PlaybackContext.Item(
            providerId = target.providerId, kind = "LIVE", remoteId = target.remoteId,
            title = target.name, poster = target.logo, streamUrl = resolved,
        ))
        return resolved
    }

    /** Channel list + now/next for each channel in the active zap queue.
     *  Used by the OK-triggered drawer overlay. */
    data class DrawerEntry(
        val channel: com.ultratv.tv.nativeapp.data.db.ChannelEntity,
        val now: com.ultratv.tv.nativeapp.data.db.EpgEntity?,
        val next: com.ultratv.tv.nativeapp.data.db.EpgEntity?,
        val isCurrent: Boolean,
    )

    val queue: StateFlow<List<DrawerEntry>> = zapQueue.state.map { s ->
        if (s == null) emptyList()
        else {
            val ids = s.channels.map { it.id }
            val now = System.currentTimeMillis()
            // SQLite IN-list cap: 999 host params. Chunk to be safe with big playlists.
            val rows = ids.chunked(500).flatMap { chunk ->
                epgDao.rangeForChannels(chunk, now - 30 * 60_000, now + 4 * 60 * 60_000)
            }
            val byCh = rows.groupBy { it.channelId }
            s.channels.mapIndexed { idx, c ->
                val list = byCh[c.id].orEmpty()
                DrawerEntry(
                    channel = c,
                    now = list.firstOrNull { it.startMs <= now && it.endMs > now },
                    next = list.firstOrNull { it.startMs > now },
                    isCurrent = idx == s.index,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Zap directly to a specific channel (drawer pick). Same flow as zap(). */
    suspend fun zapTo(channel: com.ultratv.tv.nativeapp.data.db.ChannelEntity): String? {
        val s = zapQueue.state.value ?: return null
        val idx = s.channels.indexOfFirst { it.id == channel.id }
        if (idx < 0) return null
        // Reuse setter to update index.
        zapQueue.set(s.channels, channel)
        val resolved = provider.resolvePlayUrl(channel.id, channel.streamUrl)
        playback.set(PlaybackContext.Item(
            providerId = channel.providerId, kind = "LIVE", remoteId = channel.remoteId,
            title = channel.name, poster = channel.logo, streamUrl = resolved,
        ))
        return resolved
    }

    /**
     * Reads the last persisted position for the current item and returns the
     * offset (ms) the player should seek to once the source is ready. Live
     * channels never resume — they snap to the live edge instead, so this
     * returns 0 for them. Suspends so the caller can await the DB read and
     * seek with the actual value (the old fire-and-forget version raced the
     * seekTo() and never moved the playhead).
     */
    suspend fun prepareResume(): Long {
        val c = playback.current.value ?: return 0L
        if (c.kind == "LIVE") return 0L
        return history.resumePositionMs(c.providerId, c.kind, c.remoteId)
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BackHandler { onBack() }
    val playbackItem by vm.current.collectAsState()
    val isLive = playbackItem?.kind == "LIVE"
    var currentUrl by remember { mutableStateOf(url) }
    var currentTitle by remember { mutableStateOf(title) }
    var tracksOpen by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var displayMenu by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableStateOf(AspectMode.Fit) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    // Load prefs off the main thread. runBlocking here blocked the main thread
    // on a DataStore read during composition (ANR risk). produceState starts
    // null and emits the real values once the first read completes; we show a
    // black placeholder until then so the player is built exactly once with the
    // resolved prefs (keeping it stable for the screen's lifetime — changing
    // buffer / frame-rate / decoder still only takes effect on next launch).
    val loadedPrefs by androidx.compose.runtime.produceState<com.ultratv.tv.nativeapp.data.prefs.UserPrefs?>(
        initialValue = null,
    ) {
        value = vm.playbackPrefs()
    }
    val playbackPrefs = loadedPrefs ?: run {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val player = remember {
        // bufferSeconds = how much we want to *hold* in memory; the "start
        // playback" threshold should always be very small so live TV starts
        // immediately (the user's complaint: "rien ne se lit" — the player
        // was waiting on 5 s of buffer before going READY).
        val bufMs = (playbackPrefs.bufferSeconds * 1000).coerceAtLeast(5_000)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs                       = */ 5_000,
                /* maxBufferMs                       = */ bufMs,
                /* bufferForPlaybackMs               = */ 500,
                /* bufferForPlaybackAfterRebufferMs  = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Pluggable DataSource: HTTP/HTTPS go through the default OkHttp
        // pipeline, rtmp:// + rtmps:// URLs go through RtmpDataSource. The
        // earlier v1.0.25 attempt only intercepted open() and let the rest
        // of the lifecycle (read / close / getUri) dangle on a stale inner
        // — that crashed HTTP playback. The RoutingDataSource below owns
        // the backing instance for the whole open-read-close cycle.
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        val defaultFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val rtmpFactory = androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
        val routingFactory = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var inner: androidx.media3.datasource.DataSource? = null
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    val scheme = spec.uri.scheme?.lowercase()
                    val backing = if (scheme == "rtmp" || scheme == "rtmps")
                        rtmpFactory.createDataSource() else defaultFactory.createDataSource()
                    listeners.forEach { backing.addTransferListener(it) }
                    inner = backing
                    return backing.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    inner?.read(buffer, offset, length) ?: -1
                override fun getUri(): android.net.Uri? = inner?.uri
                override fun close() {
                    runCatching { inner?.close() }
                    inner = null
                }
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    listeners += transferListener
                    inner?.addTransferListener(transferListener)
                }
                override fun getResponseHeaders(): Map<String, List<String>> =
                    inner?.responseHeaders ?: emptyMap()
            }
        }
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(routingFactory)
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            // Hardware renderers first by default; flipping the pref pushes the
            // software decoder ahead so finicky streams (HEVC main10 on cheap
            // boxes, malformed HLS variant tags) fall back gracefully.
            setExtensionRendererMode(
                if (playbackPrefs.preferSoftwareDecoder)
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
        }
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            playWhenReady = true
            // Surface playback failures to the dashboard so we can see WHY a
            // stream silently never starts (codec, 403, DNS, etc).
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    com.ultratv.tv.nativeapp.RemoteLog.error(
                        "player",
                        "code=${error.errorCodeName} ${error.message ?: ""}",
                    )
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (!playbackPrefs.autoFrameRate) return
                    val act = (context as? android.app.Activity) ?: return
                    val fmt = currentTracks.groups
                        .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                        ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                    val fps = fmt?.frameRate ?: return
                    if (fps <= 0f) return
                    // Pick the display mode whose refresh is the closest integer
                    // multiple of fps (so 24 fps → 24/48/72 Hz, 50 fps → 50/100 Hz).
                    val display = act.windowManager.defaultDisplay ?: return
                    val target = display.supportedModes.minByOrNull { m ->
                        val multiple = (m.refreshRate / fps).coerceAtLeast(1f)
                        kotlin.math.abs(m.refreshRate - fps * kotlin.math.round(multiple))
                    } ?: return
                    val lp = act.window.attributes
                    if (lp.preferredDisplayModeId != target.modeId) {
                        lp.preferredDisplayModeId = target.modeId
                        act.window.attributes = lp
                        com.ultratv.tv.nativeapp.RemoteLog.debug("player", "switched display to ${target.refreshRate}Hz for ${fps}fps")
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    val name = when (state) {
                        androidx.media3.common.Player.STATE_IDLE -> "idle"
                        androidx.media3.common.Player.STATE_BUFFERING -> "buffering"
                        androidx.media3.common.Player.STATE_READY -> "ready"
                        androidx.media3.common.Player.STATE_ENDED -> "ended"
                        else -> "?"
                    }
                    com.ultratv.tv.nativeapp.RemoteLog.debug("player", "state=$name")
                }
            })
        }
    }

    // Stream-stats overlay: tracks codec/resolution/bitrate while playing.
    var statsOpen by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(StreamStats()) }
    LaunchedEffect(statsOpen) {
        if (!statsOpen) return@LaunchedEffect
        while (true) {
            stats = StreamStats.read(player)
            delay(1_000)
        }
    }

    // Sleep-timer: when > 0, stops playback at the given timestamp. The
    // LaunchedEffect below polls every 5s and pauses + closes the screen
    // when the deadline is reached.
    var sleepDeadlineMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepDeadlineMs) {
        if (sleepDeadlineMs <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < sleepDeadlineMs) delay(5_000)
        player.pause()
        com.ultratv.tv.nativeapp.ui.common.Toaster.show(S.sleepReached)
        onBack()
    }
    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotBlank()) {
            player.setMediaItem(MediaItem.fromUri(currentUrl))
            player.prepare()
            // Seek to last persisted position if VOD/episode has one. Awaits the
            // DB read so the seek uses the real value (was racing before).
            val resume = vm.prepareResume()
            // Skip stale "near-end" positions so credits don't auto-replay.
            if (resume > 5_000) {
                player.seekTo(resume)
            }
            player.play()
        }
    }
    LaunchedEffect(playbackSpeed) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed)
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

    // D-pad UP/DOWN = channel zap on Live. The PlayerView eats LEFT/RIGHT for
    // seek when useController = true, which is what we want for VOD.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (!isLive || ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> {
                        scope.launch {
                            vm.zap(forward = false)?.let {
                                currentUrl = it
                                currentTitle = vm.current.value?.title ?: currentTitle
                            }
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        scope.launch {
                            vm.zap(forward = true)?.let {
                                currentUrl = it
                                currentTitle = vm.current.value?.title ?: currentTitle
                            }
                        }
                        true
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        drawerOpen = !drawerOpen
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowFastForwardButton(!isLive)
                    setShowRewindButton(!isLive)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    controllerShowTimeoutMs = if (isLive) 1500 else 3000
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { v -> v.resizeMode = aspectMode.resizeMode },
            modifier = Modifier.fillMaxSize(),
        )

        // Vertical-drag gestures for touch users: right strip = volume,
        // left strip = brightness. The strips are narrow (120 dp) so the
        // central PlayerView still receives tap-to-toggle-controls. On
        // TV the D-pad never produces drag events, so this is inert there.
        val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        val activity = remember(context) { context as? Activity }
        val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        var brightness by remember {
            mutableFloatStateOf(
                activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
            )
        }
        var gestureLabel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(gestureLabel) {
            if (gestureLabel != null) { delay(900); gestureLabel = null }
        }
        // Right strip: volume
        var volAccum by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.align(Alignment.CenterEnd).width(120.dp).fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { volAccum = 0f },
                    ) { _, dragAmount ->
                        volAccum += -dragAmount
                        val step = (volAccum / 60f).toInt()
                        if (step != 0) {
                            volume = (volume + step).coerceIn(0, maxVol)
                            volAccum -= step * 60f
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                            gestureLabel = "🔊 ${(volume * 100 / maxVol)}%"
                        }
                    }
                },
        )
        // Left strip: brightness
        var brAccum by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.align(Alignment.CenterStart).width(120.dp).fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { brAccum = 0f },
                    ) { _, dragAmount ->
                        brAccum += -dragAmount
                        val deltaPct = (brAccum / 8f).toInt()
                        if (deltaPct != 0) {
                            brightness = (brightness + deltaPct / 100f).coerceIn(0.05f, 1f)
                            brAccum -= deltaPct * 8f
                            activity?.window?.let { w ->
                                val attrs = w.attributes as WindowManager.LayoutParams
                                attrs.screenBrightness = brightness
                                w.attributes = attrs
                            }
                            gestureLabel = "☀ ${(brightness * 100).toInt()}%"
                        }
                    }
                },
        )
        // Transient indicator
        gestureLabel?.let { lbl ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(lbl, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(Modifier.align(Alignment.TopStart).padding(24.dp)) {
            Column {
                Text(currentTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (isLive) {
                    Text(
                        "▲ ▼ to zap channels",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                    )
                }
                Text(currentUrl.substringBefore('?').takeLast(60), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        FlowRow(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .widthIn(max = 760.dp)
                .padding(24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4,
        ) {
            // Sleep timer menu — anchored bottom-right next to the external-player button.
            var sleepMenu by remember { mutableStateOf(false) }
            Button(onClick = { sleepMenu = !sleepMenu }) {
                Text(
                    if (sleepDeadlineMs > 0L) {
                        val mins = ((sleepDeadlineMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
                        "💤 ${mins}min"
                    } else "💤 " + S.sleepLabel
                )
            }
            if (sleepMenu) {
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    SleepOption(S.sleepMin15) { sleepDeadlineMs = System.currentTimeMillis() + 15 * 60_000; sleepMenu = false }
                    SleepOption(S.sleepMin30) { sleepDeadlineMs = System.currentTimeMillis() + 30 * 60_000; sleepMenu = false }
                    SleepOption(S.sleep1h) { sleepDeadlineMs = System.currentTimeMillis() + 60 * 60_000; sleepMenu = false }
                    SleepOption(S.sleep2h) { sleepDeadlineMs = System.currentTimeMillis() + 120 * 60_000; sleepMenu = false }
                    if (sleepDeadlineMs > 0L) {
                        SleepOption(S.sleepCancel) { sleepDeadlineMs = 0L; sleepMenu = false }
                    }
                }
            }
            if (!isLive) {
                Button(onClick = { tracksOpen = true }) { Text("🎚 ${S.playerTracks}") }
            }
            if (isLive) {
                Button(onClick = { vm.recordLive(120, S.recordingQueuedTemplate) }) { Text("⏺ ${S.playerRecord} (2h)") }
            }
            Button(onClick = { displayMenu = !displayMenu }) { Text("📐 ${S.playerDisplay}") }
            // Cast picker — only shown if the Cast SDK initialised successfully
            // (Play Services present). We use the framework's MediaRouteButton
            // wrapped in an AndroidView so the system Cast UI takes over.
            val castInited = remember {
                runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(context) }.isSuccess
            }
            if (castInited) {
                // MediaRouteButton wired by CastButtonFactory — opens the
                // framework's chooser dialog when the user clicks it.
                AndroidView(
                    factory = { ctx ->
                        androidx.mediarouter.app.MediaRouteButton(ctx).also { btn ->
                            runCatching {
                                com.google.android.gms.cast.framework.CastButtonFactory
                                    .setUpMediaRouteButton(ctx.applicationContext, btn)
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Button(onClick = { statsOpen = !statsOpen }) {
                Text("📊 " + S.playerStats)
            }
            Button(onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), "video/*")
                        putExtra("title", title)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(Intent.createChooser(intent, S.recordingsOpenWith))
                }
            }) { Text(S.playerExternal) }
        }
        if (displayMenu) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 70.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                Text(S.playerAspect, color = Color(0xFF66B3FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                AspectMode.entries.forEach { mode ->
                    Button(
                        onClick = { aspectMode = mode; displayMenu = false },
                        colors = if (mode == aspectMode) androidx.tv.material3.ButtonDefaults.colors()
                        else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                    ) { Text(mode.label, fontSize = 12.sp) }
                }
                if (!isLive) {
                    Text(S.playerSpeed, color = Color(0xFF66B3FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                        Button(
                            onClick = { playbackSpeed = sp; displayMenu = false },
                            colors = if (sp == playbackSpeed) androidx.tv.material3.ButtonDefaults.colors()
                            else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                        ) { Text("${sp}x", fontSize = 12.sp) }
                    }
                }
            }
        }
        if (drawerOpen && isLive) {
            LiveDrawer(
                vm = vm,
                onPick = { ch ->
                    scope.launch {
                        vm.zapTo(ch)?.let {
                            currentUrl = it
                            currentTitle = vm.current.value?.title ?: currentTitle
                        }
                        drawerOpen = false
                    }
                },
                onDismiss = { drawerOpen = false },
            )
        }
        if (tracksOpen) {
            TracksDialog(player = player, onDismiss = { tracksOpen = false })
        }
        if (statsOpen) {
            val T = com.ultratv.tv.nativeapp.ui.theme.UltraTokens
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 110.dp, end = 60.dp)
                    .width(280.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color(0xB3000000))
                    .border(1.dp, T.Line2, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "STREAM STATS",
                        color = T.Fg3,
                        fontSize = 10.sp,
                        letterSpacing = 2.3.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .width(6.dp)
                                .height(6.dp)
                                .background(T.Ok, androidx.compose.foundation.shape.CircleShape),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                        Text("sain", color = T.Ok, fontSize = 11.sp)
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                StatRow(S.statResolution, stats.resolution)
                StatRow(S.statVideoCodec, stats.videoCodec)
                StatRow(S.statFrameRate, stats.frameRate)
                StatRow(S.statVideoBitrate, stats.videoBitrate)
                StatRow(S.statAudioCodec, stats.audioCodec)
                StatRow(S.statAudioChannels, stats.audioChannels)
                StatRow(S.statBuffered, stats.bufferedAhead)
                StatRow(S.statDroppedFrames, stats.droppedFrames)
            }
        }
    }
}

/** Subtitle + audio track picker for VOD playback. Reads the current Tracks
 *  object from the player and writes back a TrackSelectionOverride when the
 *  user picks a track. */
@OptIn(androidx.media3.common.util.UnstableApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun TracksDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    val tracks = player.currentTracks
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(androidx.tv.material3.MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            androidx.tv.material3.Text(
                "🎚 " + S.playerTracks,
                color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            // Audio tracks
            val audioGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            androidx.tv.material3.Text(S.playerAudioTemplate.format(audioGroups.sumOf { it.length }), color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            audioGroups.forEach { group ->
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val label = listOfNotNull(
                        fmt.label,
                        fmt.language,
                        fmt.sampleMimeType?.removePrefix("audio/"),
                        fmt.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                    ).joinToString(" · ").ifBlank { "Track ${i + 1}" }
                    androidx.tv.material3.Button(
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                            onDismiss()
                        },
                        colors = if (group.isTrackSelected(i)) androidx.tv.material3.ButtonDefaults.colors()
                        else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                    ) { androidx.tv.material3.Text(label, fontSize = 13.sp) }
                }
            }
            // Subtitle tracks
            val subGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            androidx.tv.material3.Text(S.playerSubtitlesTemplate.format(subGroups.sumOf { it.length }), color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            androidx.tv.material3.Button(
                onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                        .build()
                    onDismiss()
                },
                colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
            ) { androidx.tv.material3.Text(S.playerOff, fontSize = 13.sp) }
            subGroups.forEach { group ->
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val label = listOfNotNull(fmt.label, fmt.language).joinToString(" · ").ifBlank { "Subtitle ${i + 1}" }
                    androidx.tv.material3.Button(
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                            onDismiss()
                        },
                        colors = if (group.isTrackSelected(i)) androidx.tv.material3.ButtonDefaults.colors()
                        else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                    ) { androidx.tv.material3.Text(label, fontSize = 13.sp) }
                }
            }
            androidx.tv.material3.Button(
                onClick = onDismiss,
                colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.background),
            ) { androidx.tv.material3.Text(S.close) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.width(90.dp))
        Text(value, color = Color.White, fontSize = 11.sp)
    }
}

/** PlayerView.resizeMode mapping. RESIZE_MODE_* values are ints exposed by
 *  androidx.media3.ui.AspectRatioFrameLayout. */
private enum class AspectMode(val label: String, val resizeMode: Int) {
    Fit("Fit", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill("Fill", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom("Zoom", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FixedWidth("16:9", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    FixedHeight("4:3", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
}

private data class StreamStats(
    val resolution: String = "—",
    val videoCodec: String = "—",
    val frameRate: String = "—",
    val videoBitrate: String = "—",
    val audioCodec: String = "—",
    val audioChannels: String = "—",
    val bufferedAhead: String = "—",
    val droppedFrames: String = "—",
) {
    companion object {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun read(player: ExoPlayer): StreamStats {
            val v = player.videoFormat
            val a = player.audioFormat
            val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            return StreamStats(
                resolution = v?.let { "${it.width}×${it.height}" } ?: "—",
                videoCodec = v?.sampleMimeType?.removePrefix("video/") ?: "—",
                frameRate = v?.frameRate?.takeIf { it > 0 }?.let { "%.1f fps".format(it) } ?: "—",
                videoBitrate = v?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "—",
                audioCodec = a?.sampleMimeType?.removePrefix("audio/") ?: "—",
                audioChannels = a?.channelCount?.toString() ?: "—",
                bufferedAhead = "${bufferedMs / 1000}s",
                droppedFrames = "n/a",
            )
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun SleepOption(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(vertical = 2.dp),
    ) { Text(label, fontSize = 13.sp) }
}
