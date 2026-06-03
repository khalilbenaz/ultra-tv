package com.ultratv.tv.nativeapp.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.db.SeriesEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.data.repo.SearchResults
import com.ultratv.tv.nativeapp.ui.common.ChannelLogo
import com.ultratv.tv.nativeapp.ui.components.UltraIcon
import com.ultratv.tv.nativeapp.ui.theme.UltraFonts
import com.ultratv.tv.nativeapp.ui.theme.UltraTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val provider: ProviderRepository,
    private val catalog: CatalogRepository,
    private val history: com.ultratv.tv.nativeapp.data.prefs.SearchHistoryStore,
) : ViewModel() {
    private val _q = MutableStateFlow("")
    val query: StateFlow<String> = _q.asStateFlow()
    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    val recent: StateFlow<List<String>> = history.recent
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    fun setQuery(s: String) {
        _q.value = s
        job?.cancel()
        job = viewModelScope.launch {
            delay(220)
            val pid = provider.firstActive()?.id ?: return@launch
            _results.value = catalog.search(pid, s)
            if (s.length >= 3) history.record(s)
        }
    }

    fun append(c: Char) { setQuery(query.value + c) }
    fun backspace() { setQuery(query.value.dropLast(1)) }
    fun clear() { setQuery("") }
    fun clearHistory() { viewModelScope.launch { history.clear() } }
}

private val KB_ROWS = listOf(
    "ABCDEFGHIJ".toList(),
    "KLMNOPQRST".toList(),
    "UVWXYZ-'.,".toList(),
    "0123456789".toList(),
)

