package com.ultratv.tv.nativeapp.ui.recordings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.RecordingEntity
import com.ultratv.tv.nativeapp.data.recording.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val repo: RecordingRepository,
) : ViewModel() {
    val items: StateFlow<List<RecordingEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onPlayLocal: (filePath: String, title: String) -> Unit,
    vm: RecordingsViewModel = hiltViewModel(),
) {
    val list by vm.items.collectAsState()
    val ctx = LocalContext.current
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(S.recordingsTitle, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        if (list.isEmpty()) {
            Text(S.recordingsEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        val pct = if (r.totalBytes > 0) (r.downloadedBytes * 100 / r.totalBytes).toInt() else 0
                        val sub = when (r.status) {
                            "running" -> "${S.recordingStatusRunning} $pct% (${formatBytes(r.downloadedBytes)} / ${formatBytes(r.totalBytes)})"
                            "done" -> "${S.recordingStatusDone} · ${formatBytes(r.totalBytes)}"
                            "failed", "error" -> "${S.recordingStatusFailed} — ${r.errorMessage ?: "unknown error"}"
                            "cancelled" -> S.recordingStatusCancelled
                            else -> S.recordingStatusQueued
                        }
                        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (r.status == "done") {
                        Button(onClick = { onPlayLocal("file://${r.filePath}", r.title) }) { Text(S.recordingsPlay) }
                        Button(onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(android.net.Uri.parse("file://${r.filePath}"), "video/*")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                ctx.startActivity(Intent.createChooser(intent, S.recordingsOpenWith))
                            }
                        }, colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Text(S.recordingsOpenWith)
                        }
                    }
                    Button(
                        onClick = { vm.remove(r.id) },
                        colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) { Text(S.delete) }
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024L -> "$b B"
    b < 1024L * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
    else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
}
