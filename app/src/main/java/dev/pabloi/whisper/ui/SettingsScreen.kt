package dev.pabloi.whisper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import dev.pabloi.whisper.data.EngineChoice
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
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.pabloi.whisper.WhisprApp
    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { app.settings.update(block) }
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
        }
    }
}
