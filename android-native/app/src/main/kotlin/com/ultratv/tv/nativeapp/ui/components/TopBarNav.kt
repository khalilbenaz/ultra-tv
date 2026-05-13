package com.ultratv.tv.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

private data class TopBarItem(val route: String, val labelOf: (com.ultratv.tv.nativeapp.i18n.Strings) -> String, val icon: String)

private val items = listOf(
    TopBarItem("home", { it.navHome }, "🏠"),
    TopBarItem("live", { it.navLive }, "📺"),
    TopBarItem("guide", { it.navGuide }, "🗓"),
    TopBarItem("movies", { it.navMovies }, "🎬"),
    TopBarItem("series", { it.navSeries }, "📚"),
    TopBarItem("favorites", { it.navFavorites }, "★"),
    TopBarItem("search", { it.navSearch }, "🔍"),
    TopBarItem("categories", { it.navCategories }, "🏷"),
    TopBarItem("multiview", { it.navMultiview }, "▦"),
    TopBarItem("settings", { it.navSettings }, "⚙"),
)

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
fun TopBarNav(navController: NavController) {
    val current by navController.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "home"
    val strings = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Ultra TV",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.padding(end = 16.dp),
        )
        items.forEach { item ->
            val selected = isSelected(route, item.route)
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
                shape = ButtonDefaults.shape(RoundedCornerShape(20.dp)),
                colors = if (selected) ButtonDefaults.colors()
                else ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("${item.icon}  ${item.labelOf(strings)}", fontSize = 14.sp)
            }
        }
    }
}

private fun isSelected(route: String, candidate: String): Boolean = when {
    route == candidate -> true
    candidate == "live" && route.startsWith("player") -> true
    candidate == "movies" && route.startsWith("movies/") -> true
    candidate == "series" && route.startsWith("series/") -> true
    else -> false
}
