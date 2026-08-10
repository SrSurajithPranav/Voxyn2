package com.voxyn.execution

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voxyn.apps.AppCatalog
import com.voxyn.core.PhaseResult
import com.voxyn.utils.VoxynLogger

/** Resolves a user-facing app name against the apps actually installed on this device. */
class AppLaunchResolver(
    private val context: Context,
    private val catalog: AppCatalog
) {

    fun launch(appName: String): PhaseResult<String> {
        if (appName.isBlank()) return PhaseResult.Failure("App name is missing")

        val entry = catalog.findBest(appName)
        val displayName = entry?.label ?: appName.trim()

        val packageName = entry?.packageName
            ?: knownPackage(catalog.canonical(appName))
            ?: run {
                val suggestions = catalog.suggest(appName, 3)
                val message = if (suggestions.isEmpty()) {
                    "I couldn't find an installed app named $displayName."
                } else {
                    "I couldn't find $displayName. Did you mean ${suggestions.joinToString(" or ")}?"
                }
                return PhaseResult.Failure(message)
            }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return PhaseResult.Failure("I couldn't find an installed app named $displayName.")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        VoxynLogger.i("Opened app: $displayName ($packageName)", "EXEC")
        return PhaseResult.Success("Opened $displayName")
    }

    private fun knownPackage(canonicalName: String): String? = when (canonicalName) {
        "youtube" -> "com.google.android.youtube"
        "whatsapp" -> "com.whatsapp"
        "whatsappbusiness" -> "com.whatsapp.w4b"
        "chatgpt" -> "com.openai.chatgpt"
        "settings" -> "com.android.settings"
        "chrome" -> "com.android.chrome"
        "google" -> "com.google.android.googlequicksearchbox"
        "instagram" -> "com.instagram.android"
        "telegram" -> "org.telegram.messenger"
        "snapchat" -> "com.snapchat.android"
        "gmail" -> "com.google.android.gm"
        "maps" -> "com.google.android.apps.maps"
        "photos" -> "com.google.android.apps.photos"
        "playstore" -> "com.android.vending"
        "youtubemusic" -> "com.google.android.apps.youtube.music"
        "clock" -> "com.google.android.deskclock"
        "calculator" -> "com.google.android.calculator"
        "phone" -> "com.google.android.dialer"
        "messages" -> "com.google.android.apps.messaging"
        "spotify" -> "com.spotify.music"
        "netflix" -> "com.netflix.mediaclient"
        "primevideo" -> "com.amazon.avod.thirdpartyclient"
        "x" -> "com.twitter.android"
        "facebook" -> "com.facebook.katana"
        "linkedin" -> "com.linkedin.android"
        else -> null
    }
}
