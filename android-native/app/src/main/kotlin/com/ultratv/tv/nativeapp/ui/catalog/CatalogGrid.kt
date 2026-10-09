package com.ultratv.tv.nativeapp.ui.catalog

import com.ultratv.tv.nativeapp.i18n.traktWatched
import com.ultratv.tv.nativeapp.data.repo.atMostEvery
import com.ultratv.tv.nativeapp.data.repo.snapshotWhile
import com.ultratv.tv.nativeapp.ui.common.RowBleed
import com.ultratv.tv.nativeapp.ui.common.rowBleedStart
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.CategoryCount
import com.ultratv.tv.nativeapp.data.db.MovieDao
import com.ultratv.tv.nativeapp.data.db.SeriesDao
import com.ultratv.tv.nativeapp.data.prefs.HiddenCategoriesStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.i18n.LocalDs
import com.ultratv.tv.nativeapp.ui.common.RequestInitialFocus
import com.ultratv.tv.nativeapp.ui.common.design
import com.ultratv.tv.nativeapp.ui.common.prettyCategoryName
import com.ultratv.tv.nativeapp.ui.design.FocusSurface
import com.ultratv.tv.nativeapp.ui.design.Manrope
import com.ultratv.tv.nativeapp.ui.design.PosterImage
import com.ultratv.tv.nativeapp.ui.design.Sora
import com.ultratv.tv.nativeapp.ui.design.Ux
import com.ultratv.tv.nativeapp.ui.design.spx
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class CatalogKind { MOVIES, SERIES }

/** Carte de la grille : seulement des champs RÉELS de la source. */
data class PosterItem(val id: Long, val title: String, val poster: String?, val year: Int?, val rating: Double?, val lang: String = "", val watched: Boolean = false)

data class CategoryChip(val remoteId: String, val name: String)

