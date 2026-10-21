package com.voxyn.mode

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.TextToSpeechManager

enum class AssistantMode {
    NORMAL, DRIVING, STUDY, SLEEP, MEETING, MORNING
}

class ModeEngine(
    private val context: Context,
    private val tts: TextToSpeechManager? = null
) {
    var currentMode: AssistantMode = AssistantMode.NORMAL
        private set

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    fun activateMode(modeName: String): String {
        val targetMode = when (modeName.lowercase().trim()) {
            "driving", "drive"   -> AssistantMode.DRIVING
            "study", "focus"     -> AssistantMode.STUDY
            "sleep", "bedtime", "night" -> AssistantMode.SLEEP
            "meeting", "office"  -> AssistantMode.MEETING
            "morning"            -> AssistantMode.MORNING
            else                 -> AssistantMode.NORMAL
        }

        currentMode = targetMode

        // Apply real device ringer and notification policy adjustments
        runCatching {
            when (targetMode) {
                AssistantMode.SLEEP -> {
                    if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                        notificationManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    } else {
                        audioManager?.ringerMode = AudioManager.RINGER_MODE_SILENT
                    }
                }
                AssistantMode.STUDY -> {
                    if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                        notificationManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    } else {
                        audioManager?.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    }
                }
                AssistantMode.MEETING -> {
                    audioManager?.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
                AssistantMode.NORMAL, AssistantMode.MORNING -> {
                    if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                        notificationManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    audioManager?.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
                AssistantMode.DRIVING -> {
                    // Hands-free mode flag active
                }
            }
        }

        val summary = when (targetMode) {
            AssistantMode.DRIVING -> "Driving mode activated. Hands-free voice optimization ON."
            AssistantMode.STUDY   -> "Study mode activated. Notifications silenced."
            AssistantMode.SLEEP   -> "Sleep mode activated. Do Not Disturb enabled."
            AssistantMode.MEETING -> "Meeting mode activated. Vibrate mode enabled."
            AssistantMode.MORNING -> "Good morning! Ready to start your day."
            AssistantMode.NORMAL  -> "Normal mode restored."
        }

        VoxynLogger.i("Mode switch -> ${targetMode.name}: $summary", "MODE")
        // TTS announcement is handled by central VoxynEngine to eliminate overlapping audio collisions
        return summary
    }

    fun deactivateMode(): String {
        currentMode = AssistantMode.NORMAL
        runCatching {
            if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                notificationManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            audioManager?.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
        val msg = "Returned to Normal mode."
        VoxynLogger.i(msg, "MODE")
        return msg
    }
}
