package com.ultratv.tv.nativeapp.ui.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val playback: PlaybackContext,
) : ViewModel() {
    private val _m = MutableStateFlow<MovieEntity?>(null)
    val movie: StateFlow<MovieEntity?> = _m.asStateFlow()
    fun load(id: Long) { viewModelScope.launch { _m.value = catalog.movieById(id) } }
    fun registerPlay(m: MovieEntity) {
        playback.set(PlaybackContext.Item(
            providerId = m.providerId, kind = "MOVIE", remoteId = m.remoteId,
            title = m.name, poster = m.poster, streamUrl = m.streamUrl,
        ))
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onPlay: (url: String, title: String) -> Unit,
    vm: MovieDetailViewModel = hiltViewModel(),
) {
    val m by vm.movie.collectAsState()
    LaunchedEffect(movieId) { vm.load(movieId) }

    val movie = m
    if (movie == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = MaterialTheme.colorScheme.onBackground) }
        return
    }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(
            Modifier
                .width(280.dp)
                .height(420.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (movie.poster != null) AsyncImage(model = movie.poster, contentDescription = movie.name, modifier = Modifier.fillMaxSize())
            else Text("🎬", fontSize = 80.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            Text(movie.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                movie.year?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                movie.rating?.let { Text("★ %.1f".format(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                movie.container?.let { Text(it.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
            }
            movie.plot?.let { Text(it, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.registerPlay(movie); onPlay(movie.streamUrl, movie.name) }) { Text("Play", fontSize = 16.sp) }
                com.ultratv.tv.nativeapp.ui.common.FavoriteButton(kind = "MOVIE", remoteId = movie.remoteId)
            }
        }
    }
}
