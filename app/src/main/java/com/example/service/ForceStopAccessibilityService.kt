package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.BuildConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

enum class ForceStopActionResult {
    NONE,
    CONFIRMATION_CLICKED,
    FORCE_STOP_CLICKED,
    ALREADY_DISABLED
}

enum class ClearCacheActionResult {
    NONE,
    STORAGE_CLICKED,
    CLEAR_CACHE_CLICKED,
    ALREADY_CLEARED
}

/**
 * Accessibility service used to drive the system Settings UI.
 *
 * Performance design:
 *  - The service is idle by default (no event types, no window flags). It is only "woken"
 *    for the duration of an automation run.
 *  - Events never trigger scans themselves; they only raise a conflated signal that the
 *    automation coroutine waits on (see [awaitChange]). All scanning happens on that single
 *    coroutine, never on the main thread, and never concurrently.
 *  - Only windows belonging to the Settings package are inspected (SystemUI, IME and our own
 *    window are skipped), and when possible the system itself filters events by package.
 *  - Each force-stop tick is a single pass per window instead of several overlapping ones.
 *  - Resource-ID / text lookups try the previously successful key first and only query
 *    IDs that make sense for the window's package.
 */
private fun safeRecycle(node: AccessibilityNodeInfo) {
    try { node.recycle() } catch (ignored: Exception) {}
}

class ForceStopAccessibilityService : AccessibilityService() {

