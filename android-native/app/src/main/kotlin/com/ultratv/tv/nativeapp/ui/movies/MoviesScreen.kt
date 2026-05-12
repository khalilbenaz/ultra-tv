package com.ultratv.tv.nativeapp.ui.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.ui.common.CategoryChips
import com.ultratv.tv.nativeapp.ui.common.ContentRail
import com.ultratv.tv.nativeapp.ui.common.HeroBanner
import com.ultratv.tv.nativeapp.ui.common.PosterCard

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun MoviesScreen(onOpen: (Long) -> Unit, vm: MoviesViewModel = hiltViewModel()) {
    val sel by vm.selectedCategory.collectAsState()
    val cats by vm.categories.collectAsState()
    val rails by vm.rails.collectAsState()
    val featured by vm.featured.collectAsState()
    val flatMovies by vm.movies.collectAsState()

    // When no category is filtered, show the Netflix-style rails view.
    // When a single category is picked from chips, fall back to a flat grid
    // (faster scanning when the user already narrowed scope).
    val railsMode = sel == null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Movies", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        if (railsMode && featured != null) {
            HeroBanner(
                title = featured!!.name,
                subtitle = listOfNotNull(
                    featured!!.year?.toString(),
                    featured!!.rating?.let { "★ %.1f".format(it) },
                    featured!!.container?.uppercase(),
                ).joinToString(" · "),
                image = featured!!.poster,
                primaryLabel = "Open",
                onPrimary = { onOpen(featured!!.id) },
            )
        }

        CategoryChips(categories = cats, selected = sel, onSelect = vm::selectCategory)

        if (railsMode) {
            if (rails.isEmpty()) {
                Text("No movies — add a provider in Settings and re-sync.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rails.forEach { rail ->
                ContentRail(
                    title = rail.category?.name ?: "Other",
                    items = rail.items,
                    itemKey = { it.id },
                ) { m -> PosterCard(title = m.name, poster = m.poster, subtitle = m.year?.toString()) { onOpen(m.id) } }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Text("${flatMovies.size} titles", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (flatMovies.isEmpty()) {
                Text("Nothing in this category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(720.dp),       // bounded so we can be inside verticalScroll
                ) {
                    items(flatMovies, key = { it.id }) { m ->
                        PosterCard(title = m.name, poster = m.poster, subtitle = m.year?.toString()) { onOpen(m.id) }
                    }
                }
            }
        }
    }
}
