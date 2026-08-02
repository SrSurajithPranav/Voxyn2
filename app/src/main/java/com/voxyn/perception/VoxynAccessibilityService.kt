package com.voxyn.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voxyn.memory.MemoryRepository
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class VoxynAccessibilityService : AccessibilityService() {

    companion object {
        var instance: VoxynAccessibilityService? = null
            private set
    }

    private val contextEngine: ContextEngine by inject()
    private val memory: MemoryRepository by inject()
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        VoxynLogger.i("VoxynAccessibilityService connected & instance bound", "A11Y")
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

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    // ─── Actions ──────────────────────────────────────────────────────

    fun performGoBack(): Boolean {
        VoxynLogger.i("A11Y: Performing GO_BACK", "A11Y")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performGoHome(): Boolean {
        VoxynLogger.i("A11Y: Performing GO_HOME", "A11Y")
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performReadNotifications(): Boolean {
        VoxynLogger.i("A11Y: Opening Notifications shade", "A11Y")
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun performScrollDown(): Boolean {
        VoxynLogger.i("A11Y: Performing SCROLL_DOWN", "A11Y")
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        val success = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ?: root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return success
    }

    fun performScrollUp(): Boolean {
        VoxynLogger.i("A11Y: Performing SCROLL_UP", "A11Y")
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        val success = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ?: root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        return success
    }

    fun performSendCurrentMessage(): Boolean {
        VoxynLogger.i("A11Y: Performing SEND_CURRENT_MESSAGE", "A11Y")
        val root = rootInActiveWindow ?: return false
        val sendNode = findNodeByTextOrId(root, listOf("send", "submit", "post"))
        if (sendNode != null) {
            var target: AccessibilityNodeInfo? = sendNode
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    fun performLikeCurrent(): Boolean {
        VoxynLogger.i("A11Y: Performing LIKE_CURRENT", "A11Y")
        val root = rootInActiveWindow ?: return false
        val likeNode = findNodeByTextOrId(root, listOf("like", "thumbs up", "favorite", "upvote"))
        if (likeNode != null) {
            var target: AccessibilityNodeInfo? = likeNode
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: likeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    fun performRememberThis(): String {
        VoxynLogger.i("A11Y: Performing REMEMBER_THIS (scraping screen text)", "A11Y")
        val root = rootInActiveWindow
        val textContent = if (root != null) extractAllText(root).joinToString("\n") else "No screen text captured"
        val timestamp = System.currentTimeMillis()

        scope.launch {
            memory.recordOutcome("REMEMBER_THIS", true)
        }

        VoxynLogger.i("Remembered screen clip (${textContent.length} chars)", "A11Y")
        return textContent.take(200)
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByTextOrId(node: AccessibilityNodeInfo, terms: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        for (term in terms) {
            if (text.contains(term) || desc.contains(term) || viewId.contains(term)) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextOrId(child, terms)
            if (found != null) return found
        }
        return null
    }

    private fun extractAllText(node: AccessibilityNodeInfo): List<String> {
        val list = mutableListOf<String>()
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) list.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) list.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            list.addAll(extractAllText(child))
        }
        return list
    }
}
