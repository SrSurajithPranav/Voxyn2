package com.voxyn.perception

import com.voxyn.utils.VoxynLogger

/**
 * SpeechNormalizationLayer — turns raw STT transcripts into a canonical request.
 *
 * Vosk's small offline model is not authoritative: the user's speech
 * pronunciation (rhotacism) makes it hear "you do" for YouTube, "hunger books"
 * for HungerBox, etc. This layer fixes known variants against the catalog of
 * apps actually installed on the device before intent routing.
 *
 * Pipeline: raw STT → text normalization → app/entity correction → user-specific
 * variants → canonical request.
 */
class SpeechNormalizer(private val appNameResolver: AppNameResolver) {

    fun normalize(raw: String): String {
        val original = raw.trim().lowercase()
        var t = original

        // Vosk emits [unk] for out-of-vocabulary words; they only confuse intent
        // matching ("whats the time in [unk]"). Drop them before anything else.
        t = t.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()

        // Wake-word residue
        t = t.replace(Regex("^\\s*(hey|hi|okay)\\s+voxyn[,.!]?\\s*"), "")
        t = t.replace(Regex("^\\s*voxyn[,.!]?\\s*"), "")

        // Vosk hears "opening/opened" for "open"
        t = t.replace(Regex("^opening\\s+"), "open ")
        t = t.replace(Regex("^opened\\s+"), "open ")

        // Common speech shortcuts
        t = t.replace(Regex("\\byour job\\b"), "youtube")
        t = t.replace(Regex("\\byou tube\\b"), "youtube")
        t = t.replace(Regex("\\bu tube\\b"), "youtube")
        t = t.replace(Regex("\\byou toob\\b"), "youtube")
        t = t.replace(Regex("\\byeetube\\b"), "youtube")
        t = t.replace(Regex("\\bwhats up\\b"), "whatsapp")
        t = t.replace(Regex("\\bwassup\\b"), "whatsapp")
        t = t.replace(Regex("\\bwhats app\\b"), "whatsapp")
        t = t.replace(Regex("\\bwatts app\\b"), "whatsapp")
        t = t.replace(Regex("\\bchat gpt\\b"), "chatgpt")

        // "open the/a X" and trailing politeness
        t = t.replace(Regex("^open\\s+(?:the|a|an)\\s+"), "open ")
        t = t.replace(Regex("^open\\s+(.+?)\\s+(?:please|pls|for\\s+me)\\s*$"), "open $1")

        // Toggle words usually mean the system setting, not an app
        t = t.replace(Regex("^open\\s+(wifi|wi\\s*fi)$"), "turn on wifi")
        t = t.replace(Regex("^open\\s+bluetooth$"), "turn on bluetooth")
        t = t.replace(Regex("^open\\s+flashlight$"), "turn on flashlight")

        // Photo commands map to the camera app
        t = t.replace(Regex("^(take|click)\\s+(a\\s+)?(photo|picture|selfie)$"), "open camera")
        t = t.replace(Regex("^take\\s+a\\s+photo$"), "open camera")

        // "when/and/in instagram" is usually a mis-heard "open instagram".
        val altOpen = Regex("^(when|and|in|wanna|want to)\\s+(.+)$").find(t)
        if (altOpen != null) {
            val spoken = altOpen.groupValues[2].trim()
            val label = appNameResolver.resolveLabel(spoken)
            if (label != null) {
                t = "open $label"
            }
        }

        // Guess app names against the catalog of installed apps before routing.
        val open = Regex("^(open|launch|start|go to)\\s+(.+)$").find(t)
        if (open != null) {
            val spoken = open.groupValues[2].trim()
            val label = appNameResolver.resolveLabel(spoken)
            if (label != null) {
                val prefix = open.groupValues[1].trim()
                t = if (prefix == "go to") "go to $label" else "$prefix $label"
            }
        }

        // A bare app name ("whatsapp") means "open whatsapp".
        if (!t.contains(" ")) {
            val label = appNameResolver.resolveLabel(t)
            if (label != null) {
                t = "open $label"
            }
        }

        val result = t.trim()
        if (result != original) {
            VoxynLogger.i("[NORM] \"$original\" → \"$result\"", "NORM")
        }
        return result
    }
}