private const val ROW_SIZE = 20

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogGridViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val hiddenStore: HiddenCategoriesStore,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val trakt: com.ultratv.tv.nativeapp.data.trakt.TraktLibraryRepository,
    syncBus: com.ultratv.tv.nativeapp.data.repo.SyncStatusBus,
) : ViewModel() {
    private val kind = MutableStateFlow(CatalogKind.MOVIES)
    fun bind(k: CatalogKind) { kind.value = k }

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected
    fun select(remoteId: String?) { _selected.value = remoteId }

    /** Synchro en cours : compteurs et rangées lus une seule fois au lieu d'être relancés à chaque lot inséré. */
    private val syncing = syncBus.status.map { it != null }.distinctUntilChanged()

    private val pid = providerRepo.observeProviders().map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }.distinctUntilChanged()

    /** Puces : « Tous » + les catégories NON vides (compteurs SQL), hors catégories masquées. */
    /** null = pas encore chargées (rien n'est affiché de faux pendant ce temps : ni « aucun film », ni grille à plat). */
    val chips: StateFlow<List<CategoryChip>?> = combine(pid, kind, hiddenStore.hidden) { p, k, h -> Triple(p, k, h) }
        .flatMapLatest { (p, k, hidden) ->
            if (p == null) flowOf(emptyList())
            else {
                val kindName = if (k == CatalogKind.MOVIES) "MOVIE" else "SERIES"
                val counts: Flow<List<CategoryCount>> = if (k == CatalogKind.MOVIES) movieDao.observeCategoryCounts(p).atMostEvery(1_000).snapshotWhile(syncing) else seriesDao.observeCategoryCounts(p).atMostEvery(1_000).snapshotWhile(syncing)
                combine(catalog.categories(p, kindName), counts) { cats: List<CategoryEntity>, cnt ->
                    val nonEmpty = cnt.filter { it.n > 0 }.mapNotNull { it.categoryId }.toSet()
                    cats.filter { it.remoteId in nonEmpty && hiddenStore.keyFor(kindName, p, it.remoteId) !in hidden }
                        .map { CategoryChip(it.remoteId, prettyCategoryName(it.name)) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _langView = kotlinx.coroutines.flow.MutableStateFlow(com.ultratv.tv.nativeapp.data.repo.LangView.ALL)
    val langView: StateFlow<com.ultratv.tv.nativeapp.data.repo.LangView> = _langView
    fun toggleLang(code: String) { _langView.value = _langView.value.toggle(code) }
    fun clearLangView() { _langView.value = com.ultratv.tv.nativeapp.data.repo.LangView.ALL }
    val langCounts: StateFlow<List<com.ultratv.tv.nativeapp.data.db.LangCount>> = combine(pid, kind) { p, k -> p to k }
        .flatMapLatest { (p, k) -> if (p == null) flowOf(emptyList()) else if (k == CatalogKind.MOVIES) movieDao.observeLangCounts(p).atMostEvery(1_000).snapshotWhile(syncing) else seriesDao.observeLangCounts(p).atMostEvery(1_000).snapshotWhile(syncing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Rangée d'une catégorie pour la vue « Tous » (les 20 plus récents), MISE EN CACHE par catégorie : une rangée
     * qui sort puis revient à l'écran en défilant retrouve sa dernière valeur tout de suite (avant : nouvelle requête,
     * squelette puis clignotement). Pendant une synchro, au plus une relecture par seconde.
     */
    private val rowCache = java.util.concurrent.ConcurrentHashMap<String, StateFlow<List<PosterItem>?>>()
    fun rowItems(cat: String): StateFlow<List<PosterItem>?> = rowCache.getOrPut(cat) {
        combine(pid, kind) { p, k -> p to k }.flatMapLatest { (p, k) ->
            if (p == null) flowOf(emptyList())
            else if (k == CatalogKind.MOVIES) movieDao.observeRow(p, cat, ROW_SIZE).atMostEvery(1_000).snapshotWhile(syncing).map { l -> val lib = trakt.library.value; l.map { PosterItem(it.id, it.title, it.poster, it.year, it.rating, watched = lib.isMovieWatched(it.title, it.year)) } }
            else seriesDao.observeRow(p, cat, ROW_SIZE).atMostEvery(1_000).snapshotWhile(syncing).map { l -> l.map { PosterItem(it.id, it.title, it.poster, it.year, it.rating) } }
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), null)
    }

    val items: Flow<PagingData<PosterItem>> = combine(pid, kind, _selected, _langView) { p, k, c, lv -> listOf<Any?>(p, k, c, lv) }
        .distinctUntilChanged()
        .flatMapLatest { arr ->
            @Suppress("UNCHECKED_CAST") val p = arr[0] as Long?; val k = arr[1] as CatalogKind; val cat = arr[2] as String?; val lv = arr[3] as com.ultratv.tv.nativeapp.data.repo.LangView
            if (p == null) flowOf(PagingData.empty())
            else if (k == CatalogKind.MOVIES) Pager(PagingConfig(pageSize = 42, prefetchDistance = 28, initialLoadSize = 84, enablePlaceholders = false)) {
                if (cat == null) movieDao.pagedAll(p, lv.useLang, lv.langs) else movieDao.pagedForCategory(p, cat, lv.useLang, lv.langs)
            }.flow.map { pd -> val lib = trakt.library.value; pd.map { PosterItem(it.id, it.title, it.poster, it.year, it.rating, it.lang, lib.isMovieWatched(it.title, it.year)) } }
            else Pager(PagingConfig(pageSize = 42, prefetchDistance = 28, initialLoadSize = 84, enablePlaceholders = false)) {
                if (cat == null) seriesDao.pagedAll(p, lv.useLang, lv.langs) else seriesDao.pagedForCategory(p, cat, lv.useLang, lv.langs)
            }.flow.map { pd -> pd.map { PosterItem(it.id, it.title, it.poster, it.year, it.rating, it.lang) } }
        }.cachedIn(viewModelScope)
}

/**
 * Films / Séries (maquette Films.dc.html) : titre, puces de catégories, grille d'affiches 2:3 sur
 * 7 colonnes. Paginée : seules les affiches visibles existent en mémoire (180 000 films sur la source réelle).
 */
@Composable
fun CatalogGridScreen(kind: CatalogKind, onOpen: (Long) -> Unit) {
    val vm: CatalogGridViewModel = hiltViewModel(key = kind.name)
    LaunchedEffect(kind) { vm.bind(kind) }
    val D = LocalDs.current
    val chipsOrNull by vm.chips.collectAsState()
    val chips = chipsOrNull.orEmpty()
    val selected by vm.selected.collectAsState()
    val items = vm.items.collectAsLazyPagingItems()
    val langView by vm.langView.collectAsState()
    val langCounts by vm.langCounts.collectAsState()
    var langPanel by remember { mutableStateOf(false) }
    val title = if (kind == CatalogKind.MOVIES) D.moviesTitle else D.seriesTitle

    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    val widthDp = com.ultratv.tv.nativeapp.ui.common.LocalUiWidthDp.current
    com.ultratv.tv.nativeapp.ui.mobile.TouchRefresh {
    Column(
        Modifier.fillMaxSize().then(if (touch) Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp) else Modifier.padding(start = 72.design, end = 96.design, top = 54.design)),
        verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 32.design),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = if (touch) 28.sp else 48.spx, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                com.ultratv.tv.nativeapp.ui.mobile.SearchAction()
            }
        }
        LazyRow(Modifier.rowBleedStart(RowBleed), horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 14.design), contentPadding = PaddingValues(start = RowBleed)) {
            item(key = "all") { Chip(D.allChip, selected == null) { vm.select(null) } }
            items(chips, key = { it.remoteId }, contentType = { "chip" }) { c -> Chip(c.name, selected == c.remoteId) { vm.select(c.remoteId) } }
        }
        // Chargement : squelette de rangées à taille fixe (la mise en page ne saute plus quand les données arrivent).
        if (chipsOrNull == null) { RowsSkeleton(touch); return@Column }
        // « Tous » : une rangée par catégorie (comme Netflix), « Voir tout » ouvre la grille de la catégorie.
        if (selected == null && chips.isNotEmpty()) {
            CategoryRows(vm, chips, touch, onOpen)
            return@Column
        }
        if (items.itemCount == 0) {
            // Grille en cours de chargement : squelette, pas « aucun film ».
            if (items.loadState.refresh is androidx.paging.LoadState.Loading) { RowsSkeleton(touch); return@Column }
            if (chips.isEmpty() && com.ultratv.tv.nativeapp.ui.common.NoDataStateCard()) return@Column
            Text(if (kind == CatalogKind.MOVIES) D.noMovies else D.noSeries, color = Ux.Text3, fontFamily = Manrope, fontSize = 24.spx, maxLines = 2)
            return@Column
        }
        val first = remember { FocusRequester() }
        var firstFocused by remember { mutableStateOf(false) }
        RequestInitialFocus(first, hasFocus = { firstFocused }, key = selected)
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (touch) com.ultratv.tv.nativeapp.ui.mobile.gridColumns(widthDp - 40f - (if (widthDp >= 600f) 88f else 0f)) else 7),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 28.design),
            verticalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 28.design),
            contentPadding = PaddingValues(top = if (touch) 4.dp else 12.design, bottom = if (touch) 16.dp else 54.design),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }, contentType = { "poster" }) { i ->
                val it = items[i]
                if (it != null) PosterCell(it, if (i == 0) Modifier.focusRequester(first).onFocusChanged { f -> firstFocused = f.isFocused } else Modifier) { onOpen(it.id) }
                else androidx.compose.foundation.layout.Spacer(Modifier.height(1.design))
            }
        }
    }
    }
}

