package com.ultratv.tv.nativeapp.ui.parental

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
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.prefs.LockedChannelsStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LockedChannelsViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val store: LockedChannelsStore,
) : ViewModel() {

    val channels: StateFlow<List<ChannelEntity>> = providerRepo.observeProviders()
        .flatMapLatest { ps ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
                ?: return@flatMapLatest flowOf(emptyList())
            catalog.channels(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locked: StateFlow<Set<String>> = store.locked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggle(channel: ChannelEntity, on: Boolean) {
        viewModelScope.launch { store.set(channel.providerId, channel.remoteId, on) }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun LockedChannelsScreen(vm: LockedChannelsViewModel = hiltViewModel()) {
    val chans by vm.channels.collectAsState()
    val locked by vm.locked.collectAsState()
    var search by remember { mutableStateOf("") }

    val filtered by remember(chans, search) {
        derivedStateOf {
            if (search.isBlank()) chans
            else chans.filter { it.name.contains(search, ignoreCase = true) }
        }
    }
    val lockedCount = filtered.count { "${it.providerId}:${it.remoteId}" in locked }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lock individual channels", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Locked channels need the parental PIN to play. ${chans.size} total · $lockedCount locked",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
                    if (search.isEmpty()) Text("Filter channels…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    inner()
                },
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { it.id }) { c ->
                val isOn = "${c.providerId}:${c.remoteId}" in locked
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        c.name + if (isOn) "  🔒" else "",
                        fontSize = 15.sp,
                        color = if (isOn) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { vm.toggle(c, !isOn) },
                        colors = if (isOn) ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                        else ButtonDefaults.colors(),
                    ) { Text(if (isOn) "Unlock" else "Lock") }
                }
            }
        }
    }
}
