package com.ultratv.tv.nativeapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.prefs.AppTheme
import com.ultratv.tv.nativeapp.data.prefs.DefaultPlayer
import com.ultratv.tv.nativeapp.data.prefs.SidebarPosition
import com.ultratv.tv.nativeapp.ui.AppViewModel

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun PreferencesSection(vm: AppViewModel = hiltViewModel()) {
    val p by vm.prefs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // Menu position
        PrefRow(label = "Menu position") {
            ChoiceChip("Sidebar", on = p.sidebarPosition == SidebarPosition.LEFT) { vm.setSidebar(SidebarPosition.LEFT) }
            ChoiceChip("Top bar", on = p.sidebarPosition == SidebarPosition.TOP) { vm.setSidebar(SidebarPosition.TOP) }
        }

        // Theme
        PrefRow(label = "Theme") {
            ChoiceChip("Dark", on = p.theme == AppTheme.DARK) { vm.setTheme(AppTheme.DARK) }
            ChoiceChip("AMOLED", on = p.theme == AppTheme.AMOLED) { vm.setTheme(AppTheme.AMOLED) }
            ChoiceChip("Blue", on = p.theme == AppTheme.BLUE) { vm.setTheme(AppTheme.BLUE) }
        }

        // Default player
        PrefRow(label = "Default player") {
            ChoiceChip("Internal (Media3)", on = p.defaultPlayer == DefaultPlayer.INTERNAL) { vm.setDefaultPlayer(DefaultPlayer.INTERNAL) }
            ChoiceChip("External (VLC / MX)", on = p.defaultPlayer == DefaultPlayer.EXTERNAL) { vm.setDefaultPlayer(DefaultPlayer.EXTERNAL) }
        }

        SwitchRow("Auto-sync on launch", "Pull provider catalogs every time the app starts.", p.autoSyncOnLaunch) { vm.setAutoSync(it) }
        SwitchRow("Show channel numbers", "Display the position number next to each channel in Live TV.", p.showChannelNumbers) { vm.setShowChannelNumbers(it) }
        SwitchRow("Hide adult categories", "Completely remove adult categories from lists (beyond PIN lock).", p.hideAdultCategories) { vm.setHideAdult(it) }
        SwitchRow("Resume playback", "Reopen movies/episodes at the position you left them.", p.resumePlayback) { vm.setResumePlayback(it) }
        SwitchRow("Auto-play next episode", "Automatically play S0xE0y+1 when an episode ends.", p.autoPlayNextEpisode) { vm.setAutoPlayNext(it) }
        SwitchRow("Launch at TV boot", "Open Ultra TV automatically when the box finishes booting.", p.launchAtBoot) { vm.setLaunchAtBoot(it) }
        SwitchRow("Auto-play last watched on launch", "Resume the last channel / movie / episode when the app starts.", p.autoPlayLastOnLaunch) { vm.setAutoPlayLast(it) }

        PrefRow(label = "Refresh playlists") {
            IntervalChip("Every launch", 0, p.syncIntervalHours, vm::setSyncInterval)
            IntervalChip("Every 6h", 6, p.syncIntervalHours, vm::setSyncInterval)
            IntervalChip("Every 12h", 12, p.syncIntervalHours, vm::setSyncInterval)
            IntervalChip("Every 24h", 24, p.syncIntervalHours, vm::setSyncInterval)
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun IntervalChip(label: String, value: Int, current: Int, onSet: (Int) -> Unit) {
    ChoiceChip(label, on = value == current) { onSet(value) }
}

@Composable
private fun PrefRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(20.dp)),
        colors = if (on) ButtonDefaults.colors()
        else ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Text(label, fontSize = 14.sp) }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun SwitchRow(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
