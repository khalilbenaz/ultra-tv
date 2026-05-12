package com.ultratv.tv.nativeapp.ui.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    onPlayEpisode: (url: String, title: String) -> Unit,
    vm: SeriesDetailViewModel = hiltViewModel(),
) {
    val s by vm.series.collectAsState()
    val eps by vm.episodes.collectAsState()
    val loading by vm.loading.collectAsState()
    LaunchedEffect(seriesId) { vm.load(seriesId) }

    val series = s
    if (series == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = MaterialTheme.colorScheme.onBackground) }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(
                Modifier.width(220.dp).height(320.dp).clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (series.poster != null) AsyncImage(model = series.poster, contentDescription = series.name, modifier = Modifier.fillMaxSize())
                else Text("📺", fontSize = 80.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(series.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    series.year?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                    series.rating?.let { Text("★ %.1f".format(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                    if (loading) Text("(loading episodes…)", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
                series.plot?.let { Text(it, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, maxLines = 6) }
                com.ultratv.tv.nativeapp.ui.common.FavoriteButton(kind = "SERIES", remoteId = series.remoteId)
            }
        }

        Text("Episodes", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        if (eps.isEmpty() && !loading) {
            Text("No episodes available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(eps, key = { it.id }) { ep ->
                    Card(
                        onClick = {
                            vm.registerPlay(series.name, series.remoteId, series.providerId, ep)
                            val tag = "S${ep.season.toString().padStart(2, '0')}E${ep.episode.toString().padStart(2, '0')}"
                            onPlayEpisode(ep.streamUrl, "${series.name} – $tag · ${ep.title}")
                        },
                        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("S${ep.season.toString().padStart(2, '0')}E${ep.episode.toString().padStart(2, '0')}",
                                color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(ep.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
