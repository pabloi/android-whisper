package dev.pabloi.whisper.ui

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pabloi.whisper.data.AppSettings
import dev.pabloi.whisper.data.AudioSourcePreset
import dev.pabloi.whisper.data.EngineChoice
import dev.pabloi.whisper.engine.local.ModelCatalog
import dev.pabloi.whisper.engine.local.ModelDownloadService
import dev.pabloi.whisper.engine.local.ModelSpec
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState(initial = AppSettings())

    // Delegate all updates through the SettingsStore via the WhisprApp singleton.
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as dev.pabloi.whisper.WhisprApp
    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { app.settings.update(block) }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            update { it.copy(recordingsFolderUri = uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Engine", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.engine == EngineChoice.LOCAL,
                    onClick = { update { it.copy(engine = EngineChoice.LOCAL) } },
                    label = { Text("On-device NPU") }
                )
                FilterChip(
                    selected = settings.engine == EngineChoice.REMOTE,
                    onClick = { update { it.copy(engine = EngineChoice.REMOTE) } },
                    label = { Text("Remote") }
                )
            }

            if (settings.engine == EngineChoice.LOCAL) {
                Text("On-device model", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tiny / Base / Small are great for fast iteration. Turbo gives the best accuracy.",
                    style = MaterialTheme.typography.bodySmall
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelCatalog.specs.forEach { spec ->
                        ModelRow(
                            spec = spec,
                            selected = settings.localModelId == spec.id,
                            onSelect = { update { it.copy(localModelId = spec.id) } },
                            onDownload = { vm.startDownloadFor(spec.id) },
                            onDelete = {
                                app.repoFor(spec).clean()
                                // Force a recomposition: settings flow re-emits when the
                                // user toggles the radio, which is enough to re-query
                                // installed-state in the row composable.
                                update { it.copy(localModelId = it.localModelId) }
                            },
                        )
                    }
                }
            }

            if (settings.engine == EngineChoice.REMOTE) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Any OpenAI-compatible endpoint: OpenAI, faster-whisper-server, vLLM, RunPod.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = settings.remoteBaseUrl,
                            onValueChange = { v -> update { it.copy(remoteBaseUrl = v) } },
                            label = { Text("Base URL (e.g. https://api.openai.com)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = settings.remoteApiKey,
                            onValueChange = { v -> update { it.copy(remoteApiKey = v) } },
                            label = { Text("API key (optional for self-hosted)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = settings.remoteModelName,
                            onValueChange = { v -> update { it.copy(remoteModelName = v) } },
                            label = { Text("Model name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Text("Transcription", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = settings.language,
                onValueChange = { v -> update { it.copy(language = v) } },
                label = { Text("Language hint (e.g. en, es) — blank = auto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.timestamps,
                    onCheckedChange = { v -> update { it.copy(timestamps = v) } }
                )
                Spacer(Modifier.height(0.dp))
                Text("  Emit per-segment timestamps", modifier = Modifier.padding(start = 8.dp))
            }

            RecordingSection(
                settings = settings,
                onPickFolder = { folderLauncher.launch(null) },
                onSourceChanged = { p -> update { it.copy(audioSourcePreset = p) } },
                onEffectsChanged = { on -> update { it.copy(audioEffectsOn = on) } },
            )
        }
    }
}

@Composable
private fun RecordingSection(
    settings: AppSettings,
    onPickFolder: () -> Unit,
    onSourceChanged: (AudioSourcePreset) -> Unit,
    onEffectsChanged: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Recording", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Folder: ${if (settings.recordingsFolderUri.isEmpty()) "Not set" else settings.recordingsFolderUri}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onPickFolder) { Text("Choose folder…") }

            Spacer(Modifier.height(12.dp))
            Text("Audio source preset")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AudioSourcePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = preset == settings.audioSourcePreset,
                        onClick = { onSourceChanged(preset) },
                        label = { Text(preset.name) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.audioEffectsOn, onCheckedChange = onEffectsChanged)
                Spacer(Modifier.width(8.dp))
                Text("Apply system NS / AGC")
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.pabloi.whisper.WhisprApp
    val running by ModelDownloadService.running(spec.id).collectAsState()
    val progress by ModelDownloadService.progress(spec.id).collectAsState()
    val error by ModelDownloadService.error(spec.id).collectAsState()
    val installed = app.repoFor(spec).isInstalled()

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect, enabled = installed)
                Column(Modifier.padding(start = 4.dp)) {
                    Text(spec.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (installed) "Installed" else "${spec.approxSizeMb} MB · not installed",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (running) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                Text(progress.message, style = MaterialTheme.typography.labelSmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!installed) {
                        Button(onClick = onDownload) { Text("Download") }
                    } else {
                        OutlinedButton(onClick = onDelete) { Text("Delete") }
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
