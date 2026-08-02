package com.voxyn.execution

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voxyn.core.PhaseResult

/** Resolves a user-facing app name against launchable applications on this device. */
class AppLaunchResolver(private val context: Context) {

    fun launch(appName: String): PhaseResult<String> {
        val query = normalize(appName)
        if (query.isBlank()) return PhaseResult.Failure("App name is missing")

        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .map { info ->
                AppCandidate(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName
                )
            }

        val candidate = matches
            .map { it to score(query, it) }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?: knownPackage(appName)?.let { packageName ->
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    AppCandidate(appName, packageName)
                }
            }
            ?: return PhaseResult.Failure("I couldn't find an installed app named $appName")

        val launchIntent = packageManager.getLaunchIntentForPackage(candidate.packageName)
            ?: return PhaseResult.Failure("${candidate.label} cannot be launched")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return PhaseResult.Success("Opened ${candidate.label}")
    }

    private fun score(query: String, candidate: AppCandidate): Int {
        val label = normalize(candidate.label)
        val packageName = normalize(candidate.packageName.substringAfterLast('.'))
        return when {
            label == query -> 100
            label.startsWith(query) -> 80
            label.contains(query) -> 60
            packageName == query -> 50
            else -> 0
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun knownPackage(appName: String): String? = when (normalize(appName)) {
        "youtube" -> "com.google.android.youtube"
        "whatsapp" -> "com.whatsapp"
        "whatsappbusiness" -> "com.whatsapp.w4b"
        "settings" -> "com.android.settings"
        "chrome" -> "com.android.chrome"
        else -> null
    }

    private data class AppCandidate(val label: String, val packageName: String)
}
