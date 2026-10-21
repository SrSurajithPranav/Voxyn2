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

    data class PhoneticRule(
        val canonical: String,
        val patterns: List<Regex>
    )

    companion object {
        private val PHONETIC_RULES = listOf(
            PhoneticRule(
                canonical = "chatgpt",
                patterns = listOf(
                    Regex("\\bchat\\s*(?:gee\\s*pee\\s*tee|jeep\\s*tee|gp\\s*tea|gbt|gpt|g\\s*p\\s*t)\\b"),
                    Regex("\\bchad\\s*gpt\\b"),
                    Regex("\\bchatgpt\\b")
                )
            ),
            PhoneticRule(
                canonical = "youtube",
                patterns = listOf(
                    Regex("\\b(?:your\\s*job|you\\s*do|you\\s*tube|u\\s*tube|you\\s*toob|yeetube|ur\\s*job|your\\s*do|youdou|youto|yodo|your\\s*due|your\\s*dew)\\b"),
                    Regex("\\byoutube\\b")
                )
            ),
            PhoneticRule(
                canonical = "whatsapp",
                patterns = listOf(
                    Regex("\\b(?:whats\\s*app|what\\s*sap|watts?\\s*app|wassup|whatsup|wasapp|watsapp)\\b"),
                    Regex("^what's\\s+app$"),
                    Regex("\\bwhatsapp\\b")
                )
            ),
            PhoneticRule(
                canonical = "hungerbox",
                patterns = listOf(
                    Regex("\\b(?:hunger\\s*books|hungry\\s*box|hunger\\s*box|hungerbooks|hungrybox)\\b"),
                    Regex("\\bhungerbox\\b")
                )
            ),
            PhoneticRule(
                canonical = "instagram",
                patterns = listOf(
                    Regex("\\b(?:instagaram|istagram|insta|instag)\\b"),
                    Regex("\\binstagram\\b")
                )
            ),
            PhoneticRule(
                canonical = "x",
                patterns = listOf(
                    Regex("\\b(?:twitter|tweeter|twatter)\\b")
                )
            )
        )
    }

    fun normalize(raw: String): String {
        val original = raw.trim().lowercase()
        var t = original

        // Vosk emits [unk] for out-of-vocabulary words; drop them before anything else.
        t = t.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        t = t.trimEnd('.', '?', '!', ',', ';').trim()

        // Wake-word residue
        t = t.replace(Regex("^\\s*(hey|hi|okay)\\s+voxyn[,.!]?\\s*"), "")
        t = t.replace(Regex("^\\s*voxyn[,.!]?\\s*"), "")

        // Vosk hears "opening/opened" for "open"
        t = t.replace(Regex("^opening\\s+"), "open ")
        t = t.replace(Regex("^opened\\s+"), "open ")

        // Apply phonetic / rhotacism normalization
        for (rule in PHONETIC_RULES) {
            for (pattern in rule.patterns) {
                t = t.replace(pattern, rule.canonical)
            }
        }

        // Spoken numbers to digits
        t = t.replace(Regex("\\bzero\\b"), "0")
            .replace(Regex("\\bten\\b"), "10")
            .replace(Regex("\\btwenty\\b"), "20")
            .replace(Regex("\\bthirty\\b"), "30")
            .replace(Regex("\\bforty\\b"), "40")
            .replace(Regex("\\bfifty\\b"), "50")
            .replace(Regex("\\bsixty\\b"), "60")
            .replace(Regex("\\bseventy\\b"), "70")
            .replace(Regex("\\beighty\\b"), "80")
            .replace(Regex("\\bninety\\b"), "90")
            .replace(Regex("\\bhundred\\b"), "100")

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