private val FILTERS = listOf("Tous", "Films", "Séries", "Chaînes", "Sport", "Documentaire", "Reprendre")

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    // (streamUrl, title) — same play path the Live/Guide screens use.
    onOpenChannel: (String, String) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val q by vm.query.collectAsState()
    val r by vm.results.collectAsState()
    val recent by vm.recent.collectAsState()
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    var activeFilter by remember { mutableStateOf(0) }

    Row(Modifier.fillMaxSize().padding(top = 60.dp)) {
        // ===== LEFT: keyboard + recents =====
        Column(
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .padding(start = 40.dp, end = 40.dp),
        ) {
            Text(
                "RECHERCHE",
                color = UltraTokens.Fg3,
                fontSize = 11.sp,
                letterSpacing = 2.3.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))
            // Input field
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(UltraTokens.Surface2)
                    .border(1.dp, UltraTokens.Line2, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                if (q.isEmpty()) {
                    Text(
                        "Tapez votre recherche…",
                        color = UltraTokens.Fg4,
                        fontSize = 22.sp,
                        fontFamily = UltraFonts.Serif,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            q,
                            color = UltraTokens.Fg,
                            fontSize = 22.sp,
                            fontFamily = UltraFonts.Serif,
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(24.dp)
                                .background(UltraTokens.Accent),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            // On-screen keyboard
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x66000000))
                    .border(1.dp, UltraTokens.Line, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KB_ROWS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { ch ->
                            KeyboardKey(
                                label = ch.toString(),
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                onClick = { vm.append(ch.lowercaseChar()) },
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    KeyboardKey(
                        label = "Espace",
                        modifier = Modifier.weight(2f).height(40.dp),
                        onClick = { vm.append(' ') },
                    )
                    KeyboardKey(
                        label = "⌫ Suppr",
                        modifier = Modifier.weight(1.2f).height(40.dp),
                        onClick = { vm.backspace() },
                    )
                    KeyboardKey(
                        label = "Effacer",
                        modifier = Modifier.weight(1f).height(40.dp),
                        danger = true,
                        onClick = { vm.clear() },
                    )
                }
            }
            // Recents
            Spacer(Modifier.height(24.dp))
            if (recent.isNotEmpty()) {
                Text(
                    "RÉCENTES",
                    color = UltraTokens.Fg3,
                    fontSize = 11.sp,
                    letterSpacing = 2.3.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recent.take(10).forEach { rec ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(UltraTokens.Surface2)
                                .border(1.dp, UltraTokens.Line, RoundedCornerShape(999.dp))
                                .clickable { vm.setQuery(rec) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(rec, color = UltraTokens.Fg2, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Divider
        Box(Modifier.width(1.dp).fillMaxHeight().background(UltraTokens.Line))

        // ===== RIGHT: filter chips + result sections =====
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 60.dp, end = 80.dp, top = 0.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Filter chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 24.dp),
            ) {
                items(FILTERS.size) { idx ->
                    val active = idx == activeFilter
                    val interaction = remember(idx) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val highlight = active || focused
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (highlight) UltraTokens.Accent else UltraTokens.Surface2)
                            .border(
                                1.dp,
                                if (highlight) UltraTokens.Accent else UltraTokens.Line2,
                                RoundedCornerShape(999.dp),
                            )
                            .clickable(interactionSource = interaction, indication = null) { activeFilter = idx }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            FILTERS[idx],
                            color = if (highlight) Color.White else UltraTokens.Fg2,
                            fontSize = 13.sp,
                            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))

            val total = r.channels.size + r.movies.size + r.series.size
            if (q.isBlank()) {
                Text(
                    "Commencez à taper pour rechercher.",
                    color = UltraTokens.Fg3,
                    fontSize = 14.sp,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$total RÉSULTATS POUR",
                        color = UltraTokens.Fg3,
                        fontSize = 11.sp,
                        letterSpacing = 2.3.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "« $q »",
                        color = UltraTokens.Fg,
                        fontSize = 18.sp,
                        fontFamily = UltraFonts.Serif,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            val showAll = activeFilter == 0
            if (showAll || activeFilter == 1) {
                ResultSection("Films", r.movies, total) { m ->
                    SquareResultCard(m.name, m.year?.toString(), onClick = { onOpenMovie(m.id) })
                }
            }
            if (showAll || activeFilter == 2) {
                ResultSection("Séries", r.series, total) { s ->
                    SquareResultCard(s.name, s.year?.toString(), onClick = { onOpenSeries(s.id) })
                }
            }
            if (showAll || activeFilter == 3) {
                ResultSection("Chaînes en direct", r.channels, total) { c ->
                    ChannelResultCard(c, onClick = { onOpenChannel(c.streamUrl, c.name) })
                }
            }

            if (q.isNotBlank() && total == 0) {
                Text(
                    "Aucun résultat",
                    color = UltraTokens.Fg3,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> UltraTokens.Accent
                    danger -> UltraTokens.AccentSoft
                    else -> Color(0x0DFFFFFF)
                }
            )
            .border(
                1.dp,
                when {
                    focused -> UltraTokens.Accent
                    danger -> Color(0x4DFF3A2F)
                    else -> UltraTokens.Line
                },
                RoundedCornerShape(8.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> Color.White
                danger -> UltraTokens.Accent
                else -> UltraTokens.Fg2
            },
            fontSize = if (label.length > 1) 11.sp else 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun <T : Any> ResultSection(
    title: String,
    items: List<T>,
    @Suppress("UNUSED_PARAMETER") total: Int,
    card: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            "$title · ${items.size}",
            color = UltraTokens.Fg2,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.take(12).forEach { card(it) }
        }
    }
}

@Composable
private fun SquareResultCard(title: String, sub: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(UltraTokens.Surface1)
            .border(1.dp, UltraTokens.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(UltraTokens.Surface2),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, color = UltraTokens.Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        if (sub != null) {
            Text(sub, color = UltraTokens.Fg3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChannelResultCard(c: ChannelEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(UltraTokens.Surface1)
            .border(1.dp, UltraTokens.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(
            name = c.name,
            logoUrl = c.logo,
            short = null,
            hueSeed = c.name.hashCode(),
            hd = null,
            size = 48.dp,
            showBadge = false,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(c.name, color = UltraTokens.Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "Chaîne",
                color = UltraTokens.Fg3,
                fontSize = 11.sp,
            )
        }
    }
}
