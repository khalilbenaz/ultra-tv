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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Welcome", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        // ---- Onboarding card: shown prominently when no provider is configured ----
        if (providers.isEmpty()) {
            MacOnboardingCard(mac = vm.mac, onGoSettings = onGoSettings)
        }

        // ---- Quick links ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGoLive) { Text("Live TV") }
            Button(onClick = onGoMovies) { Text("Movies") }
            Button(onClick = onGoSeries) { Text("Series") }
            Button(onClick = onGoSettings) { Text("Settings") }
        }

        Spacer(Modifier.height(4.dp))

        // ---- Continue watching ----
        if (continueW.isNotEmpty()) {
            ContentRail(
                title = "Continue watching",
                items = continueW,
                itemKey = { "h-${it.kind}-${it.remoteId}" },
            ) { h ->
                PosterCard(
                    title = h.title,
                    poster = h.poster,
                    subtitle = progressLabel(h.positionMs, h.durationMs),
                ) {
                    vm.playFromHistory(h)
                    onPlay(h.streamUrl, h.title)
                }
            }
        }

        if (recent.isNotEmpty() && recent.size > continueW.size) {
            ContentRail(
                title = "Recently watched",
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
                title = "Movies",
                items = movies,
                itemKey = { it.id },
            ) { m -> PosterCard(title = m.name, poster = m.poster, subtitle = m.year?.toString()) { onOpenMovie(m.id) } }
        }

        if (series.isNotEmpty()) {
            ContentRail(
                title = "Series",
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
                title = "Featured channels",
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
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun MacOnboardingCard(mac: String, onGoSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("👋 First-time setup", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Your device MAC:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "On a web browser, open:",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Text(
            "https://ultratv-config.khalilbenaz.workers.dev",
            color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
        Text(
            "Sign in (admin / nimda), open this MAC, add your provider(s). Then come back here → Settings → \"Sync from cloud\".",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onGoSettings) { Text("Open Settings") }
    }
}

private fun progressLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0) return "Resume"
    val pct = (positionMs * 100 / durationMs).coerceIn(0, 99)
    return "Resume · $pct%"
}
