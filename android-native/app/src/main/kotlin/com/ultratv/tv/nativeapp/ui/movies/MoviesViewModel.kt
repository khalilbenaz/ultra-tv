package com.ultratv.tv.nativeapp.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.prefs.HiddenCategoriesStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Group of movies bound to a category — one rail in the Netflix-style screen. */
data class MovieRail(val category: CategoryEntity?, val items: List<MovieEntity>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val hiddenStore: HiddenCategoriesStore,
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val providers = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = combine(
        providers, hiddenStore.hidden,
    ) { ps, hidden -> ps to hidden }
        .flatMapLatest { (ps, hidden) ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.categories(pid, "MOVIE").map { list ->
                list.filter { hiddenStore.keyFor("MOVIE", pid, it.remoteId) !in hidden }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Flat filtered list — used when the user picks a single category from the
     * chip filter. Stays as a simple grid in that mode.
     */
    val movies: StateFlow<List<MovieEntity>> = combine(
        providers, _selectedCategory, hiddenStore.hidden,
    ) { ps, cat, hidden -> Triple(ps, cat, hidden) }
        .flatMapLatest { (ps, cat, hidden) ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.movies(pid).map { list ->
                list.filter { m ->
                    val cid = m.categoryId
                    if (cid != null && hiddenStore.keyFor("MOVIE", pid, cid) in hidden) return@filter false
                    cat == null || m.categoryId == cat
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Netflix-style rails: a list of (category, top-N items) pairs. Items are
     * capped at 25 per rail so even a 50k-movie catalog stays fluid (lazy
     * horizontal scrolling within the rail handles the rest of the discovery).
     */
    val rails: StateFlow<List<MovieRail>> = combine(
        providers, hiddenStore.hidden,
    ) { ps, hidden -> ps to hidden }
        .flatMapLatest { (ps, hidden) ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            combine(catalog.categories(pid, "MOVIE"), catalog.movies(pid)) { cats, movs ->
                val visibleCats = cats.filter { hiddenStore.keyFor("MOVIE", pid, it.remoteId) !in hidden }
                val groups = movs.groupBy { it.categoryId }
                val rails = mutableListOf<MovieRail>()
                visibleCats.forEach { cat ->
                    val items = groups[cat.remoteId].orEmpty().take(25)
                    if (items.isNotEmpty()) rails += MovieRail(cat, items)
                }
                // Trailing "Other" rail for items whose category was hidden or unknown.
                val unbucketed = movs.filter { m ->
                    val cid = m.categoryId
                    cid == null || cid !in visibleCats.map { c -> c.remoteId }.toSet()
                }
                if (unbucketed.isNotEmpty()) {
                    rails += MovieRail(null, unbucketed.take(25))
                }
                rails
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featured: StateFlow<MovieEntity?> = movies
        .map { list ->
            // Pick the most-recent year, breaking ties by name length (deterministic).
            list.maxByOrNull { (it.year ?: 0) * 100L + (it.name.length % 100) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectCategory(remoteId: String?) { _selectedCategory.value = remoteId }
}
