package com.companion.ainotifications.dictation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import java.io.File

/** Shell-only: a marked synthetic insertion in an empty Notes editor, no microphone. */
class DictationInsertionCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val report = File(context.filesDir, "dictation-insertion-check.txt")
            try {
                report.writeText("Synthetic insertion sanity check; no microphone\n")
                val controller = DictationController.get(context)
                check(!controller.state.value.busy) { "A dictation is already running" }
                if (intent.getBooleanExtra("network_only", false)) {
                    val client = DictationClient()
                    client.test(controller.settings.asr)
                    client.test(controller.settings.cleanup)
                    val cleaned = client.polish(controller.settings.cleanup, "synthetic dictation check for tomorrow")
                    check(cleaned.contains("synthetic", ignoreCase = true) && cleaned.contains("tomorrow", ignoreCase = true))
                    report.appendText("SUCCESS Phone ASR/cleanup routes and non-thinking cleanup request; synthetic text only\n")
                    return@launch
                }
                val service = DictationAccessibilityService.connection.value ?: error("Accessibility disconnected")
                val sample = "Synthetic dictation check.\n\nSecond paragraph."
                report.appendText(service.checkSyntheticInsertion("com.coloros.note", sample) + "\n")
            } catch (error: Exception) {
                report.appendText("FAIL ${error.message}\n")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
