package com.companion.ainotifications.dictation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DictationScreen() {
    val context = LocalContext.current
    val controller = remember { DictationController.get(context) }
    val state by controller.state.collectAsStateWithLifecycle()
    val connection by DictationAccessibilityService.connection.collectAsStateWithLifecycle()
    val readiness by DictationRecordingService.readiness.collectAsStateWithLifecycle()
    val settings = controller.settings
    val scope = rememberCoroutineScope()
    var microphoneGranted by remember { mutableStateOf(microphoneGranted(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphoneGranted = it
        if (it) DictationRecordingService.enableFromVisibleScreen(context)
    }
    var overlayEnabled by remember { mutableStateOf(settings.overlayEnabled) }
    var overlaySide by remember { mutableStateOf(settings.overlaySide) }
    var overlayOpacity by remember { mutableFloatStateOf(settings.overlayOpacity.toFloat()) }
    var backgroundAsr by remember { mutableStateOf(settings.backgroundAsr) }
    var inspectTranscription by remember { mutableStateOf(settings.inspectTranscription) }
    var asr by remember { mutableStateOf(settings.asr) }
    var cleanup by remember { mutableStateOf(settings.cleanup) }
    var connectionStatus by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var editedResult by remember(state.result) { mutableStateOf(state.result) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                microphoneGranted = microphoneGranted(context)
                overlayEnabled = settings.overlayEnabled
                overlaySide = settings.overlaySide
                overlayOpacity = settings.overlayOpacity.toFloat()
                DictationAccessibilityService.refreshOverlay()
                DictationRecordingService.enableFromVisibleScreen(context)
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            DictationRecordingService.enableFromVisibleScreen(context)
        }
        onDispose { lifecycle.removeObserver(observer) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title", contentType = "text") {
            Text("Private dictation", style = MaterialTheme.typography.headlineSmall)
        }
        item(key = "intro", contentType = "text") {
            Text("Use your current keyboard. Tap the floating mic, speak, then tap the checkmark to finish. With background transcription enabled, recorded audio goes to your PC while you speak. The complete transcript is optionally polished after Stop.")
        }
        item(key = "permissions", contentType = "card") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Microphone", style = MaterialTheme.typography.titleMedium)
                    Text(if (microphoneGranted) "Microphone permission granted" else "Microphone permission is required to record.")
                    if (!microphoneGranted) {
                        Button(onClick = { permissions.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text("Open Android app settings") }
                    }
                    Text("2. Floating controls and insertion", style = MaterialTheme.typography.titleMedium)
                    Text("Enabling Private dictation controls in Android Accessibility grants access to read and change on-screen text. It is used to find the original text field and insert your result. Password fields are excluded. Screen text stays on your phone; only recorded audio and optional cleanup text go to your PC. No audio or transcript logging is enabled.")
                    Text(if (connection != null) "Accessibility service connected" else "Accessibility service is not enabled")
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Open Accessibility settings")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Show floating microphone", Modifier.weight(1f))
                        Switch(checked = overlayEnabled, onCheckedChange = {
                            overlayEnabled = it; settings.overlayEnabled = it
                            if (it) DictationRecordingService.enableFromVisibleScreen(context)
                            else DictationRecordingService.disable(context)
                            DictationAccessibilityService.refreshOverlay()
                        })
                    }
                    Text(if (readiness.ready) (if (state.busy) "Dictation active" else "Dictation ready · microphone is off until you tap Mic.")
                        else readiness.error.ifBlank { "Enable floating microphone with microphone permission to prepare dictation." },
                        style = MaterialTheme.typography.bodySmall)
                    Text("A quiet Dictation ready notification keeps recording available without opening a window over your editor. After restarting the phone or stopping the service, open this screen once to prepare it again.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Dock microphone", Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("left", "right").forEach { side ->
                                FilterChip(selected = overlaySide == side, onClick = {
                                    overlaySide = side; settings.overlaySide = side
                                    DictationAccessibilityService.refreshOverlay()
                                }, label = { Text(side.replaceFirstChar { it.uppercase() }) })
                            }
                        }
                    }
                    Text("Background opacity: ${overlayOpacity.roundToInt()}%")
                    Slider(value = overlayOpacity, onValueChange = { overlayOpacity = it },
                        valueRange = 20f..100f, steps = 15,
                        onValueChangeFinished = { settings.overlayOpacity = overlayOpacity.roundToInt() })
                    Text("Lower opacity makes the light background more transparent. Default: 50%. Icons and text stay fully visible.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("The mic stays where you place it and fades in when a text field and keyboard are visible. Drag it to change its height or dock on the other side. During recording, Listening confirms the microphone is active; tap Polish to toggle cleanup, ✓ to finish, or × to cancel. Copy appears only if insertion fails.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item(key = "background_asr", contentType = "card") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Transcribe while speaking", Modifier.weight(1f))
                        Switch(checked = backgroundAsr, enabled = !state.busy, onCheckedChange = {
                            backgroundAsr = it; settings.backgroundAsr = it
                        })
                    }
                    Text("Uses speech detection and rolling audio windows of at most 8 seconds. Turn off to send one recording after Stop.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Show transcription details", Modifier.weight(1f))
                        Switch(checked = inspectTranscription, enabled = !state.busy, onCheckedChange = {
                            inspectTranscription = it; settings.inspectTranscription = it
                        })
                    }
                    Text("Enable before recording to inspect the last 24 ASR updates, stable words and changing endings. Details stay in app memory until the next recording or Cancel; nothing is written to logs or files.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item(key = "session", contentType = "card") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.phase.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Polish after Stop", Modifier.weight(1f))
                        Switch(checked = state.polished, onCheckedChange = controller::setPolished,
                            enabled = !state.busy || state.phase == DictationPhase.RECORDING)
                    }
                    if (state.phase == DictationPhase.RECORDING) {
                        Button(onClick = controller::stop) { Text("Stop recording") }
                    } else if (!state.busy) {
                        Button(onClick = {
                            val id = controller.prepare(null)
                            DictationRecordingService.startFromVisibleScreen(context, id)
                        }, enabled = microphoneGranted) { Text("Try dictation in this app") }
                    }
                    if (state.busy) TextButton(onClick = controller::cancel) { Text("Cancel") }
                    if (state.result.isNotBlank() && !state.busy) {
                        OutlinedTextField(value = editedResult, onValueChange = { editedResult = it },
                            label = { Text("Transcript · review or edit before copying") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
                        Row {
                            Button(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Dictation", editedResult))
                            }) { Text("Copy") }
                            if (state.result != state.original && state.original.isNotBlank()) {
                                TextButton(onClick = controller::useOriginal) { Text("Use original") }
                            }
                        }
                        Text("Copy explicitly places the transcript on the system clipboard.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.timings.isNotBlank()) Text(state.timings, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.streaming?.let { progress ->
            item(key = "streaming_summary", contentType = "text") {
                Text("ASR requests: ${progress.requests} · Responses: ${progress.completed} · " +
                    "Retries needed: ${progress.failures} · Committed parts: ${progress.sections} · Reused: ${progress.reused}\n" +
                    "Resolved audio: ${progress.finalizedSamples / 16} / ${progress.capturedSamples / 16} ms\n" +
                    "Window starts at ${progress.windowStartSample / 16} ms · Rolls: ${progress.rolls}\n" +
                    "Largest request: ${progress.maxRequestSamples / 16} ms\n" +
                    "${progress.detector} · Pauses detected: ${progress.detectedPauses}",
                    style = MaterialTheme.typography.bodySmall)
                if (progress.overlapping) Text("Checking the shared words before committing the next window.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.inspectTranscription && inspectTranscription) {
                item(key = "streaming_preview", contentType = "card") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Live transcription details", style = MaterialTheme.typography.titleMedium)
                            Text("Stable preview: ${progress.stable.ifBlank { "(waiting for agreement)" }}")
                            Text("Changing ending: ${progress.tentative.ifBlank { "(none)" }}")
                            Text("Stable preview means consecutive results agree; recent words and punctuation can still change. Pauses keep the same context. Parts are accepted after matching overlapping windows or Stop. Nothing is inserted until Stop.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                progress.history.forEach { update ->
                    item(key = "asr_update_${update.number}_${update.kind}", contentType = "text") {
                        Text("#${update.number} · ${update.rangeLabel} · ${update.kind} · ${update.durationMs} ms",
                            style = MaterialTheme.typography.labelLarge)
                        if (update.stable.isNotBlank()) Text("Stable: ${update.stable}")
                        if (update.tentative.isNotBlank()) Text("Changing: ${update.tentative}")
                    }
                }
            }
        }

        item(key = "connection_title", contentType = "text") {
            Text("PC connection", style = MaterialTheme.typography.titleLarge)
        }
        item(key = "connection_intro", contentType = "text") {
            Text("Both devices need Tailscale. These private HTTPS defaults match your current PC servers. Settings are separate from your notification agent.")
        }
        item(key = "asr", contentType = "endpoint") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EndpointFields("Speech recognition", asr, { asr = it }, !state.busy)
            }
        }
        item(key = "cleanup", contentType = "endpoint") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EndpointFields("Optional cleanup", cleanup, { cleanup = it }, !state.busy)
            }
        }
        item(key = "connection_actions", contentType = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    try {
                        DictationProtocol.baseUrl(asr.url)
                        DictationProtocol.baseUrl(cleanup.url)
                        require(asr.model.isNotBlank() && cleanup.model.isNotBlank()) { "Enter both model names." }
                        settings.asr = asr; settings.cleanup = cleanup
                        connectionStatus = "Dictation settings saved."
                    } catch (error: Exception) { connectionStatus = error.message.orEmpty() }
                }, enabled = !state.busy && !testing) { Text("Save settings") }
                OutlinedButton(onClick = {
                    testing = true; connectionStatus = "Testing configured servers…"
                    scope.launch {
                        try {
                            val client = DictationClient()
                            val asrResult = try { "ASR: ${client.test(asr)}" }
                                catch (error: CancellationException) { throw error }
                                catch (error: Exception) { "ASR: ${error.message}" }
                            val cleanupResult = try { "Cleanup: ${client.test(cleanup)}" }
                                catch (error: CancellationException) { throw error }
                                catch (error: Exception) { "Cleanup: ${error.message}" }
                            connectionStatus = "$asrResult\n$cleanupResult"
                        } finally { testing = false }
                    }
                }, enabled = !state.busy && !testing) { Text("Test connection") }
            }
        }
        if (connectionStatus.isNotBlank()) item(key = "connection_status", contentType = "text") { Text(connectionStatus) }
        item(key = "limits", contentType = "text") {
            Text("Recordings stay in memory and are limited to 5 minutes in this first version. The result stays available until the next dictation, Cancel, or app process exit.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun microphoneGranted(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun EndpointFields(title: String, value: ModelEndpoint, change: (ModelEndpoint) -> Unit, enabled: Boolean) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value.url, { change(value.copy(url = it)) }, label = { Text("Tailscale URL ending in /v1") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled)
    OutlinedTextField(value.model, { change(value.copy(model = it)) }, label = { Text("Model ID / alias") },
        modifier = Modifier.fillMaxWidth(), enabled = enabled)
    OutlinedTextField(value.apiKey, { change(value.copy(apiKey = it)) }, label = { Text("API key (if required)") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = enabled)
}
