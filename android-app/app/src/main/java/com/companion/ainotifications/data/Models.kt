package com.companion.ainotifications.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage
)

data class NotificationEvent(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ActionLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val notificationTitle: String,
    val toolName: String,
    val result: String,
    val isUrgent: Boolean = false
)

data class ChatUiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" or "assistant"
    val content: String,
    val toolExecutions: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

