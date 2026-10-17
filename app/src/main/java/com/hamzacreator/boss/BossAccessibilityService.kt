package com.hamzacreator.boss

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Boss's "hands": an AccessibilityService that finds on-screen elements
 * by text, taps / swipes / types into them, and launches apps.
 *
 * Enable in Settings → Accessibility → "Boss".
 *
 * FIX vs Gemini's draft: single companion object (was duplicated),
 * missing android.content.Intent import added, and a public
 * getInstance() so CommandParser can reach the running service.
 */
class BossAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: BossAccessibilityService? = null

        /** True while the service is connected. */
        fun isInstanceActive(): Boolean = instance != null

        /** Static handle for CommandParser / CommandRouter. */
        fun getInstance(): BossAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: Boss works on demand, not on accessibility events.
    }

    override fun onInterrupt() { /* no-op */ }

    /** First node whose visible text or content-description contains [text] (case-insensitive). */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByTextRecursive(root, text.lowercase().trim())
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        if (query.isNotEmpty()) {
            val label = ((node.text?.toString() ?: "") + " " +
                    (node.contentDescription?.toString() ?: "")).trim()
            if (label.isNotEmpty() && label.lowercase().contains(query)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, query)
            if (found != null) return found
        }
        return null
    }

    /** First editable (text-input) node on screen. */
    fun findEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditableRecursive(root)
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findEditableRecursive(it) }
            if (found != null) return found
        }
        return null
    }

    /** Tap a node: ACTION_CLICK, then clickable parents, then gesture fallback. */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return tapAt(rect.centerX(), rect.centerY())
    }

    /** Gesture tap at absolute screen coordinates. */
    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Swipe gesture. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Scroll the active window forward (down). */
    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** Type text into a node via the accessibility set-text action. */
    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Launch an installed app by package name. */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
