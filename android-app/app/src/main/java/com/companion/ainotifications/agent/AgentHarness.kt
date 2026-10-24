package com.companion.ainotifications.agent

import android.content.Context
import android.util.Log
import com.companion.ainotifications.data.ActionLog
import com.companion.ainotifications.data.AppPreferences
import com.companion.ainotifications.data.ChatCompletionRequest
import com.companion.ainotifications.data.ChatCompletionResponse
import com.companion.ainotifications.data.ChatMessage
import com.companion.ainotifications.data.ChatUiMessage
import com.companion.ainotifications.data.NotificationEvent
import com.companion.ainotifications.tools.PhoneActionExecutor
import com.companion.ainotifications.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AgentHarness private constructor(context: Context) {

    private val appContext = context.applicationContext
    val prefs = AppPreferences(appContext)
    val memoryManager = MemoryManager(appContext)
    private val actionExecutor = PhoneActionExecutor(appContext)
    val toolRegistry = ToolRegistry(actionExecutor, memoryManager)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _recentLogs = MutableStateFlow<List<ActionLog>>(emptyList())
    val recentLogs: StateFlow<List<ActionLog>> = _recentLogs.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatUiMessage>>(
        listOf(
            ChatUiMessage(
                role = "assistant",
                content = "Hey Hasan! I'm your AI companion. Tell me what notifications matter to you, ask me to remember facts, or set alarms."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatUiMessage>> = _chatMessages.asStateFlow()

    private val _isChatSending = MutableStateFlow(false)
    val isChatSending: StateFlow<Boolean> = _isChatSending.asStateFlow()

    companion object {
        private const val TAG = "AgentHarness"
        @Volatile
        private var instance: AgentHarness? = null

        fun getInstance(context: Context): AgentHarness {
            return instance ?: synchronized(this) {
                instance ?: AgentHarness(context).also { instance = it }
            }
        }
    }

    suspend fun processNotification(event: NotificationEvent) = withContext(Dispatchers.IO) {
        if (!prefs.isServiceActive) {
            Log.d(TAG, "AI processing skipped - service paused")
            return@withContext
        }

        _isProcessing.value = true
        try {
            val memory = memoryManager.loadMemory()
            val systemPrompt = PromptTemplates.buildSystemPrompt(memory)
            val userPrompt = PromptTemplates.buildUserPrompt(
                appName = event.appName,
                packageName = event.packageName,
                title = event.title,
                text = event.text
            )

            val conversation = mutableListOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            )

            // Agent Loop (mirrors harness.py run_agent)
            var turns = 0
            val maxTurns = 5

            while (turns < maxTurns) {
                turns++
                val requestPayload = ChatCompletionRequest(
                    model = prefs.modelName,
                    messages = conversation,
                    tools = toolRegistry.toolSchemas
                )

                val requestJson = json.encodeToString(requestPayload)
                val cleanUrl = prefs.serverUrl.trimEnd('/') + "/chat/completions"

                val httpRequest = Request.Builder()
                    .url(cleanUrl)
                    .addHeader("Authorization", "Bearer ${prefs.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorLog = ActionLog(
                        appName = event.appName,
                        notificationTitle = event.title,
                        toolName = "HTTP Error",
                        result = "Code ${response.code}: $responseBody",
                        isUrgent = false
                    )
                    addLog(errorLog)
                    break
                }

                val completion = json.decodeFromString<ChatCompletionResponse>(responseBody)
                val choice = completion.choices.firstOrNull() ?: break
                val assistantMessage = choice.message

                val toolCalls = assistantMessage.toolCalls
                if (toolCalls.isNullOrEmpty()) {
                    // Assistant finished without further tool calls
                    if (!assistantMessage.content.isNullOrBlank()) {
                        Log.i(TAG, "Agent thought: ${assistantMessage.content}")
                    }
                    break
                }

                // Append assistant tool call request
                conversation.add(assistantMessage)

                // Execute each tool call
                for (call in toolCalls) {
                    val fnName = call.function.name
                    val fnArgs = call.function.arguments
                    Log.i(TAG, "Executing tool: $fnName($fnArgs)")

                    val result = toolRegistry.executeTool(fnName, fnArgs)

                    val isUrgent = fnName == "urgent_alert"
                    addLog(
                        ActionLog(
                            appName = event.appName,
                            notificationTitle = event.title,
                            toolName = fnName,
                            result = result,
                            isUrgent = isUrgent
                        )
                    )

                    // Append tool result message
                    conversation.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = call.id,
                            content = result
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            addLog(
                ActionLog(
                    appName = event.appName,
                    notificationTitle = event.title,
                    toolName = "Exception",
                    result = e.message ?: "Unknown error",
                    isUrgent = false
                )
            )
        } finally {
            _isProcessing.value = false
        }
    }

    suspend fun pingServer(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val cleanUrl = prefs.serverUrl.trimEnd('/') + "/models"
            val request = Request.Builder()
                .url(cleanUrl)
                .addHeader("Authorization", "Bearer ${prefs.apiKey}")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            if (response.isSuccessful) {
                Pair(true, "Connected (${latency}ms)")
            } else {
                Pair(false, "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Offline"
            val shortMsg = if (msg.length > 20) msg.take(18) + "…" else msg
            Pair(false, shortMsg)
        }
    }

    suspend fun sendUserChatMessage(userText: String) = withContext(Dispatchers.IO) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return@withContext

        // Append user message immediately to UI state
        val userMsg = ChatUiMessage(role = "user", content = trimmed)
        _chatMessages.value = _chatMessages.value + userMsg
        _isChatSending.value = true

        val executedTools = mutableListOf<String>()

        try {
            val memory = memoryManager.loadMemory()
            val systemPrompt = PromptTemplates.buildCompanionChatSystemPrompt(memory)

            val conversation = mutableListOf(
                ChatMessage(role = "system", content = systemPrompt)
            )

            // Keep recent chat turns for context
            val recentHistory = _chatMessages.value.takeLast(8)
            for (msg in recentHistory) {
                conversation.add(ChatMessage(role = msg.role, content = msg.content))
            }

            var turns = 0
            val maxTurns = 5
            var finalAssistantReply = ""

            while (turns < maxTurns) {
                turns++
                val requestPayload = ChatCompletionRequest(
                    model = prefs.modelName,
                    messages = conversation,
                    tools = toolRegistry.toolSchemas
                )

                val requestJson = json.encodeToString(requestPayload)
                val cleanUrl = prefs.serverUrl.trimEnd('/') + "/chat/completions"

                val httpRequest = Request.Builder()
                    .url(cleanUrl)
                    .addHeader("Authorization", "Bearer ${prefs.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    finalAssistantReply = "Model error (${response.code}): $responseBody"
                    break
                }

                val completion = json.decodeFromString<ChatCompletionResponse>(responseBody)
                val choice = completion.choices.firstOrNull() ?: break
                val assistantMessage = choice.message

                val toolCalls = assistantMessage.toolCalls
                if (toolCalls.isNullOrEmpty()) {
                    finalAssistantReply = assistantMessage.content ?: "Done!"
                    break
                }

                // If tool calls were made by the model:
                conversation.add(assistantMessage)

                for (call in toolCalls) {
                    val fnName = call.function.name
                    val fnArgs = call.function.arguments
                    val toolResult = toolRegistry.executeTool(fnName, fnArgs)
                    executedTools.add("$fnName: $toolResult")

                    // Also log to activity stream
                    addLog(
                        ActionLog(
                            appName = "Companion Chat",
                            notificationTitle = "User prompt: '$trimmed'",
                            toolName = fnName,
                            result = toolResult,
                            isUrgent = false
                        )
                    )

                    conversation.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = call.id,
                            content = toolResult
                        )
                    )
                }
            }

            if (finalAssistantReply.isBlank()) {
                finalAssistantReply = if (executedTools.isNotEmpty()) {
                    "Done! " + executedTools.joinToString("; ")
                } else {
                    "Understood!"
                }
            }

            _chatMessages.value = _chatMessages.value + ChatUiMessage(
                role = "assistant",
                content = finalAssistantReply,
                toolExecutions = executedTools
            )

        } catch (e: Exception) {
            _chatMessages.value = _chatMessages.value + ChatUiMessage(
                role = "assistant",
                content = "Error: ${e.message}"
            )
        } finally {
            _isChatSending.value = false
        }
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            ChatUiMessage(
                role = "assistant",
                content = "Chat cleared! Ask me anything, tell me what to remember, or set an alarm."
            )
        )
    }

    private fun addLog(log: ActionLog) {
        val updated = listOf(log) + _recentLogs.value.take(49)
        _recentLogs.value = updated
    }

    fun clearLogs() {
        _recentLogs.value = emptyList()
    }
}
