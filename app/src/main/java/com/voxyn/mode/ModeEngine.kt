package com.voxyn.mode

import android.content.Context
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.TextToSpeechManager

enum class AssistantMode {
    NORMAL, DRIVING, STUDY, SLEEP, MEETING, MORNING
}

class ModeEngine(
    private val context: Context,
    private val tts: TextToSpeechManager
) {
    var currentMode: AssistantMode = AssistantMode.NORMAL
        private set

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
        val summary = when (targetMode) {
            AssistantMode.DRIVING -> "Driving mode activated. Hands-free voice optimization ON."
            AssistantMode.STUDY   -> "Study mode activated. Notifications silenced."
            AssistantMode.SLEEP   -> "Sleep mode activated. Do Not Disturb enabled."
            AssistantMode.MEETING -> "Meeting mode activated. Vibrate mode enabled."
            AssistantMode.MORNING -> "Good morning! Ready to start your day."
            AssistantMode.NORMAL  -> "Normal mode restored."
        }

        VoxynLogger.i("Mode switch -> ${targetMode.name}: $summary", "MODE")
        tts.speak(summary)
        return summary
    }

    fun deactivateMode(): String {
        currentMode = AssistantMode.NORMAL
        val msg = "Returned to Normal mode."
        VoxynLogger.i(msg, "MODE")
        tts.speak(msg)
        return msg
    }
}