@Composable
private fun CategoryRows(vm: CatalogGridViewModel, chips: List<CategoryChip>, touch: Boolean, onOpen: (Long) -> Unit) {
    val D = LocalDs.current
    val first = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    RequestInitialFocus(first, hasFocus = { firstFocused }, key = chips.firstOrNull()?.remoteId)
    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(if (touch) 18.dp else 36.design),
        contentPadding = PaddingValues(bottom = if (touch) 16.dp else 54.design),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(chips.size, key = { chips[it].remoteId }, contentType = { "row" }) { idx ->
            val c = chips[idx]
            val loaded by remember(c.remoteId) { vm.rowItems(c.remoteId) }.collectAsState()
            val list = loaded.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 16.design)) {
                Text(c.name, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = if (touch) 18.sp else 30.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (loaded == null) { SkeletonRow(touch); return@Column }
                LazyRow(Modifier.rowBleedStart(RowBleed), horizontalArrangement = Arrangement.spacedBy(if (touch) 10.dp else 24.design), contentPadding = PaddingValues(start = RowBleed, end = if (touch) 16.dp else 96.design)) {
                    items(list.size, key = { list[it].id }, contentType = { "poster" }) { i ->
                        val it = list[i]
                        val m = Modifier.width(if (touch) 120.dp else 200.design)
                        PosterCell(it, if (idx == 0 && i == 0) m.focusRequester(first).onFocusChanged { f -> firstFocused = f.isFocused } else m) { onOpen(it.id) }
                    }
                    if (list.size >= ROW_SIZE) item(key = "more-${c.remoteId}") {
                        FocusSurface(onClick = { vm.select(c.remoteId) }, shape = RoundedCornerShape(18.design), bg = Ux.Surface,
                            modifier = Modifier.width(if (touch) 120.dp else 200.design).height(if (touch) 180.dp else 300.design)) { f ->
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(D.seeAll, color = if (f) Ux.TextOnLight else Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = if (touch) 14.sp else 24.spx)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Rangée d'affiches vides, à la taille exacte d'une vraie rangée (affiche 2:3 + titre + méta). */
@Composable
private fun SkeletonRow(touch: Boolean) {
    val w = if (touch) 120.dp else 200.design
    Row(horizontalArrangement = Arrangement.spacedBy(if (touch) 10.dp else 24.design)) {
        repeat(if (touch) 3 else 7) {
            Column(verticalArrangement = Arrangement.spacedBy(if (touch) 6.dp else 10.design)) {
                androidx.compose.foundation.layout.Box(Modifier.width(w).height(w * 1.5f).clip(RoundedCornerShape(if (touch) 12.dp else 18.design)).background(Ux.Surface))
                androidx.compose.foundation.layout.Box(Modifier.width(w * 0.8f).height(if (touch) 12.dp else 22.design).clip(RoundedCornerShape(6.design)).background(Ux.Surface))
                androidx.compose.foundation.layout.Box(Modifier.width(w * 0.5f).height(if (touch) 10.dp else 18.design).clip(RoundedCornerShape(6.design)).background(Ux.Surface))
            }
        }
    }
}

/** Écran Films / Séries en chargement : deux rangées squelettes avec leur titre. */
@Composable
private fun RowsSkeleton(touch: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(if (touch) 18.dp else 36.design)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 16.design)) {
                androidx.compose.foundation.layout.Box(Modifier.width(if (touch) 140.dp else 320.design).height(if (touch) 16.dp else 30.design).clip(RoundedCornerShape(8.design)).background(Ux.Surface))
                SkeletonRow(touch)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(if (touch) 20.dp else 28.design), bg = if (selected) Ux.Cta else Ux.Surface, modifier = Modifier.height(if (touch) 40.dp else 56.design)) { f ->
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = if (touch) 16.dp else 28.design).height(if (touch) 40.dp else 56.design), contentAlignment = Alignment.Center) {
            Text(label, color = if (f || selected) Ux.TextOnLight else Ux.Text2, fontFamily = Manrope, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, fontSize = if (touch) 13.sp else 22.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Affiche 2:3 (focus : ×1,06 + anneau), titre 22 gras sur 1 ligne, année · note 18. */
@Composable
internal fun PosterCell(item: PosterItem, modifier: Modifier, onClick: () -> Unit) {
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (touch) 6.dp else 12.design)) {
        FocusSurface(onClick = onClick, shape = RoundedCornerShape(18.design), bg = Ux.Surface, ringWidth = 5.design, focusedBg = Color0xE4E4E7, modifier = Modifier.fillMaxWidth().aspectRatio23()) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(item.poster, item.title, Modifier.fillMaxSize(), radius = 18)
                com.ultratv.tv.nativeapp.ui.common.LangBadge(item.lang, modifier = Modifier.align(Alignment.TopStart).padding(10.design))
                if (item.watched) WatchedBadge(Modifier.align(Alignment.BottomEnd).padding(8.design))
            }
        }
        // Deux lignes réservées (cellules alignées dans la rangée / la grille) : un titre long n'est plus coupé à 15 caractères.
        Text(item.title, color = Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = if (touch) 13.sp else 22.spx,
            lineHeight = if (touch) 16.sp else 27.spx, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Formatage (String.format) une fois par affiche, pas à chaque recomposition de la cellule.
        val meta = androidx.compose.runtime.remember(item.year, item.rating) { listOfNotNull(item.year?.toString(), item.rating?.let { "★ %.1f".format(java.util.Locale.ROOT, it) }).joinToString(" · ") }
        Text(meta, color = Ux.Text3, fontFamily = Manrope, fontSize = if (touch) 12.sp else 22.spx, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(if (touch) 16.dp else 30.design))
    }
}

private val Color0xE4E4E7 = androidx.compose.ui.graphics.Color(0xFFE4E4E7)
private fun Modifier.aspectRatio23() = this.aspectRatio(2f / 3f)

/** Pastille « ✓ Vu » (film vu sur Trakt) : discrète, en bas à droite de l'affiche. */
@Composable
internal fun WatchedBadge(modifier: Modifier = Modifier) {
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    Text(
        "✓ " + com.ultratv.tv.nativeapp.i18n.LocalDs.current.traktWatched, color = Ux.White, fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = if (touch) 10.sp else 20.spx, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.design)).background(androidx.compose.ui.graphics.Color(0xCC0A0A0C)).padding(horizontal = 8.design, vertical = 3.design),
    )
}
