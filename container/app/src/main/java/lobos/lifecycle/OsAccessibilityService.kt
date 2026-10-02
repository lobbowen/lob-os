package lobos.lifecycle

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import lobos.RuntimeDiagnostics
import org.json.JSONArray
import org.json.JSONObject

class OsAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt：系统中断了服务反馈")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "OsAccessibilityService 已连接（UI 自动化可用）")
        RuntimeDiagnostics.append(this, "accessibility", true, "无障碍服务已连接", "ui_automation 能力可用")
        RuntimeDiagnostics.append(
            this, "accessibility", true,
            "服务已连接：UI 自动化（tap/swipe/inputText/getUiTree/waitFor）可执行",
            "无障碍在本系统里只作自动化执行体，不承担保活职责",
        )
        OsHostService.ensureRunning(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "OsAccessibilityService 已解绑")
        RuntimeDiagnostics.append(
            this, "accessibility", false,
            "服务被解绑：UI 自动化不可用（ui.* 将返回 -32001）",
            "如需恢复请到系统「无障碍」页重新开启；本系统不会静默重挂",
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchPath(path, durationMs.coerceIn(1L, 3000L))
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchPath(path, durationMs.coerceIn(1L, 10000L))
    }

    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(desc: GestureDescription?) { result = true; latch.countDown() }
                override fun onCancelled(desc: GestureDescription?) { result = false; latch.countDown() }
            },
            null
        )
        if (!dispatched) return false
        latch.await(durationMs + 800L, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    fun dumpUiTree(maxNodes: Int = 3000, maxDepth: Int = 40): JSONObject {
        val windows = JSONArray()
        var budget = intArrayOf(maxNodes)

        try {
            val list = getWindows()
            if (list != null) {
                for (w in list) {
                    val root = w.root ?: continue
                    windows.put(
                        JSONObject().apply {
                            put("id", w.id)
                            put("type", windowTypeName(w.type))
                            put("layer", w.layer)
                            put("active", w.isActive)
                            put("focused", w.isFocused)
                            put("node", nodeToJson(root, 0, maxDepth, budget) ?: JSONObject.NULL)
                        }
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getWindows 采集失败", e)
        }

        if (windows.length() == 0) {
            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null) {
                windows.put(
                    JSONObject().apply {
                        put("id", -1)
                        put("type", "active")
                        put("layer", -1)
                        put("active", true)
                        put("focused", true)
                        put("node", nodeToJson(root, 0, maxDepth, budget) ?: JSONObject.NULL)
                    }
                )
            }
        }

        return JSONObject().apply {
            put("windows", windows)
            put("windowCount", windows.length())
            put("truncated", budget[0] <= 0)
        }
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        else -> "unknown($type)"
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        budget: IntArray
    ): JSONObject? {
        if (node == null) return null
        if (depth > maxDepth || budget[0] <= 0) return null
        budget[0] -= 1

        val rect = Rect()
        try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}

        val children = JSONArray()
        try {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val c = nodeToJson(child, depth + 1, maxDepth, budget)
                if (c != null) children.put(c)
            }
        } catch (_: Throwable) {}

        return JSONObject().apply {
            put("cls", node.className?.toString() ?: "")
            put("pkg", node.packageName?.toString() ?: "")
            put("id", node.viewIdResourceName ?: "")
            put("text", node.text?.toString() ?: "")
            put("desc", node.contentDescription?.toString() ?: "")
            put("bounds", JSONArray().apply {
                put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
            })
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
            put("checked", if (node.isCheckable) node.isChecked else JSONObject.NULL)
            put("children", children)
        }
    }

    fun inputText(text: String, selector: JSONObject? = null): Boolean {
        val target = findEditableTarget(selector) ?: return false

        if (setTextOn(target, text)) return true

        return try {
            if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("lobos", text))
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_PASTE 降级失败", e)
            false
        }
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean = try {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (e: Throwable) {
        Log.w(TAG, "ACTION_SET_TEXT 失败", e)
        false
    }

    private fun findEditableTarget(selector: JSONObject?): AccessibilityNodeInfo? {
        if (selector != null && selector.length() > 0) {
            selector.optString("id", "").takeIf { it.isNotBlank() }?.let { id ->
                val byId = findViewIdNode(id)
                if (byId != null && byId.isEditable) return byId
                if (byId != null) return byId
            }
            selector.optString("text", "").takeIf { it.isNotBlank() }?.let { t ->
                findTextNode(t)?.let { return it }
            }
        }
        try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        } catch (_: Throwable) {}
        return findFirstEditable(activeRoots(), 0)
    }

    private fun findViewIdNode(viewId: String): AccessibilityNodeInfo? = try {
        activeRoots().firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        }
    } catch (_: Throwable) { null }

    private fun findTextNode(text: String): AccessibilityNodeInfo? = try {
        activeRoots().firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
        }
    } catch (_: Throwable) { null }

    private fun findFirstEditable(
        nodes: List<AccessibilityNodeInfo>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 40) return null
        for (n in nodes) {
            if (n.isEditable) return n
            val kids = (0 until n.childCount).mapNotNull { n.getChild(it) }
            findFirstEditable(kids, depth + 1)?.let { return it }
        }
        return null
    }

    fun activeRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            getWindows()?.forEach { w -> w.root?.let { out.add(it) } }
        } catch (_: Throwable) {}
        if (out.isEmpty()) {
            try { rootInActiveWindow?.let { out.add(it) } } catch (_: Throwable) {}
        }
        return out
    }

    fun waitForNode(selector: JSONObject, timeoutMs: Long, intervalMs: Long = 250L): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 120_000L)
        val step = intervalMs.coerceIn(50L, 2000L)
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() <= deadline) {
            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null) {
                val hit = matchNode(root, selector)
                if (hit != null) {
                    return JSONObject().apply {
                        put("found", true)
                        put("elapsedMs", System.currentTimeMillis() - startedAt)
                        put("node", nodeToJson(hit, 0, 40, intArrayOf(2000)) ?: JSONObject.NULL)
                    }
                }
            }
            try { Thread.sleep(step) } catch (_: InterruptedException) { break }
        }
        return JSONObject().apply {
            put("found", false)
            put("elapsedMs", System.currentTimeMillis() - startedAt)
        }
    }

    private fun matchNode(
        node: AccessibilityNodeInfo?,
        selector: JSONObject,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null

        val wantId = selector.optString("id", "")
        val wantText = selector.optString("text", "")
        val wantCls = selector.optString("className", "")
        val wantPkg = selector.optString("pkg", "")

        val okPkg = wantPkg.isEmpty() || node.packageName?.toString() == wantPkg
        if (okPkg) {
            val okId = wantId.isEmpty() || node.viewIdResourceName == wantId
            val okText = wantText.isEmpty() ||
                node.text?.toString()?.contains(wantText) == true ||
                node.contentDescription?.toString()?.contains(wantText) == true
            val okCls = wantCls.isEmpty() || node.className?.toString() == wantCls
            if (okId && okText && okCls && (wantId.isNotEmpty() || wantText.isNotEmpty() || wantCls.isNotEmpty())) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            matchNode(node.getChild(i), selector, depth + 1)?.let { return it }
        }
        return null
    }

    companion object {
        const val TAG = "OsAccessibilityService"

        @Volatile
        var instance: OsAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }
}
