package dev.pabloi.whisper.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenRecord: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.transcribe.collectAsState()
    val dl by vm.download.collectAsState()
    val settings by vm.settings.collectAsState(initial = dev.pabloi.whisper.data.AppSettings())
    val context = LocalContext.current

    // Big-model download or NPU transcription suffer if Samsung Freecess freezes
    // us when the screen turns off. Hold the screen on while either is in flight;
    // it auto-releases when the work completes.
    val keepScreenOn = dl.inProgress || state.busy
    val activity = context as? Activity
    DisposableEffect(keepScreenOn, activity) {
        val window = activity?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            vm.transcribe(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whispr") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EngineBanner(settings.engine.name.lowercase().replaceFirstChar { it.uppercase() })

            if (settings.engine == dev.pabloi.whisper.data.EngineChoice.LOCAL && !dl.installed) {
                DownloadCard(dl, onClick = { vm.startDownload() })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !state.busy,
                    onClick = onOpenRecord,
                    modifier = Modifier.weight(1f)
                ) { Text("Record Live") }
                Button(
                    enabled = !state.busy,
                    onClick = {
                        picker.launch(arrayOf("audio/*", "application/ogg", "video/mp4"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Pick audio file") }
                if (state.busy) {
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
                }
            }

            if (state.busy) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
                }
            }

            state.error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }

            if (state.segments.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.segments) { (time, text) ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                if (time.isNotEmpty()) {
                                    Text(time, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            if (state.finalText.isNotEmpty()) {
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text("Transcript", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Text(state.finalText, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { vm.copyToClipboard(state.finalText) }) {
                                Icon(Icons.Outlined.ContentCopy, null)
                                Spacer(Modifier.height(0.dp))
                                Text("  Copy")
                            }
                            OutlinedButton(onClick = {
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, state.finalText)
                                }
                                context.startActivity(Intent.createChooser(i, "Share transcript"))
                            }) {
                                Icon(Icons.Outlined.Share, null)
                                Text("  Share")
                            }
                            state.lastDurationMs?.let {
                                Text(
                                    "  ${it / 1000.0}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            val recordings by vm.recordingsListStateOrEmpty().collectAsState(initial = emptyList())
            if (recordings.isNotEmpty()) {
                Text("Recordings", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recordings) { r ->
                        Card {
                            Column(Modifier.padding(8.dp)) {
                                Text(r.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${r.routeLabel} · ${r.sampleRate} Hz · ${r.durationMs / 1000}s",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineBanner(name: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text("Engine: $name", modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun DownloadCard(dl: DownloadUiState, onClick: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Download ${dl.activeSpec.displayName}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("≈ ${dl.activeSpec.approxSizeMb} MB one-time download from Qualcomm AI Hub.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (dl.inProgress) {
                LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(dl.message, style = MaterialTheme.typography.labelSmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onClick) { Text("Download") }
                    dl.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
