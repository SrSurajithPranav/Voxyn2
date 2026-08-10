package com.voxyn.perception

import android.content.Context
import com.voxyn.utils.VoxynLogger

class ContextEngine(private val context: Context) {

    var currentApp: String? = null
        private set

    fun updateCurrentApp(packageName: String) {
        currentApp = packageName
        VoxynLogger.d("Foreground app: $packageName", "CONTEXT")
    }

    fun getContextSummary(): String {
        return buildString {
            currentApp?.let { append("Current app: $it") }
        }.ifEmpty { "No context" }
    }
}
