package dev.pabloi.whisper.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pabloi.whisper.recording.RecordingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    vm: RecordViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    val recState = ui.state
    val isBusy = recState !is RecordingState.Idle

    DisposableEffect(isBusy, activity) {
        val window = activity?.window
        if (isBusy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.setFolder(uri)
            tryStart(vm, context)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) tryStart(vm, context) { folderLauncher.launch(null) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Live") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
                },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = recState) {
                RecordingState.Idle, is RecordingState.Failure -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = ui.transcribeLive, onCheckedChange = { vm.setTranscribeLive(it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Transcribe while recording")
                    }
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else tryStart(vm, context) { folderLauncher.launch(null) }
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("Start", style = MaterialTheme.typography.titleLarge) }

                    if (s is RecordingState.Failure) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(s.cause.message ?: s.cause::class.java.simpleName, modifier = Modifier.padding(12.dp))
                        }
                    }
                    ui.error?.let {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(it, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                is RecordingState.Starting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Starting…")
                }
                is RecordingState.Recording -> {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatDuration(s.durationMs), style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("${s.routeLabel} · ${s.sampleRate} Hz", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            LevelMeter(levelDb = s.levelDb)
                        }
                    }
                    Button(
                        onClick = { vm.stop() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Stop", style = MaterialTheme.typography.titleLarge) }
                }
                is RecordingState.Paused -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.MicOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Paused: ${s.reason}")
                        }
                    }
                }
                is RecordingState.Stopping -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Saving…")
                }
            }

            if (ui.segments.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    reverseLayout = true,
                ) {
                    items(ui.segments.asReversed()) { (time, text) ->
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

            if (ui.needsFolder) {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("Choose a folder to save your recordings.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { folderLauncher.launch(null) }) { Text("Pick folder") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(levelDb: Float) {
    // Map −60..0 dB to 0..1.
    val frac = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun tryStart(
    vm: RecordViewModel,
    context: android.content.Context,
    pickFolder: (() -> Unit)? = null,
) {
    kotlinx.coroutines.runBlocking {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val ok = vm.preflight(micGranted = granted)
        if (ok) vm.start()
        else if (vm.ui.value.needsFolder && pickFolder != null) pickFolder()
    }
}
