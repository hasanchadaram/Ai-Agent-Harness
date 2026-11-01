package com.companion.ainotifications.dictation

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.companion.ainotifications.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DictationReadiness(val ready: Boolean = false, val error: String = "")

/** Armed from a resumed app screen. Overlay taps never launch an activity or pause the editor. */
class DictationRecordingService : Service() {
    private var recordingId = ""
    private var foreground = false
    private var destroying = false
    private val controller get() = DictationController.get(this)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                controller.settings.overlayEnabled = false
                controller.cancel()
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_STOP -> { controller.stop(); return START_STICKY }
            ACTION_CANCEL -> { controller.cancel(); return START_STICKY }
        }
        try {
            if (intent == null || intent.action == ACTION_READY) {
                if (!controller.settings.overlayEnabled && !controller.state.value.busy) {
                    shutdown(); return START_NOT_STICKY
                }
                if (!foreground) promote(active = controller.state.value.busy)
                return START_STICKY
            }
            val id = intent.getStringExtra(EXTRA_ID) ?: return START_STICKY
            if (controller.state.value.id != id || controller.state.value.phase != DictationPhase.STARTING) {
                if (!foreground) shutdown()
                return START_NOT_STICKY
            }
            recordingId = id
            if (!foreground) promote(active = true) else showNotification(active = true)
            controller.start(id) { finishSession() }
        } catch (error: Exception) {
            status.value = DictationReadiness(error = "Open Dictation to enable microphone controls. ${error.javaClass.simpleName}")
            controller.startupFailed(recordingId, "Cannot start microphone service. Open Dictation and check microphone permission.")
            shutdown()
        }
        return if (controller.settings.overlayEnabled) START_STICKY else START_NOT_STICKY
    }

    private fun promote(active: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Private voice dictation", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION, notification(active),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        foreground = true
        status.value = DictationReadiness(ready = true)
    }

    private fun notification(active: Boolean): Notification {
        fun action(request: Int, name: String) = PendingIntent.getService(this, request,
            Intent(this, DictationRecordingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("open_dictation", true), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (active) "Private dictation active" else "Dictation ready")
            .setContentText(if (active) "Recording or processing. Stop or cancel here."
                else "Microphone is off. Tap the floating mic to dictate.")
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
        if (active) {
            builder.addAction(android.R.drawable.ic_media_pause, "Stop recording", action(1, ACTION_STOP))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", action(2, ACTION_CANCEL))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", action(3, ACTION_DISABLE))
        }
        return builder.build()
    }

    private fun showNotification(active: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(active))
    }

    private fun finishSession() {
        recordingId = ""
        if (destroying) return
        if (controller.settings.overlayEnabled && foreground) showNotification(active = false) else shutdown()
    }

    private fun shutdown() {
        foreground = false
        status.value = status.value.copy(ready = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroying = true
        foreground = false
        status.value = status.value.copy(ready = false)
        controller.serviceDestroyed(recordingId)
        super.onDestroy()
    }

    companion object {
        private val status = MutableStateFlow(DictationReadiness())
        val readiness = status.asStateFlow()
        val isReady get() = status.value.ready
        const val EXTRA_ID = "recording_id"
        const val ACTION_STOP = "com.companion.ainotifications.dictation.STOP"
        const val ACTION_CANCEL = "com.companion.ainotifications.dictation.CANCEL"
        private const val ACTION_READY = "com.companion.ainotifications.dictation.READY"
        private const val ACTION_DISABLE = "com.companion.ainotifications.dictation.DISABLE"
        private const val CHANNEL = "private_dictation"
        private const val NOTIFICATION = 4301

        /** Call only from a resumed screen: Android requires foreground microphone eligibility. */
        fun enableFromVisibleScreen(context: Context) {
            val controller = DictationController.get(context)
            if (isReady || !controller.settings.overlayEnabled ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, DictationRecordingService::class.java).setAction(ACTION_READY))
            } catch (error: Exception) {
                status.value = DictationReadiness(error = "Cannot enable dictation. ${error.javaClass.simpleName}")
            }
        }

        /** An overlay may use only an already-armed service; never steal focus with a fallback activity. */
        fun startExistingSession(context: Context, id: String) {
            val controller = DictationController.get(context)
            if (!isReady) {
                controller.startupFailed(id, "Open AI Notifications → Dictation once to enable microphone controls, then return to your editor.")
                return
            }
            try {
                context.startService(Intent(context, DictationRecordingService::class.java).putExtra(EXTRA_ID, id))
            } catch (error: Exception) {
                controller.startupFailed(id, "Dictation is not ready. Open Dictation and enable microphone controls.")
            }
        }

        fun startFromVisibleScreen(context: Context, id: String) {
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, DictationRecordingService::class.java).putExtra(EXTRA_ID, id))
            } catch (error: Exception) {
                DictationController.get(context).startupFailed(id, "Cannot start microphone. Check permission in Dictation.")
            }
        }

        fun disable(context: Context) {
            DictationController.get(context).cancel()
            context.stopService(Intent(context, DictationRecordingService::class.java))
        }
    }
}
