package com.ultratv.tv.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private data class BottomItem(val route: String, val labelOf: (com.ultratv.tv.nativeapp.i18n.Strings) -> String, val icon: String)

private val items = listOf(
    BottomItem("home", { it.navHome }, "🏠"),
    BottomItem("live", { it.navLive }, "📺"),
    BottomItem("movies", { it.navMovies }, "🎬"),
    BottomItem("series", { it.navSeries }, "📚"),
    BottomItem("search", { it.navSearch }, "🔍"),
    BottomItem("settings", { it.navSettings }, "⚙"),
)

/**
 * Compact-width navigation bar: a single row of icon+label buttons across
 * the bottom of the screen. The full ten-route set scrolls horizontally so
 * users on narrow phones can still reach Guide / Favorites / Multi-View
 * without us giving up vertical real estate.
 */
@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
fun BottomBarNav(navController: NavController) {
    val current by navController.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "home"
    val strings = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 6.dp, vertical = 6.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val selected = route == item.route ||
                (item.route == "live" && route.startsWith("player")) ||
                (item.route == "movies" && route.startsWith("movies/")) ||
                (item.route == "series" && route.startsWith("series/"))
            Button(
                onClick = {
                    if (route != item.route) {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
                colors = if (selected) ButtonDefaults.colors()
                else ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.icon, fontSize = 18.sp)
                    Text(item.labelOf(strings), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
