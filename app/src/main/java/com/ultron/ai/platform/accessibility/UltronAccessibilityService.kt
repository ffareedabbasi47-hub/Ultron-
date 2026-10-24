package com.ultron.ai.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ultron.ai.domain.NavKind
import com.ultron.ai.domain.ScreenControl
import com.ultron.ai.domain.ScreenNode
import com.ultron.ai.domain.ScreenSnapshot

/**
 * Optional. Works only after the user turns it on in Android Settings > Accessibility. It acts only when ULTRON
 * is given a command, never watches or records the screen in the background, never reads password fields,
 * and refuses to act inside ULTRON's own window or the system package installer.
 */
class UltronAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* intentionally ignored: no passive monitoring */ }
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    // ---- tree helpers ----
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    internal fun snapshotNow(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName) return ScreenSnapshot(pkg, emptyList())
        val infos = ArrayList<AccessibilityNodeInfo>()
        val out = ArrayList<ScreenNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || out.size >= 120) return
            if (n.isVisibleToUser) {
                val label = when {
                    n.isPassword -> "(password field)"
                    !n.text.isNullOrBlank() -> n.text.toString()
                    !n.contentDescription.isNullOrBlank() -> n.contentDescription.toString()
                    !n.hintText.isNullOrBlank() -> n.hintText.toString()
                    else -> ""
                }
                val interesting = label.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable
                if (interesting && !(label.isBlank() && !n.isClickable && !n.isEditable)) {
                    infos += n
                    out += ScreenNode(out.size, label.replace('\n', ' ').trim(), n.isClickable || hasClickableParent(n), n.isEditable && !n.isPassword, n.isScrollable)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        lastNodes = infos
        return ScreenSnapshot(pkg, out)
    }

    private fun hasClickableParent(n: AccessibilityNodeInfo): Boolean {
        var p = n.parent; var d = 0
        while (p != null && d < 6) { if (p.isClickable) return true; p = p.parent; d++ }
        return false
    }

    private fun clickWithParents(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n; var d = 0
        while (cur != null && d < 7) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; d++
        }
        return false
    }

    internal fun clickIndex(id: Int): Boolean {
        val n = lastNodes.getOrNull(id) ?: return false
        if (n.isPassword) return false
        return clickWithParents(n)
    }

    internal fun clickByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() == packageName) return false
        val q = label.trim().lowercase()
        val found = root.findAccessibilityNodeInfosByText(label.trim()).filter { !it.isPassword }
        val best = found.sortedBy { (it.text ?: it.contentDescription)?.toString()?.length ?: 999 }
            .firstOrNull { (it.text?.toString() ?: it.contentDescription?.toString() ?: "").lowercase().contains(q) }
        return best?.let { clickWithParents(it) } ?: false
    }

    internal fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
            ?: lastNodes.firstOrNull { it.isEditable && !it.isPassword && it.isVisibleToUser }
            ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    internal fun scrollScreen(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val s = find(root) ?: return false
        return s.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    internal fun nav(kind: NavKind): Boolean = performGlobalAction(
        when (kind) {
            NavKind.BACK -> GLOBAL_ACTION_BACK
            NavKind.HOME -> GLOBAL_ACTION_HOME
            NavKind.RECENTS -> GLOBAL_ACTION_RECENTS
            NavKind.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        },
    )

    companion object {
        @Volatile internal var instance: UltronAccessibilityService? = null
    }
}

/** Bridge from the app to the running service (null service = not enabled, every call reports failure honestly). */
class AccessibilityScreenControl : ScreenControl {
    private val s get() = UltronAccessibilityService.instance
    override fun isEnabled() = s != null
    override fun snapshot(): ScreenSnapshot? = s?.snapshotNow()
    override fun clickNode(id: Int) = s?.clickIndex(id) ?: false
    override fun clickLabel(label: String) = s?.clickByLabel(label) ?: false
    override fun typeText(text: String) = s?.type(text) ?: false
    override fun scroll(down: Boolean) = s?.scrollScreen(down) ?: false
    override fun navigate(kind: NavKind) = s?.nav(kind) ?: false
}
