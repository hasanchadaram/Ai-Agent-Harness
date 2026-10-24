package com.companion.ainotifications.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.companion.ainotifications.agent.AgentHarness
import com.companion.ainotifications.data.ActionLog
import com.companion.ainotifications.data.ChatUiMessage
import com.companion.ainotifications.data.NotificationEvent
import com.companion.ainotifications.ui.theme.AiNotificationsTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            AiNotificationsTheme {
                MainScreen()
            }
        }
    }
}

fun isNotificationListenerGranted(context: Context): Boolean {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledListeners.contains(context.packageName)
}

enum class AppTab(val title: String, val icon: ImageVector) {
    FEED("Activity Feed", Icons.Default.Notifications),
    CHAT("Companion Chat", Icons.Default.Chat)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val harness = remember { AgentHarness.getInstance(context) }
    val scope = rememberCoroutineScope()

    var isListenerGranted by remember { mutableStateOf(isNotificationListenerGranted(context)) }

    // Auto-refresh permission state whenever the user returns to the app from Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerGranted = isNotificationListenerGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var selectedTab by remember { mutableStateOf(AppTab.FEED) }
    val isProcessing by harness.isProcessing.collectAsState()

    var showMemoryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showReferenceDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectedTab == AppTab.FEED) "AI Notification Harness" else "AI Companion Chat",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isProcessing) "⚡ Agent processing notification..." else if (selectedTab == AppTab.FEED) "Idle - Listening to phone notifications" else "Ask questions or teach memory",
                            fontSize = 12.sp,
                            color = if (isProcessing) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                },
                actions = {
                    if (selectedTab == AppTab.CHAT) {
                        IconButton(onClick = { harness.clearChat() }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Chat")
                        }
                    }
                    IconButton(onClick = { showReferenceDialog = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Capabilities & Guide")
                    }
                    IconButton(onClick = { showMemoryDialog = true }) {
                        Icon(Icons.Default.Psychology, contentDescription = "Memory")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == AppTab.FEED) {
                NotificationFeedContent(
                    harness = harness,
                    isListenerGranted = isListenerGranted,
                    onGrantPermission = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            } else {
                CompanionChatContent(harness = harness)
            }
        }
    }

    // Memory Dialog
    if (showMemoryDialog) {
        var memoryText by remember { mutableStateOf(harness.memoryManager.loadMemory()) }

        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("Long-Term Memory (memory.md)") },
            text = {
                Column {
                    Text(
                        text = "This memory is injected into the AI system prompt on every notification and chat.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = memoryText,
                        onValueChange = { memoryText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    harness.memoryManager.updateMemory(memoryText)
                    showMemoryDialog = false
                }) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMemoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        var tempUrl by remember { mutableStateOf(harness.prefs.serverUrl) }
        var tempModel by remember { mutableStateOf(harness.prefs.modelName) }
        var tempApiKey by remember { mutableStateOf(harness.prefs.apiKey) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Harness Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        label = { Text("Tailscale Model URL") },
                        placeholder = { Text("http://100.x.x.x:8080/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempModel,
                        onValueChange = { tempModel = it },
                        label = { Text("Model Name") },
                        placeholder = { Text("qwen3.5:4b") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = { tempApiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("llama") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    harness.prefs.serverUrl = tempUrl
                    harness.prefs.modelName = tempModel
                    harness.prefs.apiKey = tempApiKey
                    showSettingsDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Architecture & Capabilities Guide Dialog
    if (showReferenceDialog) {
        AlertDialog(
            onDismissRequest = { showReferenceDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Capabilities & Guide", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Section 1: Triggering Points
                    Column {
                        Text(
                            text = "⚡ TRIGGERING POINTS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Real-Time Notifications: Triggers automatically when apps (WhatsApp, Gmail, Calendar, Slack, SMS) post a notification.\n" +
                                    "• Smart Filters: Automatically ignores self-notifications (no loops), ongoing music/downloads, and empty notifications.\n" +
                                    "• Companion Chat: Direct conversation where you can tell the AI to save memory or set alarms.\n" +
                                    "• Manual Simulations: Tap any simulation chip to test scenarios immediately.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    HorizontalDivider()

                    // Section 2: Context & Sessions
                    Column {
                        Text(
                            text = "🔄 CONTEXT & SESSIONS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Fresh Isolated Sessions: Each notification starts a lean, isolated session (up to 5 tool steps). Your model's context NEVER fills up or overflows.\n" +
                                    "• Long-Term Memory (memory.md): Lasting facts are saved permanently. On every new notification and chat message, the latest memory is injected into the prompt so the AI always remembers you.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    HorizontalDivider()

                    // Section 3: Current Capabilities / Tools
                    Column {
                        Text(
                            text = "🛠️ WHAT IT CAN DO (TOOLS)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⏰ schedule_alarm\n" +
                                    "Parses times from messages ('flight at 7:15 PM', 'meeting at 4:30 PM') and sets native Android alarms.\n\n" +
                                    "⚠️ urgent_alert\n" +
                                    "Triggers an immediate 6-pulse vibration sequence and high-priority heads-up notification for outages or emergencies.\n\n" +
                                    "📋 push_smart_briefing\n" +
                                    "Generates a concise 1-2 sentence quick summary notification directly to your phone.\n\n" +
                                    "🧠 save_memory\n" +
                                    "Learns lasting personal facts about you, your family, or your routine and saves them to memory.md.\n\n" +
                                    "🔕 ignore_notification\n" +
                                    "Silently discards marketing spam, OTPs, or chit-chat without bothering you.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    HorizontalDivider()

                    // Section 4: Upcoming Additions
                    Column {
                        Text(
                            text = "🚀 UPCOMING EXTENSIONS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Google Calendar event insertion.\n" +
                                    "• Spoken Text-to-Speech (TTS) briefings.\n" +
                                    "• Smart auto-reply to chat notifications.\n" +
                                    "• Remote MCP tool calling over SSE/HTTP.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showReferenceDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

@Composable
fun NotificationFeedContent(
    harness: AgentHarness,
    isListenerGranted: Boolean,
    onGrantPermission: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isServiceActive by remember { mutableStateOf(harness.prefs.isServiceActive) }

    var pingStatus by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    val recentLogs by harness.recentLogs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Permission Warning Banner
        if (!isListenerGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "⚠️ Notification Access Required",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Grant permission so the local AI agent can read incoming notifications and make decisions.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onGrantPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Access in Settings")
                    }
                }
            }
        }

        // Connection & Quick Controls Card
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model: ${harness.prefs.modelName}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = harness.prefs.serverUrl,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Switch(
                        checked = isServiceActive,
                        onCheckedChange = {
                            isServiceActive = it
                            harness.prefs.isServiceActive = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            isPinging = true
                            scope.launch {
                                val (success, message) = harness.pingServer()
                                isPinging = false
                                pingStatus = if (success) "✅ $message" else "❌ $message"
                            }
                        },
                        enabled = !isPinging,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(if (isPinging) "Testing..." else "Test Connection")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    if (pingStatus != null) {
                        Text(
                            text = pingStatus!!,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Test Simulation Bar
        Text(
            text = "Simulate Notification:",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {
                    scope.launch {
                        harness.processNotification(
                            NotificationEvent(
                                id = "test_meeting_${System.currentTimeMillis()}",
                                packageName = "com.google.android.calendar",
                                appName = "Google Calendar",
                                title = "Team Sync at 4:30 PM",
                                text = "Video call starting at 4:30 PM today with Engineering."
                            )
                        )
                    }
                },
                label = { Text("Meeting Alarm", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(14.dp)) }
            )

            AssistChip(
                onClick = {
                    scope.launch {
                        harness.processNotification(
                            NotificationEvent(
                                id = "test_urgent_${System.currentTimeMillis()}",
                                packageName = "com.google.android.gm",
                                appName = "Gmail",
                                title = "CRITICAL: Server Outage",
                                text = "Alert: Production database disk usage > 98%. Immediate action required."
                            )
                        )
                    }
                },
                label = { Text("Urgent Alert", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp)) }
            )

            AssistChip(
                onClick = {
                    scope.launch {
                        harness.processNotification(
                            NotificationEvent(
                                id = "test_flight_${System.currentTimeMillis()}",
                                packageName = "com.whatsapp",
                                appName = "WhatsApp",
                                title = "Mom",
                                text = "Hey Hasan, your sister's flight lands at 7:15 PM tomorrow evening."
                            )
                        )
                    }
                },
                label = { Text("Memory & Brief", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(14.dp)) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Activity Feed Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Agent Action Stream (${recentLogs.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            if (recentLogs.isNotEmpty()) {
                TextButton(onClick = { harness.clearLogs() }) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
        }

        // Activity Log List
        if (recentLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.NotificationsNone,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No notifications processed yet", color = Color.Gray)
                    Text("Send a test notification or receive one via WhatsApp/Gmail", fontSize = 12.sp, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(recentLogs, key = { it.id }) { log ->
                    ActionLogCard(log)
                }
            }
        }
    }
}

@Composable
fun CompanionChatContent(
    harness: AgentHarness
) {
    val chatMessages by harness.chatMessages.collectAsState()
    val isSending by harness.isChatSending.collectAsState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(chatMessages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }

            if (isSending) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI is thinking & checking tools...",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        // Suggestion chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SuggestionChip(
                onClick = { inputText = "Remember that " },
                label = { Text("💡 Remember...", fontSize = 11.sp) }
            )
            SuggestionChip(
                onClick = { inputText = "Only alert me when " },
                label = { Text("🔔 Notification rule", fontSize = 11.sp) }
            )
            SuggestionChip(
                onClick = { inputText = "Set an alarm for " },
                label = { Text("⏰ Set alarm", fontSize = 11.sp) }
            )
        }

        // Input row
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask or teach your AI companion...", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val toSend = inputText
                        inputText = ""
                        scope.launch {
                            harness.sendUserChatMessage(toSend)
                        }
                    },
                    enabled = inputText.isNotBlank() && !isSending
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank() && !isSending) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}

fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.lines()
        lines.forEachIndexed { index, line ->
            var trimmedLine = line
            var isHeader = false
            if (trimmedLine.startsWith("### ")) {
                trimmedLine = trimmedLine.removePrefix("### ")
                isHeader = true
            } else if (trimmedLine.startsWith("## ")) {
                trimmedLine = trimmedLine.removePrefix("## ")
                isHeader = true
            } else if (trimmedLine.startsWith("# ")) {
                trimmedLine = trimmedLine.removePrefix("# ")
                isHeader = true
            } else if (trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ")) {
                trimmedLine = "• " + trimmedLine.substring(2)
            }

            val pattern = Regex("""(\*\*\*(.+?)\*\*\*)|(\*\*(.+?)\*\*)|(\*(.+?)\*)|(`(.+?)`)|(_(.+?)_)""")
            var cursor = 0
            val lineStart = length

            for (match in pattern.findAll(trimmedLine)) {
                if (match.range.first > cursor) {
                    append(trimmedLine.substring(cursor, match.range.first))
                }
                val fullMatch = match.value
                when {
                    fullMatch.startsWith("***") && fullMatch.endsWith("***") -> {
                        val content = match.groupValues[2]
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(content)
                        }
                    }
                    fullMatch.startsWith("**") && fullMatch.endsWith("**") -> {
                        val content = match.groupValues[4]
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(content)
                        }
                    }
                    fullMatch.startsWith("*") && fullMatch.endsWith("*") -> {
                        val content = match.groupValues[6]
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(content)
                        }
                    }
                    fullMatch.startsWith("`") && fullMatch.endsWith("`") -> {
                        val content = match.groupValues[8]
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.08f))) {
                            append(content)
                        }
                    }
                    fullMatch.startsWith("_") && fullMatch.endsWith("_") -> {
                        val content = match.groupValues[10]
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(content)
                        }
                    }
                    else -> append(fullMatch)
                }
                cursor = match.range.last + 1
            }

            if (cursor < trimmedLine.length) {
                append(trimmedLine.substring(cursor))
            }

            if (isHeader) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp), lineStart, length)
            }

            if (index < lines.size - 1) {
                append("\n")
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatUiMessage) {
    val isUser = msg.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = parseMarkdown(msg.content),
                    fontSize = 14.sp,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (msg.toolExecutions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        msg.toolExecutions.forEach { toolExec ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF34A853),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = toolExec,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionLogCard(log: ActionLog) {
    val timeFormatted = remember(log.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    }

    val (badgeColor, badgeIcon) = when (log.toolName) {
        "urgent_alert" -> Pair(Color(0xFFEA4335), Icons.Default.Warning)
        "schedule_alarm" -> Pair(Color(0xFFFBBC04), Icons.Default.Alarm)
        "push_smart_briefing" -> Pair(Color(0xFF4285F4), Icons.Default.Info)
        "save_memory" -> Pair(Color(0xFF34A853), Icons.Default.Psychology)
        "ignore_notification" -> Pair(Color.Gray, Icons.Default.VisibilityOff)
        else -> Pair(MaterialTheme.colorScheme.primary, Icons.Default.Code)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(badgeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = badgeIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.toolName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = timeFormatted,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${log.appName}: ${log.notificationTitle}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = parseMarkdown(log.result),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
