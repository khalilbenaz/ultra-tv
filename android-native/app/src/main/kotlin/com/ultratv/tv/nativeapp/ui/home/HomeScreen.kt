package com.ultratv.tv.nativeapp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.ui.common.ContentRail
import com.ultratv.tv.nativeapp.ui.common.PosterCard

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onGoLive: () -> Unit,
    onGoMovies: () -> Unit,
    onGoSeries: () -> Unit,
    onGoSettings: () -> Unit,
    onPlay: (url: String, title: String) -> Unit = { _, _ -> },
    onOpenMovie: (Long) -> Unit = {},
    onOpenSeries: (Long) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val providers by vm.providers.collectAsState()
    val continueW by vm.continueWatching.collectAsState()
    val recent by vm.recentlyWatched.collectAsState()
    val movies by vm.featuredMovies.collectAsState()
    val series by vm.featuredSeries.collectAsState()
    val channels by vm.featuredChannels.collectAsState()

    var actionsFor by remember { mutableStateOf<com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity?>(null) }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(S.homeWelcome, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        val activeProvider = providers.firstOrNull { it.active } ?: providers.firstOrNull()
        if (activeProvider != null) {
            Text(
                "★ " + activeProvider.name + "  ·  " + activeProvider.kind,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
        }

        // ---- Onboarding card: shown prominently when no provider is configured ----
        if (providers.isEmpty()) {
            MacOnboardingCard(mac = vm.mac, onGoSettings = onGoSettings)
        }

        // ---- Quick links ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGoLive) { Text(S.live) }
            Button(onClick = onGoMovies) { Text(S.movies) }
            Button(onClick = onGoSeries) { Text(S.series) }
            Button(onClick = onGoSettings) { Text(S.navSettings) }
        }

        Spacer(Modifier.height(4.dp))

        // ---- Continue watching ----
        if (continueW.isNotEmpty()) {
            ContentRail(
                title = S.homeContinueWatching,
                items = continueW,
                itemKey = { "h-${it.kind}-${it.remoteId}" },
            ) { h ->
                PosterCard(
                    title = h.title,
                    poster = h.poster,
                    subtitle = progressLabel(h.positionMs, h.durationMs),
                ) {
                    // Open the actions sheet so the user can choose between
                    // resume and dismiss — direct play would make Dismiss
                    // unreachable on D-pad without a contextual menu key.
                    actionsFor = h
                }
            }
        }

        if (recent.isNotEmpty() && recent.size > continueW.size) {
            ContentRail(
                title = S.homeRecentlyWatched,
                items = recent,
                itemKey = { "r-${it.kind}-${it.remoteId}" },
            ) { h ->
                PosterCard(
                    title = h.title,
                    poster = h.poster,
                    subtitle = h.kind.lowercase().replaceFirstChar { it.uppercase() },
                ) {
                    vm.playFromHistory(h)
                    onPlay(h.streamUrl, h.title)
                }
            }
        }

        // ---- Catalog rails ----
        if (movies.isNotEmpty()) {
            ContentRail(
                title = S.homeFeaturedMovies,
                items = movies,
                itemKey = { it.id },
            ) { m -> PosterCard(title = m.name, poster = m.poster, subtitle = m.year?.toString()) { onOpenMovie(m.id) } }
        }

        if (series.isNotEmpty()) {
            ContentRail(
                title = S.seriesTitle,
                items = series,
                itemKey = { it.id },
            ) { s ->
                PosterCard(
                    title = s.name,
                    poster = s.poster,
                    subtitle = s.year?.toString(),
                    placeholderEmoji = "📺",
                ) { onOpenSeries(s.id) }
            }
        }

        if (channels.isNotEmpty()) {
            ContentRail(
                title = S.homeFeaturedChannels,
                items = channels,
                itemKey = { it.id },
                cardWidth = 150.dp,
            ) { c ->
                PosterCard(
                    title = c.name,
                    poster = c.logo,
                    imageHeight = 100.dp,
                    placeholderEmoji = "📡",
                ) { onPlay(c.streamUrl, c.name) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Action sheet for a Continue watching entry.
    actionsFor?.let { h ->
        ContinueActions(
            title = h.title,
            onResume = {
                vm.playFromHistory(h)
                onPlay(h.streamUrl, h.title)
                actionsFor = null
            },
            onDismiss = {
                vm.dismiss(h)
                actionsFor = null
            },
            onCancel = { actionsFor = null },
        )
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueActions(
    title: String,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 2)
            val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onResume) { Text("▶ " + S.resume) }
                Button(onClick = onDismiss, colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("✖ " + S.dismiss)
                }
                Button(onClick = onCancel, colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) {
                    Text(S.cancel)
                }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun MacOnboardingCard(mac: String, onGoSettings: () -> Unit) {
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("👋 " + S.onboardingFirstTime, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(S.onboardingMacLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            mac,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            S.onboardingTwoPaths,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Text(
            S.onboardingPathManual,
            color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp,
        )
        Text(
            S.onboardingPathCloud,
            color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onGoSettings) { Text(S.onboardingOpenSettings) }
    }
}

private fun progressLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0) return "Resume"
    val pct = (positionMs * 100 / durationMs).coerceIn(0, 99)
    return "Resume · $pct%"
}
