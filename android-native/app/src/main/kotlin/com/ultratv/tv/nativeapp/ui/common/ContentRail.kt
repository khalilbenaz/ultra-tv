package com.ultratv.tv.nativeapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Horizontal Netflix-style rail of poster cards. The TV-specific scroll
 * behavior comes from Compose-TV's focus handling: when focus moves to a card
 * outside the viewport, LazyRow scrolls to reveal it.
 */
@Composable
fun <T : Any> ContentRail(
    title: String,
    items: List<T>,
    itemKey: (T) -> Any,
    cardWidth: Dp = 180.dp,
    emptyHint: String? = null,
    item: @Composable (T) -> Unit,
) {
    if (items.isEmpty() && emptyHint == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (items.isEmpty()) {
            Text(
                emptyHint.orEmpty(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = itemKey) { entry ->
                    Column(Modifier.width(cardWidth)) { item(entry) }
                }
            }
        }
    }
}
