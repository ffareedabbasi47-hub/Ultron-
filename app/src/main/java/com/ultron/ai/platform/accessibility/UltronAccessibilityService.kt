package com.ultron.ai.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ultron.ai.domain.NavKind
import com.ultron.ai.domain.ScreenControl
import com.ultron.ai.domain.ScreenNode
import com.ultron.ai.domain.ScreenSnapshot
import com.ultron.ai.domain.SwitchResult

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

    /**
     * Switch finder for Settings screens/panels. A switch row is [title text] + [Switch]; the switch usually has no text of
     * its own, so we locate the title, climb to its row and look for a checkable node there. If the window has exactly one
     * switch (Wi-Fi / Internet panels) that one is used. Never touches the password/installer screens.
     */
    internal fun setSwitchState(hints: List<String>, on: Boolean, allowSingle: Boolean): SwitchResult {
        val root = rootInActiveWindow ?: return SwitchResult.NOT_FOUND
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName || com.ultron.ai.domain.RiskPolicy.packageBlocked(pkg)) return SwitchResult.NOT_FOUND
        val switches = ArrayList<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30) return
            if (n.isCheckable && n.isVisibleToUser) switches += n
            for (i in 0 until n.childCount) collect(n.getChild(i), d + 1)
        }
        collect(root, 0)
        fun rowText(n: AccessibilityNodeInfo): String {
            val sb = StringBuilder()
            var cur: AccessibilityNodeInfo? = n; var up = 0
            while (cur != null && up < 4) {
                fun txt(x: AccessibilityNodeInfo?, d: Int) {
                    if (x == null || d > 4) return
                    x.text?.let { sb.append(it).append(' ') }
                    x.contentDescription?.let { sb.append(it).append(' ') }
                    for (i in 0 until x.childCount) txt(x.getChild(i), d + 1)
                }
                txt(cur, 0)
                if (sb.isNotBlank()) break
                cur = cur.parent; up++
            }
            return sb.toString().lowercase()
        }
        val byHint = switches.filter { sw -> val t = rowText(sw); hints.any { t.contains(it) } }
        val target = byHint.firstOrNull() ?: (if (allowSingle) switches.singleOrNull() else null) ?: return SwitchResult.NOT_FOUND
        if (target.isChecked == on) return SwitchResult.ALREADY
        if (!clickWithParents(target)) return SwitchResult.FAILED
        Thread.sleep(500)
        target.refresh()
        return if (target.isChecked == on) SwitchResult.CHANGED else SwitchResult.FAILED
    }

    internal fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
            ?: lastNodes.firstOrNull { it.isEditable && !it.isPassword && it.isVisibleToUser }
            ?: return false
        // ACTION_SET_TEXT replaces the whole field: append to what the user already wrote instead of destroying it.
        val existing = if (target.isShowingHintText) "" else target.text?.toString().orEmpty()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text) }
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
    override fun setSwitch(hints: List<String>, on: Boolean, allowSingle: Boolean): SwitchResult = s?.setSwitchState(hints, on, allowSingle) ?: SwitchResult.FAILED
}
