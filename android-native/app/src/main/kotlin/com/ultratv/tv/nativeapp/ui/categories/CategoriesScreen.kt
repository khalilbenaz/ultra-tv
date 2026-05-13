package com.ultratv.tv.nativeapp.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import javax.inject.Inject

private val ADULT_REGEX = Regex("xxx|adult|18\\+|porn|ero|adulte|للكبار", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val hidden: HiddenCategoriesStore,
) : ViewModel() {

    private val _kind = MutableStateFlow("LIVE")
    val kind: StateFlow<String> = _kind.asStateFlow()

    val hiddenSet: StateFlow<Set<String>> = hidden.hidden
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val categories: StateFlow<List<CategoryEntity>> =
        combine(providerRepo.observeProviders(), _kind) { ps, k -> ps to k }
            .flatMapLatest { (ps, k) ->
                val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
                catalog.categories(pid, k)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setKind(k: String) { _kind.value = k }

    fun toggle(cat: CategoryEntity, hide: Boolean) {
        viewModelScope.launch {
            hidden.set(hidden.keyFor(cat.kind, cat.providerId, cat.remoteId), hide)
        }
    }

    fun hideAllShown(shown: List<CategoryEntity>) {
        viewModelScope.launch {
            shown.forEach { hidden.set(hidden.keyFor(it.kind, it.providerId, it.remoteId), true) }
        }
    }

    fun showAllShown(shown: List<CategoryEntity>) {
        viewModelScope.launch {
            shown.forEach { hidden.set(hidden.keyFor(it.kind, it.providerId, it.remoteId), false) }
        }
    }

    fun hideAdultIn(shown: List<CategoryEntity>) {
        viewModelScope.launch {
            shown.filter { ADULT_REGEX.containsMatchIn(it.name) }
                .forEach { hidden.set(hidden.keyFor(it.kind, it.providerId, it.remoteId), true) }
        }
    }

    fun resetAll() = viewModelScope.launch { hidden.clearAll() }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun CategoriesScreen(vm: CategoriesViewModel = hiltViewModel()) {
    val kind by vm.kind.collectAsState()
    val cats by vm.categories.collectAsState()
    val hidden by vm.hiddenSet.collectAsState()
    var search by remember { mutableStateOf("") }

    val filtered by remember(cats, search) {
        derivedStateOf {
            if (search.isBlank()) cats
            else cats.filter { it.name.contains(search, ignoreCase = true) }
        }
    }
    val visibleCount = filtered.count { "${it.kind}:${it.providerId}:${it.remoteId}" !in hidden }
    val hiddenCount = filtered.size - visibleCount

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manage categories", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "${cats.size} total · $visibleCount shown · $hiddenCount hidden${if (search.isNotBlank()) " (filter: \"$search\")" else ""}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindButton("LIVE", "Live TV", kind, vm::setKind)
            KindButton("MOVIE", "Movies", kind, vm::setKind)
            KindButton("SERIES", "Series", kind, vm::setKind)
        }

        // Search box
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
        ) {
            BasicTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (search.isEmpty()) Text(
                        "Filter category names…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                    )
                    inner()
                },
            )
        }

        // Bulk actions — apply to the currently filtered list.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.hideAllShown(filtered) }) { Text("Hide all filtered") }
            Button(onClick = { vm.showAllShown(filtered) },
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) { Text("Show all filtered") }
            Button(onClick = { vm.hideAdultIn(filtered) }) { Text("🔞 Hide adult") }
            Button(onClick = { vm.resetAll() },
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) { Text("Reset everything") }
        }

        if (filtered.isEmpty()) {
            Text(
                if (cats.isEmpty()) "No categories yet — add a provider and re-sync."
                else "No category matches \"$search\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { it.id }) { cat ->
                    val key = "${cat.kind}:${cat.providerId}:${cat.remoteId}"
                    val isHidden = key in hidden
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            buildString {
                                append(cat.name)
                                if (cat.locked) append(" 🔒")
                                if (ADULT_REGEX.containsMatchIn(cat.name)) append(" 🔞")
                            },
                            fontSize = 15.sp,
                            color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { vm.toggle(cat, !isHidden) },
                            colors = if (isHidden) ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                            else ButtonDefaults.colors(),
                        ) {
                            Text(if (isHidden) "Show" else "Hide")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun KindButton(value: String, label: String, current: String, onSelect: (String) -> Unit) {
    val active = value == current
    Button(
        onClick = { onSelect(value) },
        shape = ButtonDefaults.shape(RoundedCornerShape(18.dp)),
        colors = if (active) ButtonDefaults.colors() else ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Text(label) }
}
