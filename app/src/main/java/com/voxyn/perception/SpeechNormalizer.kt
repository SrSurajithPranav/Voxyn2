package com.voxyn.perception

import com.voxyn.utils.VoxynLogger

/**
 * SpeechNormalizationLayer — turns raw STT transcripts into a canonical request.
 *
 * Vosk's small offline model and user pronunciation patterns (such as rhotacism,
 * where "R" sounds can be softened or confused with "W" or "D", e.g. "you do" for YouTube,
 * "hunger books" for HungerBox, "chat jeep tee" for ChatGPT) require a deterministic,
 * context-aware normalization before intent routing.
 *
 * Pipeline: raw STT → text cleanup → phonetic/rhotacism normalization → app/entity matching → canonical request.
 */
class SpeechNormalizer(private val appNameResolver: AppNameResolver) {

    fun normalize(raw: String): String {
        val original = raw.trim().lowercase()
        var t = original

        // Vosk emits [unk] for out-of-vocabulary words; drop them before anything else.
        t = t.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()

        // Wake-word residue
        t = t.replace(Regex("^\\s*(hey|hi|okay)\\s+voxyn[,.!]?\\s*"), "")
        t = t.replace(Regex("^\\s*voxyn[,.!]?\\s*"), "")

        // Vosk hears "opening/opened" for "open"
        t = t.replace(Regex("^opening\\s+"), "open ")
        t = t.replace(Regex("^opened\\s+"), "open ")

        // Common speech / phonetic / rhotacism replacements
        // YouTube: "your job", "you do", "you tube", "u tube", "yeetube"
        t = t.replace(Regex("\\byour\\s+job\\b"), "youtube")
        t = t.replace(Regex("\\byou\\s+do\\b"), "youtube")
        t = t.replace(Regex("\\byou\\s+tube\\b"), "youtube")
        t = t.replace(Regex("\\bu\\s+tube\\b"), "youtube")
        t = t.replace(Regex("\\byou\\s+toob\\b"), "youtube")
        t = t.replace(Regex("\\byeetube\\b"), "youtube")

        // WhatsApp: "whats app", "what sap", "watts app", "wassup", "whatsup", "wasapp"
        t = t.replace(Regex("\\bwhats\\s+app\\b"), "whatsapp")
        t = t.replace(Regex("\\bwhat\\s+sap\\b"), "whatsapp")
        t = t.replace(Regex("\\bwatts?\\s+app\\b"), "whatsapp")
        t = t.replace(Regex("\\bwassup\\b"), "whatsapp")
        t = t.replace(Regex("\\bwhatsup\\b"), "whatsapp")
        t = t.replace(Regex("\\bwasapp\\b"), "whatsapp")
        t = t.replace(Regex("^what's\\s+app$"), "whatsapp")

        // ChatGPT: "chat gpt", "chat jeep tee", "chat gp tea", "chad gpt", "chat gbt"
        t = t.replace(Regex("\\bchat\\s+jeep\\s*tee\\b"), "chatgpt")
        t = t.replace(Regex("\\bchat\\s+gp\\s*tea\\b"), "chatgpt")
        t = t.replace(Regex("\\bchat\\s+gpt\\b"), "chatgpt")
        t = t.replace(Regex("\\bchad\\s+gpt\\b"), "chatgpt")
        t = t.replace(Regex("\\bchat\\s+gbt\\b"), "chatgpt")

        // HungerBox: "hunger books", "hungry box", "hunger box"
        t = t.replace(Regex("\\bhunger\\s+books\\b"), "hungerbox")
        t = t.replace(Regex("\\bhungry\\s+box\\b"), "hungerbox")
        t = t.replace(Regex("\\bhunger\\s+box\\b"), "hungerbox")

        // Instagram: "insta", "instagaram", "istagram"
        t = t.replace(Regex("\\binstagaram\\b"), "instagram")
        t = t.replace(Regex("\\bistagram\\b"), "instagram")

        // "open the/a X" and trailing politeness
        t = t.replace(Regex("^open\\s+(?:the|a|an)\\s+"), "open ")
        t = t.replace(Regex("^open\\s+(.+?)\\s+(?:please|pls|for\\s+me)\\s*$"), "open $1")

        // Toggle words usually mean the system setting, not an app
        t = t.replace(Regex("^open\\s+(wifi|wi\\s*fi)$"), "turn on wifi")
        t = t.replace(Regex("^open\\s+bluetooth$"), "turn on bluetooth")
        t = t.replace(Regex("^open\\s+flashlight$"), "turn on flashlight")
        t = t.replace(Regex("^open\\s+torch$"), "turn on flashlight")

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
