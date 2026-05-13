package com.ultratv.tv.nativeapp.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.ui.common.prettyCategoryName

/**
 * Tivimate-inspired Live TV layout. Two stacked panes:
 *
 *   ┌──────────────────────┬──────────────────────────────────────────┐
 *   │  Categories          │  Channels in selected category           │
 *   │  • All channels      │  ┌─────┐ 001  TF1                         │
 *   │  • News              │  ┌─────┐ 002  France 2                    │
 *   │  • Sport             │  ┌─────┐ 003  M6                          │
 *   │  • Kids              │  …                                        │
 *   └──────────────────────┴──────────────────────────────────────────┘
 *
 * The right pane only renders channels for the selected category, so even on
 * a 50k-channel provider only ~hundreds are composed at any time — that's
 * the main lag fix.
 */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun LiveScreen(onPlay: (url: String, title: String) -> Unit, vm: LiveViewModel = hiltViewModel()) {
    val cats by vm.categories.collectAsState()
    val chans by vm.channels.collectAsState()
    val selected by vm.selectedCategory.collectAsState()
    val locked by vm.lockedChannels.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    // Channel awaiting PIN unlock; non-null while the dialog is up.
    var pinPrompt by remember { mutableStateOf<com.ultratv.tv.nativeapp.data.db.ChannelEntity?>(null) }

    Row(Modifier.fillMaxSize()) {
        // ---- Left pane: categories ----
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Categories",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item("__all__") {
                    CategoryRow(
                        label = "All channels",
                        selected = selected == CATEGORY_ALL,
                        onClick = { vm.selectCategory(CATEGORY_ALL) },
                    )
                }
                items(cats, key = { it.id }) { cat ->
                    CategoryRow(
                        label = prettyCategoryName(cat.name) + if (cat.locked) "  🔒" else "",
                        selected = selected == cat.remoteId,
                        onClick = { vm.selectCategory(cat.remoteId) },
                    )
                }
            }
        }

        // Thin vertical divider
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        )

        // ---- Right pane: channels in selected category ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = if (selected == CATEGORY_ALL) "All channels"
            else prettyCategoryName(cats.firstOrNull { it.remoteId == selected }?.name ?: "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("${chans.size} channels", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val listState = rememberLazyListState()
            // Reset scroll when the user switches category so they always see
            // the top of the new list, like Tivimate.
            LaunchedEffect(selected) { listState.scrollToItem(0) }

            if (chans.isEmpty()) {
                Text(
                    "No channels in this category.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(end = 8.dp),
                ) {
                    itemsIndexed(chans, key = { _, c -> c.id }) { i, c ->
                        val isLocked = "${c.providerId}:${c.remoteId}" in locked
                        val nn = nowNext[c.id]
                        ChannelRow(
                            channel = c,
                            position = i + 1,
                            locked = isLocked,
                            nowProgramme = nn?.first,
                            nextProgramme = nn?.second,
                        ) {
                            if (isLocked) pinPrompt = c
                            else vm.resolveAndPlay(c, onPlay)
                        }
                    }
                }
            }
        }
    }

    // PIN dialog when the user clicks a locked channel.
    pinPrompt?.let { ch ->
        com.ultratv.tv.nativeapp.ui.parental.PinPromptDialog(
            title = "🔒 ${ch.name}",
            onUnlocked = {
                pinPrompt = null
                vm.resolveAndPlay(ch, onPlay)
            },
            onCancel = { pinPrompt = null },
        )
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        colors = if (selected || focused) CardDefaults.colors(containerColor = MaterialTheme.colorScheme.primary)
        else CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected || focused) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    position: Int,
    locked: Boolean = false,
    nowProgramme: com.ultratv.tv.nativeapp.data.db.EpgEntity? = null,
    nextProgramme: com.ultratv.tv.nativeapp.data.db.EpgEntity? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = if (focused) CardDefaults.colors(containerColor = MaterialTheme.colorScheme.primary)
        else CardDefaults.colors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "%03d".format(position),
                color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.width(40.dp),
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(model = channel.logo, contentDescription = channel.name, modifier = Modifier.fillMaxSize())
                } else {
                    Text("📺", fontSize = 22.sp)
                }
            }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    channel.name + if (locked) "  🔒" else "",
                    color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (nowProgramme != null) {
                    Text(
                        "▶ ${nowProgramme.title}" + (nextProgramme?.let { "  ·  next: ${it.title}" } ?: ""),
                        color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
