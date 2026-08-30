package com.voxyn.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voxyn.perception.AppNameResolver
import com.voxyn.utils.VoxynLogger

data class AppEntry(
    val label: String,
    val packageName: String,
    val tokens: Set<String>,
    val normalized: String
)

/**
 * Index of every launchable app installed on the device. VOXYN uses this to
 * resolve mis-heard spoken app names ("your job" -> YouTube, "hunger books" ->
 * Chat GPT for a user with rhotacism) against the real labels of the apps
 * actually installed on the phone. Matching is deliberately forgiving:
 * exact -> alias -> prefix/contains/token overlap -> fuzzy edit distance.
 */
class AppCatalog(private val context: Context) : AppNameResolver {

    companion object {
        private const val MIN_MATCH_CONFIDENCE = 60
        private const val MIN_SUGGESTION_CONFIDENCE = 50

        private val COMMON_ALIASES = mapOf(
            // YouTube (Vosk hears "your job", "you do", "you tube", ...)
            "yourjob" to "youtube",
            "urjob" to "youtube",
            "yourdo" to "youtube",
            "youdo" to "youtube",
            "youdou" to "youtube",
            "youto" to "youtube",
            "yodo" to "youtube",
            "yourdue" to "youtube",
            "yourdew" to "youtube",
            "yourdupe" to "youtube",
            "yourduke" to "youtube",
            "youtub" to "youtube",
            "utube" to "youtube",
            "yout" to "youtube",

            // WhatsApp
            "whatsup" to "whatsapp",
            "wassup" to "whatsapp",
            "whatsap" to "whatsapp",
            "wantshim" to "whatsapp",
            "whatsthat" to "whatsapp",
            "whatsit" to "whatsapp",
            "wasapp" to "whatsapp",
            "watsapp" to "whatsapp",
            "wa" to "whatsapp",

            // Instagram
            "insta" to "instagram",
            "istagram" to "instagram",
            "instagaram" to "instagram",
            "instag" to "instagram",

            // Telegram / Snapchat
            "telegaram" to "telegram",
            "snap" to "snapchat",
            "snapcchat" to "snapchat",
            "snapchat" to "snapchat",

            // Gmail
            "gmail" to "gmail",
            "gmailcom" to "gmail",
            "mail" to "gmail",

            // Timesheet / 2Office (work apps)
            "timetimesheet" to "timesheet",
            "timesit" to "timesheet",
            "timesheetit" to "timesheet",
            "timesheet" to "timesheet",
            "time sheet" to "timesheet",
            "tooffice" to "2office",
            "twooffice" to "2office",

            // YouTube Music / Play Store
            "youtubemusic" to "youtube music",
            "ytmusic" to "youtube music",
            "playstore" to "play store",
            "appstore" to "play store",
            "app store" to "play store",

            // Chat GPT
            "chatgpt" to "chatgpt",
            "chatjeeptee" to "chatgpt",
            "chatgptea" to "chatgpt",
            "chatgbt" to "chatgpt",
            "chadgpt" to "chatgpt",
            "chatgp" to "chatgpt",
            "gpt" to "chatgpt",
            "gptea" to "chatgpt",

            // HungerBox (Vosk hears "hunger books" for the installed app)
            "hungerbooks" to "hungerbox",
            "hungrybox" to "hungerbox",
            "hungerbox" to "hungerbox",
            "hunger box" to "hungerbox",

            // Settings / Calculator / Chrome / Maps / Photos
            "setting" to "settings",
            "setings" to "settings",
            "calc" to "calculator",
            "calcu" to "calculator",
            "browser" to "chrome",
            "googlechrome" to "chrome",
            "googlemaps" to "maps",
            "map" to "maps",
            "gallery" to "photos",
            "photo" to "photos",

            // Streaming
            "netflex" to "netflix",
            "netflicks" to "netflix",
            "primevideo" to "prime video",
            "prime" to "prime video",
            "amazonprime" to "prime video",
            "spodify" to "spotify",
            "spotyfy" to "spotify",

            // X (Twitter)
            "twitter" to "x",
            "tweeter" to "x",
            "twatter" to "x",

            // Clock / Phone / Messages / Camera / Files
            "alarmclock" to "clock",
            "alarm" to "clock",
            "dialer" to "phone",
            "dial" to "phone",
            "sms" to "messages",
            "texts" to "messages",
            "texting" to "messages",
            "cam" to "camera",
            "filemanager" to "files",
            "myfiles" to "files",
            "file" to "files",
            "googleapp" to "google"
        )

        /** Curated phrases worth letting Vosk emit directly. */
        private val GRAMMAR_EXTRAS = listOf(
            "insta", "chatgpt", "chat gpt", "gpt", "hunger books", "you do",
            "your job", "whats up", "whatsapp", "youtube", "snap", "settings",
            "files", "photos", "gallery", "maps", "music", "clock", "alarm",
            "camera", "calculator", "wifi", "bluetooth", "flashlight", "torch",
            "screenshot"
        )
    }

    @Volatile private var cached: List<AppEntry>? = null