    @Volatile
    private var automationActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        setInstance(this)
        // Start in sleep mode so the service consumes virtually zero RAM/CPU when idle
        sleepService()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        sleepService()
        _isServiceActive.value = false
        setInstance(null)
        return super.onUnbind(intent)
    }

    /**
     * Awakens the service when automation begins.
     *
     * Events are NOT filtered by package: OEM builds (Samsung One UI in particular) can host the
     * App Info page and its dialogs in packages we cannot reliably predict, and a wrong guess
     * silently disables the whole automation. Scanning is already cheap because events only
     * raise a signal and our own/SystemUI windows are skipped.
     */
    fun wakeForAutomation() {
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = EVENT_NOTIFICATION_TIMEOUT_MS
            info.packageNames = null
            serviceInfo = info
            automationActive = true
        } catch (ignored: Exception) {}
    }

    /**
     * Puts the service into low-memory sleep mode when idle: no events are delivered and the
     * window-tracking flags are cleared so the system does no work on our behalf.
     */
    fun sleepService() {
        automationActive = false
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = 0
            info.flags = 0
            info.packageNames = null
            serviceInfo = info
        } catch (ignored: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally cheap: do NOT touch event.source or scan here.
        if (!automationActive) return
        notifyChange()
    }

    // ---------------------------------------------------------------------------------------
    // Force stop
    // ---------------------------------------------------------------------------------------

    /**
     * Checks if the "Force stop" button on the current App Info page is disabled,
     * indicating that the app is now force-stopped.
     */
    fun isForceStopDisabled(): Boolean {
        val roots = getRelevantRoots()
        try {
            for (root in roots) {
                val node = (try { findForceStopNode(root) } catch (e: Exception) { null }) ?: continue
                try {
                    if (!node.isEnabled) return true
                } finally {
                    safeRecycle(node)
                }
            }
            return false
        } finally {
            roots.forEach { safeRecycle(it) }
        }
    }

    /**
     * One pass over the relevant windows:
     * 1. If a confirmation dialog is visible -> click its positive button.
     * 2. If a dialog is on screen but nothing could be clicked -> WAIT (never touch the background page).
     * 3. If NO dialog is present -> find and click "Force stop" on the App Info page.
     */
    fun scanAndExecute(@Suppress("UNUSED_PARAMETER") targetPackage: String): ForceStopActionResult {
        val roots = getRelevantRoots()
        if (roots.isEmpty()) {
            debugLog("force-stop scan: no relevant windows")
            return ForceStopActionResult.NONE
        }

        val snapshots = ArrayList<ForceStopSnapshot>(roots.size)
        try {
            // PHASE 1: inspect each window once; click a confirmation button as soon as one is found.
            for (root in roots) {
                val snap = ForceStopSnapshot()
                snapshots.add(snap)
                try {
                    inspectForceStop(root, snap)
                    if (tryClickPositive(snap)) return ForceStopActionResult.CONFIRMATION_CLICKED
                } catch (ignored: Exception) {}
            }

            if (BuildConfig.DEBUG) {
                debugLog(
                    "force-stop scan: roots=" + roots.joinToString { it.packageName.toString() } +
                        " dialog=" + snapshots.any { it.dialogPresent } +
                        " forceStopFound=" + snapshots.any { it.forceStop != null } +
                        " candidates=" + snapshots.sumOf { it.candidates.size }
                )
            }

            // PHASE 2: a dialog is on screen -> do not touch the background window.
            if (snapshots.any { it.dialogPresent }) return ForceStopActionResult.NONE

            // PHASE 3: no dialog -> click Force stop (or report it is already disabled).
            for (snap in snapshots) {
                val node = snap.forceStop ?: continue
                try {
                    if (!node.isEnabled) return ForceStopActionResult.ALREADY_DISABLED
                    if (performClickWithFallback(node)) return ForceStopActionResult.FORCE_STOP_CLICKED
                } catch (ignored: Exception) {}
            }
            return ForceStopActionResult.NONE
        } finally {
            snapshots.forEach { it.release() }
            roots.forEach { safeRecycle(it) }
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private class QueuedNode(val node: AccessibilityNodeInfo, val depth: Int)

    /** Everything one traversal of a window learns; owns the nodes it keeps. */
    private class ForceStopSnapshot {
        var dialogPresent = false
        var positive: AccessibilityNodeInfo? = null
        var forceStop: AccessibilityNodeInfo? = null
        val candidates = ArrayList<AccessibilityNodeInfo>()
        private val owned = ArrayList<AccessibilityNodeInfo>()

        fun own(node: AccessibilityNodeInfo) {
            if (owned.isEmpty() || owned[owned.size - 1] !== node) owned.add(node)
        }

        fun release() {
            owned.forEach { safeRecycle(it) }
            owned.clear()
            candidates.clear()
            positive = null
            forceStop = null
        }
    }

    private fun inspectForceStop(root: AccessibilityNodeInfo, snap: ForceStopSnapshot) {
        val pkg = root.packageName?.toString().orEmpty()

        // (a) Standard positive dialog button by resource id (cheap, language neutral).
        val positive = findFirst(
            kind = KIND_POSITIVE,
            keys = scopedIds(KIND_POSITIVE, pkg, POSITIVE_ID_NAMES, GLOBAL_POSITIVE_IDS),
            query = { root.findAccessibilityNodeInfosByViewId(it) },
            accept = { it.isEnabled }
        )
        if (positive != null) {
            snap.positive = positive
            snap.own(positive)
            snap.dialogPresent = true
            return
        }

        // (b) Force stop button by resource id.
        val fsById = findFirst(
            kind = KIND_FORCE_STOP,
            keys = scopedIds(KIND_FORCE_STOP, pkg, FORCE_STOP_ID_NAMES, emptyList()),
            query = { root.findAccessibilityNodeInfosByViewId(it) },
            accept = { isLikelyForceStop(it) }
        )
        if (fsById != null) {
            snap.forceStop = fsById
            snap.own(fsById)
        }

        // (c) One shallow traversal for dialog markers, text buttons and (if needed) Force stop.
        val queue = ArrayDeque<QueuedNode>()
        enqueueChildren(root, 1, queue)
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val node = item.node
                var keep = false
                try {
                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    val className = node.className?.toString().orEmpty()

                    if (isDialogMarker(text, viewId, className)) snap.dialogPresent = true

                    if (snap.positive == null && viewId.endsWith("/button1") && node.isEnabled) {
                        snap.positive = node
                        snap.own(node)
                        keep = true
                    }

                    val clickableOrButton = className.contains("Button", ignoreCase = true) ||
                            className.contains("TextView", ignoreCase = true) ||
                            node.isClickable
                    if (clickableOrButton && (text.isNotEmpty() || desc.isNotEmpty())) {
                        snap.candidates.add(node)
                        snap.own(node)
                        keep = true
                    }

                    if (snap.forceStop == null && isLikelyForceStop(viewId, text, desc)) {
                        snap.forceStop = node
                        snap.own(node)
                        keep = true
                    }

                    if (item.depth < MAX_FORCE_STOP_DEPTH && queue.size < MAX_FORCE_STOP_QUEUE) {
                        enqueueChildren(node, item.depth + 1, queue)
                    }
                } finally {
                    if (!keep) safeRecycle(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) safeRecycle(queue.removeFirst().node)
        }
    }

    private fun tryClickPositive(snap: ForceStopSnapshot): Boolean {
        snap.positive?.let { if (performClickWithFallback(it)) return true }

        for (btn in snap.candidates) {
            val text = btn.text?.toString()?.trim()?.lowercase().orEmpty()
            val desc = btn.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val viewId = btn.viewIdResourceName?.lowercase().orEmpty()

            val isCancel = text.contains("cancel") || desc.contains("cancel") ||
                    text.contains("cancelar") || text.contains("annuler") ||
                    text.contains("abbrechen") || text.contains("취소") ||
                    viewId.endsWith("button2")
            if (isCancel) continue

            val isPositive = text == "ok" || desc == "ok" ||
                    text.contains("force stop") || desc.contains("force stop") ||
                    text == "yes" || desc == "yes" ||
                    text.contains("forzar") || text.contains("forcer") ||
                    text.contains("stopp") || text.contains("beenden") ||
                    text.contains("확인") || text.contains("강제") ||
                    snap.dialogPresent

            if (isPositive && btn.isEnabled && performClickWithFallback(btn)) return true
        }
        return false
    }

    private fun findForceStopNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString().orEmpty()
        findFirst(
            kind = KIND_FORCE_STOP,
            keys = scopedIds(KIND_FORCE_STOP, pkg, FORCE_STOP_ID_NAMES, emptyList()),
            query = { root.findAccessibilityNodeInfosByViewId(it) },
            accept = { isLikelyForceStop(it) }
        )?.let { return it }
        return bfsFindFirst(root, MAX_FORCE_STOP_DEPTH, MAX_FORCE_STOP_QUEUE) { isLikelyForceStop(it) }
    }

    private fun isDialogMarker(text: String, viewId: String, className: String): Boolean =
        text.contains("if you force stop") ||
                text.contains("may misbehave") ||
                text.contains("cause errors") ||
                text.contains("force stop?") ||
                text.contains("forcer l'arrêt ?") ||
                text.contains("¿forzar detención?") ||
                viewId.endsWith("alerttitle") ||
                viewId.contains("parentpanel") ||
                viewId.contains("buttonpanel") ||
                className.contains("dialog", ignoreCase = true)

    private fun isLikelyForceStop(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        return isLikelyForceStop(viewId, text, desc)
    }

    private fun isLikelyForceStop(viewId: String, text: String, desc: String): Boolean {
        // Reject other known action buttons
        if (text.contains("open") || desc.contains("open") ||
            text.contains("uninstall") || desc.contains("uninstall") ||
            text.contains("disable") || desc.contains("disable") ||
            text.contains("cancel") || desc.contains("cancel") ||
            text.contains("storage") || desc.contains("storage")
        ) {
            return false
        }

        // View ID checks for both Pixel and Samsung OneUI
        if (viewId.endsWith("force_stop_button") ||
            viewId.endsWith("btn_force_stop") ||
            viewId.endsWith("bottom_btn_force_stop") ||
            viewId.endsWith("app_control_force_stop") ||
            (viewId.endsWith("right_button") && (text.contains("stop") || desc.contains("stop") || text.isEmpty()))
        ) {
            return true
        }

        return startsWithAny(text, desc, FORCE_STOP_PHRASES)
    }

    // ---------------------------------------------------------------------------------------
    // Clear cache
    // ---------------------------------------------------------------------------------------

    /**
     * Scans for and executes Clear Cache workflow:
     * 1. Priority 1: If "Clear cache" button is visible -> Click it (or detect if already disabled/cleared).
     * 2. Priority 2: If a confirmation dialog is visible -> Click "OK" / "Clear cache".
     * 3. Priority 3: If on App Info page -> Find and click "Storage" / "Storage and cache" / "Storage usage" across OEMs.
     */
    fun scanAndExecuteClearCache(): ClearCacheActionResult {
        val roots = getRelevantRoots()
        if (roots.isEmpty()) return ClearCacheActionResult.NONE

        try {
            for (root in roots) {
                val clearCacheNode = (try { findClearCacheButtonNode(root) } catch (e: Exception) { null }) ?: continue
                try {
                    if (!clearCacheNode.isEnabled) return ClearCacheActionResult.ALREADY_CLEARED
                    if (performClickWithFallback(clearCacheNode)) return ClearCacheActionResult.CLEAR_CACHE_CLICKED
                } finally {
                    safeRecycle(clearCacheNode)
                }
            }

            for (root in roots) {
                val confirmNode = (try { findDialogConfirmNode(root) } catch (e: Exception) { null }) ?: continue
                try {
                    if (performClickWithFallback(confirmNode)) return ClearCacheActionResult.CLEAR_CACHE_CLICKED
                } finally {
                    safeRecycle(confirmNode)
                }
            }

            for (root in roots) {
                val storageNode = (try { findStorageNode(root) } catch (e: Exception) { null }) ?: continue
                try {
                    if (performClickWithFallback(storageNode)) return ClearCacheActionResult.STORAGE_CLICKED
                } finally {
                    safeRecycle(storageNode)
                }
            }

            return ClearCacheActionResult.NONE
        } finally {
            roots.forEach { safeRecycle(it) }
        }
    }

    private fun findClearCacheButtonNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString().orEmpty()
        findFirst(
            kind = KIND_CLEAR_CACHE_ID,
            keys = scopedIds(KIND_CLEAR_CACHE_ID, pkg, CLEAR_CACHE_ID_NAMES, GLOBAL_CLEAR_CACHE_IDS),
            query = { root.findAccessibilityNodeInfosByViewId(it) },
            accept = { true }
        )?.let { return it }

        findFirst(
            kind = KIND_CLEAR_CACHE_TEXT,
            keys = CLEAR_CACHE_SEARCH_TOKENS,
            query = { root.findAccessibilityNodeInfosByText(it) },
            accept = { isClearCacheMatch(it) }
        )?.let { return it }

        return bfsFindFirst(root, 4, 40) { isClearCacheMatch(it) }
    }

    private fun isClearCacheMatch(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.contains("clear_cache") || viewId.contains("button_clear_cache")) return true
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        return startsWithAny(text, desc, CLEAR_CACHE_PHRASES)
    }

    private fun findStorageNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString().orEmpty()
        findFirst(
            kind = KIND_STORAGE_ID,
            keys = scopedIds(KIND_STORAGE_ID, pkg, STORAGE_ID_NAMES, emptyList()),
            query = { root.findAccessibilityNodeInfosByViewId(it) },
            accept = { true }
        )?.let { return it }

        // Text search across OEM phrases (Pixel: "Storage and cache", Samsung: "Storage", OnePlus: "Storage usage", ...)
        findFirst(
            kind = KIND_STORAGE_TEXT,
            keys = STORAGE_SEARCH_TOKENS,
            query = { root.findAccessibilityNodeInfosByText(it) },
            accept = { isStorageMatch(it) }
        )?.let { return it }

        return bfsFindFirst(root, 4, 40) { isStorageMatch(it) }
    }

    private fun isStorageMatch(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.contains("storage_settings") || viewId.contains("manage_storage")) return true
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        return startsWithAny(text, desc, STORAGE_PHRASES)
    }

    private fun findDialogConfirmNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findFirst(
            kind = KIND_CONFIRM_ID,
            keys = CONFIRM_IDS,
            query = { root.findAccessibilityNodeInfosByViewId(it) },
            accept = { true }
        )?.let { return it }

        return findFirst(
            kind = KIND_CONFIRM_TEXT,
            keys = CONFIRM_PHRASES,
            query = { root.findAccessibilityNodeInfosByText(it) },
            accept = { node ->
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                text in CONFIRM_PHRASES
            }
        )
    }

    // ---------------------------------------------------------------------------------------
    // Window / node helpers
    // ---------------------------------------------------------------------------------------

    /** Root nodes of the windows worth inspecting (topmost first). Caller must recycle them. */
    private fun getRelevantRoots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(3)
        var windowCount = 0
        var nullRoots = 0
        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                windowCount = windowList.size
                for (i in windowList.size - 1 downTo 0) {
                    val window = windowList[i]
                    try {
                        val root = window.root
                        if (root == null) {
                            nullRoots++
                        } else if (isRelevantRoot(root)) {
                            list.add(root)
                        } else {
                            safeRecycle(root)
                        }
                    } catch (ignored: Exception) {
                    } finally {
                        try { window.recycle() } catch (ignored: Exception) {}
                    }
                }
            }
        } catch (ignored: Exception) {}

        // ALWAYS also consult the active window. On some OEM builds (Samsung One UI) the interactive
        // `windows` list omits the Settings window or returns a null root for it, while
        // rootInActiveWindow reliably returns it.
        var activePkg: String? = null
        try {
            val active = rootInActiveWindow
            if (active != null) {
                activePkg = active.packageName?.toString()
                if (isRelevantRoot(active) && !list.contains(active)) list.add(active) else safeRecycle(active)
            }
        } catch (ignored: Exception) {}

        debugLog("roots: windows=$windowCount nullRoots=$nullRoots active=$activePkg kept=${list.size}")
        return list
    }

    private fun isRelevantRoot(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        return pkg != packageName && pkg != "com.android.systemui"
    }

    private fun enqueueChildren(node: AccessibilityNodeInfo, depth: Int, queue: ArrayDeque<QueuedNode>) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.addLast(QueuedNode(it, depth)) }
        }
    }

    /** Breadth-first search. Returns the first accepted node (caller recycles); recycles everything else. */
    private fun bfsFindFirst(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        maxQueue: Int,
        accept: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<QueuedNode>()
        enqueueChildren(root, 1, queue)
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val node = item.node
                var matched = false
                try {
                    if (accept(node)) {
                        matched = true
                        return node
                    }
                    if (item.depth < maxDepth && queue.size < maxQueue) {
                        enqueueChildren(node, item.depth + 1, queue)
                    }
                } finally {
                    if (!matched) safeRecycle(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) safeRecycle(queue.removeFirst().node)
        }
        return null
    }

    /**
     * Tries each key (previously successful key first), returning the first node [accept]s.
     * All other returned nodes are recycled. Remembers which key worked for next time.
     */
    private fun findFirst(
        kind: String,
        keys: List<String>,
        query: (String) -> List<AccessibilityNodeInfo>?,
        accept: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val learned = learnedKeys[kind]
        if (learned != null && keys.contains(learned)) {
            pickFirst(query(learned), accept)?.let { return it }
        }
        for (key in keys) {
            if (key == learned) continue
            val hit = pickFirst(query(key), accept)
            if (hit != null) {
                learnedKeys[kind] = key
                return hit
            }
        }
        return null
    }

    private fun pickFirst(
        nodes: List<AccessibilityNodeInfo>?,
        accept: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (nodes.isNullOrEmpty()) return null
        var result: AccessibilityNodeInfo? = null
        for (node in nodes) {
            if (result == null && (try { accept(node) } catch (e: Exception) { false })) {
                result = node
            } else {
                safeRecycle(node)
            }
        }
        return result
    }

    private fun performClickWithFallback(node: AccessibilityNodeInfo): Boolean {
        // 1. Direct click on node
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. Parent click (up to 3 levels)
        var parent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            val nextParent = parent.parent
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                safeRecycle(parent)
                nextParent?.let { safeRecycle(it) }
                return true
            }
            safeRecycle(parent)
            parent = nextParent
            depth++
        }
        parent?.let { safeRecycle(it) }

        // 3. Child click
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val clicked = child.isClickable && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(child)
                if (clicked) return true
            }
        }

        // 4. Force ACTION_CLICK
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 5. Ultimate fallback: Physical gesture tap at screen bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            dispatchGestureClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return true
        }

        return false
    }

    private fun dispatchGestureClick(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (ignored: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        sleepService()
        _isServiceActive.value = false
        setInstance(null)
    }

    companion object {
        private const val TAG = "StopItService"
        private const val EVENT_NOTIFICATION_TIMEOUT_MS = 150L
        private const val MAX_FORCE_STOP_DEPTH = 3
        private const val MAX_FORCE_STOP_QUEUE = 25

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private var instanceRef: WeakReference<ForceStopAccessibilityService>? = null

        val instance: ForceStopAccessibilityService?
            get() = instanceRef?.get()

        fun setInstance(service: ForceStopAccessibilityService?) {
            instanceRef = if (service != null) WeakReference(service) else null
        }

        // ---- change signal: lets automation wait for UI events instead of polling ----
        private val changeSignal = Channel<Unit>(Channel.CONFLATED)

        /** Raises the (conflated) "something changed" signal. Cheap and thread-safe. */
        fun notifyChange() {
            changeSignal.trySend(Unit)
        }

        /** Suspends until the next change signal or the timeout. Returns true if a signal arrived. */
        suspend fun awaitChange(timeoutMs: Long): Boolean =
            withTimeoutOrNull(timeoutMs) { changeSignal.receive() } != null

        fun isAccessibilitySettingsEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${ForceStopAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        // ---- lookup caches ----
        private const val KIND_POSITIVE = "positive"
        private const val KIND_FORCE_STOP = "force_stop"
        private const val KIND_CLEAR_CACHE_ID = "clear_cache_id"
        private const val KIND_CLEAR_CACHE_TEXT = "clear_cache_text"
        private const val KIND_STORAGE_ID = "storage_id"
        private const val KIND_STORAGE_TEXT = "storage_text"
        private const val KIND_CONFIRM_ID = "confirm_id"
        private const val KIND_CONFIRM_TEXT = "confirm_text"

        /** Last key (id or search token) that produced a hit, per lookup kind. */
        private val learnedKeys = ConcurrentHashMap<String, String>()
        private val scopedIdCache = ConcurrentHashMap<String, List<String>>()

        /** Builds "<pkg>:id/<name>" ids for the window's own package (cached), preceded by [global] ids. */
        private fun scopedIds(kind: String, pkg: String, names: List<String>, global: List<String>): List<String> =
            scopedIdCache.getOrPut("$kind|$pkg") {
                val out = ArrayList<String>(global.size + names.size)
                out.addAll(global)
                if (pkg.isNotEmpty()) names.mapTo(out) { "$pkg:id/$it" }
                out
            }

        private fun startsWithAny(text: String, desc: String, phrases: List<String>): Boolean {
            for (phrase in phrases) {
                if (text.startsWith(phrase) || desc.startsWith(phrase)) return true
            }
            return false
        }

        private val POSITIVE_ID_NAMES = listOf("button1", "ok")
        private val GLOBAL_POSITIVE_IDS = listOf("android:id/button1", "android:id/ok")

        private val FORCE_STOP_ID_NAMES = listOf(
            "force_stop_button",
            "right_button",              // Samsung OneUI bottom-right button
            "bottom_btn_force_stop",
            "app_control_force_stop",
            "button3",
            "button_force_stop",
            "btn_force_stop"
        )

        private val CLEAR_CACHE_ID_NAMES = listOf("button_clear_cache", "clear_cache_button", "clear_cache")
        private val GLOBAL_CLEAR_CACHE_IDS = listOf("android:id/button_clear_cache")

        private val STORAGE_ID_NAMES = listOf("storage_settings", "storage", "storage_row", "manage_storage")

        private val CONFIRM_IDS = listOf("android:id/button1", "com.android.settings:id/button1", "android:id/ok")
        private val CONFIRM_PHRASES = listOf("ok", "clear cache", "clear")

        private val FORCE_STOP_PHRASES = listOf(
            "force stop",
            "force-stop",
            "force close",
            "forcer l'arrêt",
            "forcer l'arret",
            "forzar detención",
            "forzar detencion",
            "beenden erzwingen",
            "stopp erzwingen",
            "arresto forzato",
            "forçar parada",
            "forcar parada",
            "forceer stop",
            "wymuś zatrzymanie",
            "принудительно остановить",
            "강제 종료",
            "강제 중지",
            "強制停止",
            "强制停止"
        )

        /**
         * findAccessibilityNodeInfosByText does a case-insensitive substring search, so a handful of
         * short tokens cover every phrase below with far fewer IPC round trips. Results are then
         * filtered with the exact phrase lists.
         */
        private val STORAGE_SEARCH_TOKENS = listOf(
            "storage", "stockage", "speicher", "almacenamiento", "archiviazione",
            "armazenamento", "opslag", "pamięć", "pamiec", "память", "хранилище",
            "저장", "ストレージ", "存储", "儲存空間"
        )

        private val CLEAR_CACHE_SEARCH_TOKENS = listOf(
            "cache", "caché", "pamięć podręczną", "pamiec podreczna", "кэш", "кеш",
            "캐시", "キャッシュ", "缓存", "快取"
        )

        private val STORAGE_PHRASES = listOf(
            "storage and cache",
            "storage & cache",
            "storage usage",
            "storage",
            "internal storage",
            "stockage et cache",
            "stockage",
            "speicher und cache",
            "speicher",
            "almacenamiento y caché",
            "almacenamiento y cache",
            "almacenamiento",
            "spazio di archiviazione",
            "archiviazione e cache",
            "armazenamento e cache",
            "armazenamento",
            "opslag en cache",
            "opslag",
            "pamięć i pamięć podręczna",
            "pamięć",
            "pamiec",
            "память и кэш",
            "хранилище и кэш",
            "память",
            "хранилище",
            "저장용량 및 캐시",
            "저장공간 및 캐시",
            "저장공간",
            "저장용량",
            "ストレージとキャッシュ",
            "ストレージ",
            "存储和缓存",
            "存储占用",
            "存储",
            "儲存空間與快取",
            "儲存空間"
        )

        private val CLEAR_CACHE_PHRASES = listOf(
            "clear cache",
            "clean cache",
            "clear cached data",
            "vider le cache",
            "cache leeren",
            "limpiar caché",
            "limpiar cache",
            "svuota cache",
            "limpar cache",
            "wis pamięć podręczną",
            "wyczyść pamięć podręczną",
            "wyczysc pamiec podreczna",
            "очистить кэш",
            "очистить кеш",
            "캐시 지우기",
            "キャッシュを消去",
            "キャッシュをクリア",
            "清除缓存",
            "清理缓存",
            "清除快取",
            "清理快取"
        )
    }
}
