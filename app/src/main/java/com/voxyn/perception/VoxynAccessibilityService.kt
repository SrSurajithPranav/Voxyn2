package com.voxyn.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.voxyn.utils.VoxynLogger
import org.koin.android.ext.android.inject

class VoxynAccessibilityService : AccessibilityService() {

    private val contextEngine: ContextEngine by inject()

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        VoxynLogger.i("Accessibility Service connected", "A11Y")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName) {
                contextEngine.updateCurrentApp(pkg)
            }
        }
    }

    override fun onInterrupt() {
        VoxynLogger.w("Accessibility Service interrupted", "A11Y")
    }
}
