package com.companion.ainotifications.tools

import com.companion.ainotifications.agent.MemoryManager
import com.companion.ainotifications.data.FunctionDefinition
import com.companion.ainotifications.data.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ToolRegistry(
    private val actionExecutor: PhoneActionExecutor,
    private val memoryManager: MemoryManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    val toolSchemas: List<ToolDefinition> = listOf(
        // Tool: schedule_alarm
        ToolDefinition(
            function = FunctionDefinition(
                name = "schedule_alarm",
                description = "Set an alarm on the user's phone for a specific time with a label. Use when a notification mentions a specific time, meeting, flight, or reminder.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("hour") {
                            put("type", "integer")
                            put("description", "Hour of the day in 24-hour format (0-23)")
                        }
                        putJsonObject("minute") {
                            put("type", "integer")
                            put("description", "Minute of the hour (0-59)")
                        }
                        putJsonObject("label") {
                            put("type", "string")
                            put("description", "Short descriptive label for the alarm")
                        }
                    }
                    put("required", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("hour"))
                        add(kotlinx.serialization.json.JsonPrimitive("minute"))
                        add(kotlinx.serialization.json.JsonPrimitive("label"))
                    })
                }
            )
        ),

        // Tool: push_smart_briefing
        ToolDefinition(
            function = FunctionDefinition(
                name = "push_smart_briefing",
                description = "Push a concise, summarized notification briefing to the user's phone when information is useful or noteworthy.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") {
                            put("type", "string")
                            put("description", "Short, clear notification title (e.g. 'Flight Delayed 30m')")
                        }
                        putJsonObject("summary") {
                            put("type", "string")
                            put("description", "1-2 sentence quick summary with the critical info")
                        }
                        putJsonObject("urgency") {
                            put("type", "string")
                            put("description", "Urgency level: 'normal' or 'high'")
                        }
                    }
                    put("required", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("title"))
                        add(kotlinx.serialization.json.JsonPrimitive("summary"))
                    })
                }
            )
        ),

        // Tool: urgent_alert
        ToolDefinition(
            function = FunctionDefinition(
                name = "urgent_alert",
                description = "Trigger an immediate high-priority alert with distinctive vibration and sound. Use ONLY for emergencies, critical VIP alerts, security breaches, or urgent financial alerts.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") {
                            put("type", "string")
                            put("description", "What requires immediate attention")
                        }
                        putJsonObject("reason") {
                            put("type", "string")
                            put("description", "Why this is critical and requires immediate action")
                        }
                    }
                    put("required", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("title"))
                        add(kotlinx.serialization.json.JsonPrimitive("reason"))
                    })
                }
            )
        ),

        // Tool: save_memory
        ToolDefinition(
            function = FunctionDefinition(
                name = "save_memory",
                description = "Save one short lasting fact about the user or their life to long-term memory. Use whenever you learn lasting facts: preferences, projects, relationships, doctor names, flight numbers.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("fact") {
                            put("type", "string")
                            put("description", "The concise fact to remember permanently")
                        }
                    }
                    put("required", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("fact"))
                    })
                }
            )
        ),

        // Tool: ignore_notification
        ToolDefinition(
            function = FunctionDefinition(
                name = "ignore_notification",
                description = "Silently ignore this notification because it is promotional spam, marketing, chat chit-chat, or unimportant routine noise.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("reason") {
                            put("type", "string")
                            put("description", "Why this notification should be ignored")
                        }
                    }
                    put("required", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("reason"))
                    })
                }
            )
        )
    )

    fun executeTool(name: String, argumentsJson: String): String {
        return try {
            val root = json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject

            when (name) {
                "schedule_alarm" -> {
                    val hour = root["hour"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8
                    val minute = root["minute"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val label = root["label"]?.jsonPrimitive?.content ?: "Reminder"
                    actionExecutor.scheduleAlarm(hour, minute, label)
                }

                "push_smart_briefing" -> {
                    val title = root["title"]?.jsonPrimitive?.content ?: "AI Briefing"
                    val summary = root["summary"]?.jsonPrimitive?.content ?: ""
                    val urgency = root["urgency"]?.jsonPrimitive?.content ?: "normal"
                    actionExecutor.pushSmartBriefing(title, summary, urgency)
                }

                "urgent_alert" -> {
                    val title = root["title"]?.jsonPrimitive?.content ?: "Urgent Alert"
                    val reason = root["reason"]?.jsonPrimitive?.content ?: "Immediate attention needed"
                    actionExecutor.triggerUrgentAlert(title, reason)
                }

                "save_memory" -> {
                    val fact = root["fact"]?.jsonPrimitive?.content ?: ""
                    memoryManager.saveMemory(fact)
                }

                "ignore_notification" -> {
                    val reason = root["reason"]?.jsonPrimitive?.content ?: "Low priority"
                    "Ignored: $reason"
                }

                else -> "Error: Unknown tool '$name'"
            }
        } catch (e: Exception) {
            "Tool execution error: ${e.message}"
        }
    }
}
