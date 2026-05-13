package com.ultratv.tv.nativeapp.ui.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.paging.compose.collectAsLazyPagingItems
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
fun SeriesScreen(onOpen: (Long) -> Unit, vm: SeriesListViewModel = hiltViewModel()) {
    val sel by vm.selectedCategory.collectAsState()
    val cats by vm.categories.collectAsState()
    val rails by vm.rails.collectAsState()
    val featured by vm.featured.collectAsState()
    val flatItems by vm.items.collectAsState()

    val railsMode = sel == null
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(S.seriesTitle, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        if (railsMode && featured != null) {
            HeroBanner(
                title = featured!!.name,
                subtitle = listOfNotNull(
                    featured!!.year?.toString(),
                    featured!!.rating?.let { "★ %.1f".format(it) },
                ).joinToString(" · "),
                image = featured!!.poster,
                primaryLabel = S.open,
                onPrimary = { onOpen(featured!!.id) },
            )
        }

        CategoryChips(categories = cats, selected = sel, onSelect = vm::selectCategory)

        if (railsMode) {
            if (rails.isEmpty()) {
                Text(S.noSeries, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rails.forEach { rail ->
                ContentRail(
                    title = rail.category?.name ?: S.railOther,
                    items = rail.items,
                    itemKey = { it.id },
                ) { s ->
                    PosterCard(
                        title = s.name,
                        poster = s.poster,
                        subtitle = s.year?.toString(),
                        placeholderEmoji = "📺",
                    ) { onOpen(s.id) }
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            val paged = vm.pagedSeries.collectAsLazyPagingItems()
            Text("${paged.itemCount} titles loaded${if (paged.loadState.append is androidx.paging.LoadState.Loading) "…" else ""}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.height(720.dp),
            ) {
                items(
                    count = paged.itemCount,
                    key = { idx -> paged.peek(idx)?.id ?: idx },
                ) { idx ->
                    val s = paged[idx] ?: return@items
                    PosterCard(
                        title = s.name,
                        poster = s.poster,
                        subtitle = s.year?.toString(),
                        placeholderEmoji = "📺",
                    ) { onOpen(s.id) }
                }
            }
        }
    }
}