    fun all(): List<AppEntry> {
        cached?.let { return it }
        val entries = buildList {
            val manager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                manager.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { info ->
                    val label = info.loadLabel(manager)?.toString()?.trim().orEmpty()
                    if (label.isNotEmpty()) {
                        add(
                            AppEntry(
                                label = label,
                                packageName = info.activityInfo.packageName,
                                tokens = tokenize(label),
                                normalized = normalize(label)
                            )
                        )
                    }
                }
            }.onFailure {
                VoxynLogger.e("App catalog query failed: ${it.message}", tag = "APPS")
            }
        }
        cached = entries
        VoxynLogger.i("App catalog ready: ${entries.size} apps", "APPS")
        return entries
    }

    /** Best matching installed app for a spoken (possibly mis-heard) name. */
    fun findBest(spoken: String): AppEntry? {
        val query = canonical(spoken)
        if (query.isEmpty()) return null
        val entries = all()
        val scored = entries
            .map { it to score(query, it) }
            .filter { (_, s) -> s >= MIN_MATCH_CONFIDENCE }
            .sortedByDescending { (_, s) -> s }

        if (scored.isEmpty()) {
            VoxynLogger.w("No app matched '$spoken' (query='$query', threshold=$MIN_MATCH_CONFIDENCE)", "APPS")
            return null
        }
        // Two similarly scored fuzzy matches are not a safe launch decision.
        // Return no match so AppLaunchResolver can ask for confirmation instead.
        if (scored.size > 1 && scored[0].second - scored[1].second < 8 && scored[0].second < 100) {
            VoxynLogger.w("Ambiguous app match for '$spoken': ${scored.take(2).map { it.first.label }}", "APPS")
            return null
        }
        scored.take(3).forEach { (e, s) ->
            VoxynLogger.i("App match: '${e.label}' score=$s (query='$query')", "APPS")
        }
        return scored.first().first
    }

    /** Top candidate labels when nothing matched — used for "Did you mean ...?" */
    fun suggest(spoken: String, limit: Int = 3): List<String> {
        val query = canonical(spoken)
        if (query.isEmpty()) return emptyList()
        return all()
            .map { it to score(query, it) }
            .filter { (_, s) -> s >= MIN_SUGGESTION_CONFIDENCE }
            .sortedByDescending { (_, s) -> s }
            .take(limit)
            .map { (e, _) -> e.label }
    }

    override fun resolveLabel(spoken: String): String? = findBest(spoken)?.label

    fun canonical(spoken: String): String {
        val cleaned = stripFillers(spoken)
        return COMMON_ALIASES[normalize(cleaned)] ?: normalize(cleaned)
    }

    /** Words Vosk should be biased toward during command listening. */
    fun grammarTerms(): List<String> {
        val words = linkedSetOf<String>()
        words.addAll(GRAMMAR_EXTRAS)
        COMMON_ALIASES.values.forEach { v -> if (v.length >= 2) words.add(v) }
        all().forEach { entry ->
            entry.tokens.forEach { if (it.length >= 2) words.add(it) }
            if (entry.normalized.length >= 2) words.add(entry.normalized)
            entry.label.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 2 }
                .forEach { words.add(it) }
        }
        return words.toList()
    }

    private fun score(query: String, entry: AppEntry): Int {
        val q = normalize(query)
        return when {
            entry.normalized == q -> 100
            entry.normalized.startsWith(q) -> 85
            entry.normalized.contains(q) -> 75
            entry.normalized.length >= 3 && q.contains(entry.normalized) -> 75
            q == entry.packageName.substringAfterLast('.') -> 60
            else -> {
                val shared = entry.tokens.intersect(tokenize(query)).size
                if (shared > 0) 65 + shared * 5
                else fuzzyScore(q, entry.normalized)
            }
        }
    }

    /** Edit-distance + consonant-skeleton fallback for mis-heard names. */
    private fun fuzzyScore(q: String, normalized: String): Int {
        if (q.length < 4 || normalized.length < 4) return 0

        val maxLen = maxOf(q.length, normalized.length)
        val similarity = 1.0 - levenshtein(q, normalized).toDouble() / maxLen
        if (similarity >= 0.8) return (similarity * 80).toInt()

        val a = consonantSkeleton(q)
        val b = consonantSkeleton(normalized)
        if (a.isNotEmpty() && a == b) return 70

        return 0
    }

    private fun consonantSkeleton(value: String): String =
        value.replace(Regex("[aeiouy]"), "").replace(Regex("(.)\\1+"), "$1")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /** Removes polite filler around a spoken app name ("open a deputy" -> "deputy"). */
    private fun stripFillers(spoken: String): String {
        var s = spoken.trim().lowercase()
        s = s.replace(Regex("^open\\s+"), "")
        s = s.replace(Regex("^(a|an|the|my)\\s+"), "")
        s = s.replace(Regex("\\s+(please|pls)$"), "")
        s = s.replace(Regex("\\s+for\\s+me$"), "")
        s = s.replace(Regex("\\s+it\\s+would\\s+be$"), "")
        return s
    }

    private fun tokenize(value: String): Set<String> =
        value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")
}
