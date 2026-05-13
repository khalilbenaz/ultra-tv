package com.ultratv.tv.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private data class NavItem(val route: String, val labelOf: (com.ultratv.tv.nativeapp.i18n.Strings) -> String, val icon: String)

private val items = listOf(
    NavItem("home", { it.navHome }, "🏠"),
    NavItem("live", { it.navLive }, "📺"),
    NavItem("guide", { it.navGuide }, "🗓"),
    NavItem("movies", { it.navMovies }, "🎬"),
    NavItem("series", { it.navSeries }, "📚"),
    NavItem("favorites", { it.navFavorites }, "★"),
    NavItem("search", { it.navSearch }, "🔍"),
    NavItem("categories", { it.navCategories }, "🏷"),
    NavItem("multiview", { it.navMultiview }, "▦"),
    NavItem("settings", { it.navSettings }, "⚙"),
)

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
fun SidebarNav(navController: NavController) {
    val current by navController.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "home"
    val strings = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    // Wrap items in a scrollable column — TV remotes auto-scroll a verticalScroll
    // container when focus moves below the visible area. Without this, items past
    // the screen are unreachable.
    Column(
        Modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 10.dp, vertical = 18.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Ultra TV",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            val selected = isSelected(route, item.route)
            ListItem(
                selected = selected,
                onClick = {
                    if (route != item.route) {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                headlineContent = { Text(item.labelOf(strings), fontSize = 16.sp) },
                leadingContent = { Text(item.icon, fontSize = 18.sp) },
                shape = ListItemDefaults.shape(shape = RoundedCornerShape(10.dp)),
            )
        }
    }
}

private fun isSelected(route: String, candidate: String): Boolean = when {
    route == candidate -> true
    candidate == "live" && (route.startsWith("player")) -> true
    candidate == "movies" && route.startsWith("movies/") -> true
    candidate == "series" && route.startsWith("series/") -> true
    else -> false
}
