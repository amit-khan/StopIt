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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

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

private enum class DialogScanResult {
    CLICKED,
    DIALOG_PRESENT,
    NO_DIALOG
}

class ForceStopAccessibilityService : AccessibilityService() {

    private var isServiceAwake = false
    private val isScanning = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        setInstance(this)
        // Start in sleep mode so the service consumes virtually zero RAM/CPU when idle
        sleepService()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        sleepService()
        isServiceAwake = false
        _isServiceActive.value = false
        setInstance(null)
        return super.onUnbind(intent)
    }

    /**
     * Awakens the service when automation begins:
     * Restricts event filtering strictly to window state and window content changes.
     * Does NOT include FLAG_INCLUDE_NOT_IMPORTANT_VIEWS to keep node allocations minimal.
     * Avoids redundant IPC binder transactions if already awake.
     */
    fun wakeForAutomation() {
        if (isServiceAwake) return
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100
            serviceInfo = info
            isServiceAwake = true
        } catch (ignored: Exception) {}
    }

    /**
     * Puts the service into low-memory sleep mode when idle:
     * Disables event delivery (eventTypes = 0) and resets flags to 0 so the OS completely
     * stops tracking interactive windows and view IDs when dormant.
     * Avoids redundant IPC binder transactions if already asleep.
     */
    fun sleepService() {
        if (!isServiceAwake && serviceInfo?.eventTypes == 0 && serviceInfo?.flags == 0) return
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = 0
            info.flags = 0
            serviceInfo = info
            isServiceAwake = false
        } catch (ignored: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val currentAutomatingApp = AutomationManager.currentAppPackage.value ?: return
        if (AutomationManager.isPaused.value) return

        // Transiently inspect event source without caching it in long-lived state
        val source = try { event.source } catch (e: Exception) { null }
        scanAndExecute(currentAutomatingApp, source)
    }

    /**
     * Checks if the "Force stop" button on the current App Info page is disabled,
     * indicating that the app is now force-stopped.
     */
    fun isForceStopDisabled(): Boolean {
        val roots = getAllActiveRootNodes(null)
        if (roots.isEmpty()) return false

        try {
            for (root in roots) {
                val node = findForceStopButtonNode(root)
                if (node != null) {
                    val disabled = !node.isEnabled
                    try { node.recycle() } catch (ignored: Exception) {}
                    if (disabled) {
                        return true
                    }
                }
            }
            return false
        } finally {
            for (root in roots) {
                try { root.recycle() } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Actively scans window hierarchies with guaranteed recycling:
     * 1. If confirmation dialog is visible -> Clicks positive button ("OK" / "Force stop" / button1 / gesture)
     * 2. If a dialog is on screen but not yet clicked -> WAITS (blocks touching background button)
     * 3. If NO dialog is present -> Finds and clicks "Force stop" button on App Info page
     *
     * Returns ForceStopActionResult indicating exact action taken for multi-attempt handling.
     */
    fun scanAndExecute(targetPackage: String, triggerSource: AccessibilityNodeInfo? = null): ForceStopActionResult {
        // Prevent concurrent overlapping scans/clicks from onAccessibilityEvent and polling loop
        if (!isScanning.compareAndSet(false, true)) {
            try { triggerSource?.recycle() } catch (ignored: Exception) {}
            return ForceStopActionResult.NONE
        }

        try {
            val roots = getAllActiveRootNodes(triggerSource)
            if (roots.isEmpty()) return ForceStopActionResult.NONE

            try {
                // PHASE 1A: Ultra-fast ID check for standard confirmation dialog positive buttons across all roots
                for (root in roots) {
                    try {
                        if (tryClickPositiveButtonById(root)) {
                            return ForceStopActionResult.CONFIRMATION_CLICKED
                        }
                    } catch (ignored: Exception) {}
                }

                // PHASE 1B + 2: Single-pass combined dialog detection & fallback positive-button scan
                var dialogDetected = false
                for (root in roots) {
                    try {
                        when (inspectAndHandleConfirmationDialog(root)) {
                            DialogScanResult.CLICKED -> return ForceStopActionResult.CONFIRMATION_CLICKED
                            DialogScanResult.DIALOG_PRESENT -> dialogDetected = true
                            DialogScanResult.NO_DIALOG -> {}
                        }
                    } catch (ignored: Exception) {}
                }

                // If a confirmation dialog is on screen, DO NOT touch background Force Stop button
                if (dialogDetected) {
                    return ForceStopActionResult.NONE
                }

                // PHASE 3: Only when NO dialog is on screen, search for and click Force Stop button in App Info
                for (root in roots) {
                    try {
                        val result = handleForceStopButton(root, targetPackage)
                        if (result != ForceStopActionResult.NONE) {
                            return result
                        }
                    } catch (ignored: Exception) {}
                }

                return ForceStopActionResult.NONE
            } finally {
                // Guarantee immediate recycling of every root node
                for (root in roots) {
                    try {
                        root.recycle()
                    } catch (ignored: Exception) {}
                }
            }
        } finally {
            isScanning.set(false)
        }
    }

    /**
     * Scans for and executes Clear Cache workflow:
     * 1. Priority 1: If "Clear cache" button is visible -> Click it (or detect if already disabled/cleared).
     * 2. Priority 2: If a confirmation dialog is visible -> Click "OK" / "Clear cache".
     * 3. Priority 3: If on App Info page -> Find and click "Storage" / "Storage and cache" / "Storage usage" across OEMs.
     */
    fun scanAndExecuteClearCache(): ClearCacheActionResult {
        val roots = getAllActiveRootNodes(null)
        if (roots.isEmpty()) return ClearCacheActionResult.NONE

        try {
            // 1. Check if "Clear cache" button is already present on the storage page
            for (root in roots) {
                val clearCacheNode = findClearCacheButtonNode(root)
                if (clearCacheNode != null) {
                    try {
                        if (!clearCacheNode.isEnabled) {
                            return ClearCacheActionResult.ALREADY_CLEARED
                        }
                        if (performClickWithFallback(clearCacheNode)) {
                            return ClearCacheActionResult.CLEAR_CACHE_CLICKED
                        }
                    } finally {
                        try { clearCacheNode.recycle() } catch (ignored: Exception) {}
                    }
                }
            }

            // 2. Check if a confirmation dialog is visible (e.g. Xiaomi / OEM dialog asking "Clear cache?")
            for (root in roots) {
                val confirmNode = findDialogConfirmNode(root)
                if (confirmNode != null) {
                    try {
                        if (performClickWithFallback(confirmNode)) {
                            return ClearCacheActionResult.CLEAR_CACHE_CLICKED
                        }
                    } finally {
                        try { confirmNode.recycle() } catch (ignored: Exception) {}
                    }
                }
            }

            // 3. On App Info page, find and click "Storage" / "Storage and cache" / "Storage usage"
            for (root in roots) {
                val storageNode = findStorageNode(root)
                if (storageNode != null) {
                    try {
                        if (performClickWithFallback(storageNode)) {
                            return ClearCacheActionResult.STORAGE_CLICKED
                        }
                    } finally {
                        try { storageNode.recycle() } catch (ignored: Exception) {}
                    }
                }
            }

            return ClearCacheActionResult.NONE
        } finally {
            for (root in roots) {
                try { root.recycle() } catch (ignored: Exception) {}
            }
        }
    }

    private fun getAllActiveRootNodes(triggerSource: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val ownPackage = packageName

        // 1. Add triggerSource if provided and not belonging to our own app
        if (triggerSource != null) {
            val srcPkg = triggerSource.packageName?.toString()
            if (srcPkg != ownPackage) {
                list.add(triggerSource)
            } else {
                try { triggerSource.recycle() } catch (ignored: Exception) {}
            }
        }

        // 2. Inspect relevant interactive windows in reverse order (topmost / newest dialog first),
        // prioritizing TYPE_APPLICATION windows over TYPE_SYSTEM and skipping IME/overlays/SystemUI bars
        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                val systemRoots = mutableListOf<AccessibilityNodeInfo>()
                for (window in windowList.reversed()) {
                    val wType = window.type
                    if (wType != AccessibilityWindowInfo.TYPE_APPLICATION &&
                        wType != AccessibilityWindowInfo.TYPE_SYSTEM
                    ) {
                        continue
                    }
                    window.root?.let { node ->
                        val nodePkg = node.packageName?.toString()
                        val shouldSkip = nodePkg == ownPackage ||
                                (wType == AccessibilityWindowInfo.TYPE_SYSTEM && nodePkg == "com.android.systemui")
                        if (shouldSkip || list.contains(node) || systemRoots.contains(node)) {
                            try { node.recycle() } catch (ignored: Exception) {}
                        } else if (wType == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            list.add(node)
                        } else {
                            systemRoots.add(node)
                        }
                    }
                }
                list.addAll(systemRoots)
            }
        } catch (ignored: Exception) {}

        // 3. Add rootInActiveWindow if not already in list and not our own app
        try {
            rootInActiveWindow?.let {
                if (it.packageName?.toString() != ownPackage && !list.contains(it)) {
                    list.add(it)
                } else {
                    try { it.recycle() } catch (ignored: Exception) {}
                }
            }
        } catch (ignored: Exception) {}

        return list
    }

    private fun tryClickPositiveButtonById(root: AccessibilityNodeInfo): Boolean {
        for (id in POSITIVE_BUTTON_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                try {
                    if (node.isEnabled && performClickWithFallback(node)) {
                        for (other in nodes) {
                            try { other.recycle() } catch (ignored: Exception) {}
                        }
                        return true
                    }
                } catch (ignored: Exception) {}
            }
            for (node in nodes) {
                try { node.recycle() } catch (ignored: Exception) {}
            }
        }
        return false
    }

    private fun inspectAndHandleConfirmationDialog(root: AccessibilityNodeInfo): DialogScanResult {
        var hasDialogContent = false

        // Fast-path: targeted ID check for alert dialog titles and panels
        for (id in CONFIRMATION_DIALOG_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    try { node.recycle() } catch (ignored: Exception) {}
                }
                hasDialogContent = true
                break
            }
        }

        // Single shallow traversal to detect dialog markers and locate fallback positive button
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(Pair(it, 1)) }
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()

        try {
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                val className = node.className?.toString().orEmpty()

                if (!hasDialogContent && (
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
                    )
                ) {
                    hasDialogContent = true
                }

                // If ID ends with button1, click it immediately
                if ((viewId.endsWith(":id/button1") || viewId.endsWith("/button1")) && node.isEnabled) {
                    if (performClickWithFallback(node)) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return DialogScanResult.CLICKED
                    }
                }

                if (depth < 3 && queue.size < 25) {
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
                    }
                }

                val isClickableOrButton = className.contains("Button", ignoreCase = true) ||
                        className.contains("TextView", ignoreCase = true) ||
                        node.isClickable
                if (isClickableOrButton && (text.isNotEmpty() || desc.isNotEmpty())) {
                    candidates.add(node)
                } else {
                    try { node.recycle() } catch (ignored: Exception) {}
                }
            }

            // Evaluate candidates
            for (btn in candidates) {
                val text = btn.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = btn.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                val viewId = btn.viewIdResourceName?.lowercase().orEmpty()

                val isCancel = text == "cancel" || desc == "cancel" ||
                        text.contains("cancel") || desc.contains("cancel") ||
                        text.contains("cancelar") || text.contains("annuler") ||
                        text.contains("abbrechen") || text.contains("취소") ||
                        viewId.endsWith("button2")

                if (isCancel) continue

                val isPositive = text == "ok" || desc == "ok" ||
                        text == "force stop" || desc == "force stop" ||
                        text.contains("force stop") || desc.contains("force stop") ||
                        text == "yes" || desc == "yes" ||
                        text.contains("forzar") || text.contains("forcer") ||
                        text.contains("stopp") || text.contains("beenden") ||
                        text.contains("확인") || text.contains("강제") ||
                        hasDialogContent

                if (isPositive && btn.isEnabled) {
                    if (performClickWithFallback(btn)) {
                        return DialogScanResult.CLICKED
                    }
                }
            }

            return if (hasDialogContent) DialogScanResult.DIALOG_PRESENT else DialogScanResult.NO_DIALOG
        } finally {
            for (btn in candidates) {
                try { btn.recycle() } catch (ignored: Exception) {}
            }
            while (queue.isNotEmpty()) {
                val (node, _) = queue.removeFirst()
                try { node.recycle() } catch (ignored: Exception) {}
            }
        }
    }

    private fun handleForceStopButton(root: AccessibilityNodeInfo, pkgName: String): ForceStopActionResult {
        val targetNode = findForceStopButtonNode(root) ?: return ForceStopActionResult.NONE

        try {
            if (!targetNode.isEnabled) {
                // Button is disabled -> App is ALREADY stopped!
                return ForceStopActionResult.ALREADY_DISABLED
            }

            // Button is enabled -> Click it!
            val clicked = performClickWithFallback(targetNode)
            if (clicked) {
                return ForceStopActionResult.FORCE_STOP_CLICKED
            }
            return ForceStopActionResult.NONE
        } finally {
            try { targetNode.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun findForceStopButtonNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in OEM_FORCE_STOP_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (isLikelyForceStop(node)) {
                    for (other in nodes) {
                        if (other != node) {
                            try { other.recycle() } catch (ignored: Exception) {}
                        }
                    }
                    return node // Caller will recycle targetNode
                } else {
                    try { node.recycle() } catch (ignored: Exception) {}
                }
            }
        }

        // Shallow traversal (max depth 3, max 25 nodes)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(Pair(it, 1)) }
        }

        try {
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()

                if (isLikelyForceStop(node)) {
                    while (queue.isNotEmpty()) {
                        val (rem, _) = queue.removeFirst()
                        try { rem.recycle() } catch (ignored: Exception) {}
                    }
                    return node
                }

                if (depth < 3 && queue.size < 25) {
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
                    }
                }

                try { node.recycle() } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            while (queue.isNotEmpty()) {
                val (rem, _) = queue.removeFirst()
                try { rem.recycle() } catch (ignored: Exception) {}
            }
        }

        return null
    }

    private fun isLikelyForceStop(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()

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

        // Multilingual text checks
        for (phrase in STOP_PHRASES) {
            if (text == phrase || desc == phrase || text.startsWith(phrase) || desc.startsWith(phrase)) {
                return true
            }
        }

        return false
    }

    private fun findClearCacheButtonNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in CLEAR_CACHE_TARGET_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val result = nodes.first()
                for (i in 1 until nodes.size) {
                    try { nodes[i].recycle() } catch (ignored: Exception) {}
                }
                return result
            }
        }

        // Fast text search
        for (phrase in CLEAR_CACHE_PHRASES) {
            val nodes = root.findAccessibilityNodeInfosByText(phrase)
            for (node in nodes) {
                if (isClearCacheMatch(node)) {
                    for (other in nodes) {
                        if (other != node) {
                            try { other.recycle() } catch (ignored: Exception) {}
                        }
                    }
                    return node
                }
                try { node.recycle() } catch (ignored: Exception) {}
            }
        }

        // Shallow hierarchy traversal capped at depth 4
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(Pair(it, 1)) }
        }

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (isClearCacheMatch(node)) {
                while (queue.isNotEmpty()) {
                    try { queue.removeFirst().first.recycle() } catch (ignored: Exception) {}
                }
                return node
            }

            if (depth < 4 && queue.size < 40) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
                }
            }
            try { node.recycle() } catch (ignored: Exception) {}
        }

        return null
    }

    private fun isClearCacheMatch(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()

        if (viewId.contains("clear_cache") || viewId.contains("button_clear_cache")) {
            return true
        }

        for (phrase in CLEAR_CACHE_PHRASES) {
            if (text == phrase || desc == phrase || text.startsWith(phrase) || desc.startsWith(phrase)) {
                return true
            }
        }
        return false
    }

    private fun findStorageNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in STORAGE_TARGET_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val result = nodes.first()
                for (i in 1 until nodes.size) {
                    try { nodes[i].recycle() } catch (ignored: Exception) {}
                }
                return result
            }
        }

        // Text search across OEM phrases (Pixel: "Storage and cache", Samsung: "Storage", OnePlus: "Storage usage", Xiaomi: "Storage", etc.)
        for (phrase in STORAGE_PHRASES) {
            val nodes = root.findAccessibilityNodeInfosByText(phrase)
            for (node in nodes) {
                if (isStorageMatch(node)) {
                    for (other in nodes) {
                        if (other != node) {
                            try { other.recycle() } catch (ignored: Exception) {}
                        }
                    }
                    return node
                }
                try { node.recycle() } catch (ignored: Exception) {}
            }
        }

        // Traversal
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(Pair(it, 1)) }
        }

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (isStorageMatch(node)) {
                while (queue.isNotEmpty()) {
                    try { queue.removeFirst().first.recycle() } catch (ignored: Exception) {}
                }
                return node
            }

            if (depth < 4 && queue.size < 40) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
                }
            }
            try { node.recycle() } catch (ignored: Exception) {}
        }

        return null
    }

    private fun isStorageMatch(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()

        if (viewId.contains("storage_settings") || viewId.contains("manage_storage")) {
            return true
        }

        for (phrase in STORAGE_PHRASES) {
            if (text == phrase || desc == phrase || text.startsWith(phrase) || desc.startsWith(phrase)) {
                return true
            }
        }
        return false
    }

    private fun findDialogConfirmNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in DIALOG_CONFIRM_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val result = nodes.first()
                for (i in 1 until nodes.size) {
                    try { nodes[i].recycle() } catch (ignored: Exception) {}
                }
                return result
            }
        }

        for (phrase in CONFIRM_PHRASES) {
            val nodes = root.findAccessibilityNodeInfosByText(phrase)
            for (node in nodes) {
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                if (text == phrase) {
                    for (other in nodes) {
                        if (other != node) {
                            try { other.recycle() } catch (ignored: Exception) {}
                        }
                    }
                    return node
                }
                try { node.recycle() } catch (ignored: Exception) {}
            }
        }
        return null
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
                try { parent.recycle() } catch (ignored: Exception) {}
                try { nextParent?.recycle() } catch (ignored: Exception) {}
                return true
            }
            try { parent.recycle() } catch (ignored: Exception) {}
            parent = nextParent
            depth++
        }
        try { parent?.recycle() } catch (ignored: Exception) {}

        // 3. Child click
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val clicked = child.isClickable && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                try { child.recycle() } catch (ignored: Exception) {}
                if (clicked) {
                    return true
                }
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
            val path = Path().apply {
                moveTo(x, y)
            }
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
        isServiceAwake = false
        _isServiceActive.value = false
        setInstance(null)
    }

    companion object {
        val CONFIRMATION_DIALOG_IDS = listOf(
            "android:id/alertTitle",
            "android:id/parentPanel",
            "android:id/buttonPanel",
            "com.android.settings:id/parentPanel"
        )

        val POSITIVE_BUTTON_IDS = listOf(
            "android:id/button1",
            "com.android.settings:id/button1",
            "com.samsung.android.settings:id/button1",
            "com.google.android.settings:id/button1",
            "android:id/ok",
            "com.android.settings:id/ok"
        )

        val OEM_FORCE_STOP_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.google.android.settings:id/force_stop_button",
            "com.samsung.android.settings:id/force_stop_button",
            "com.android.settings:id/right_button",           // Samsung OneUI bottom-right button
            "com.samsung.android.settings:id/right_button",   // Samsung OneUI bottom-right button
            "com.samsung.android.settings:id/bottom_btn_force_stop",
            "com.samsung.android.settings:id/app_control_force_stop",
            "com.android.settings:id/button3",
            "com.android.settings:id/button_force_stop",
            "com.android.settings:id/btn_force_stop"
        )

        val STOP_PHRASES = listOf(
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

        val CLEAR_CACHE_TARGET_IDS = listOf(
            "com.android.settings:id/button_clear_cache",
            "com.android.settings:id/clear_cache_button",
            "com.android.settings:id/clear_cache",
            "android:id/button_clear_cache",
            "com.miui.securitycenter:id/clear_cache"
        )

        val STORAGE_TARGET_IDS = listOf(
            "com.android.settings:id/storage_settings",
            "com.android.settings:id/storage",
            "com.android.settings:id/storage_row",
            "com.android.settings:id/manage_storage",
            "com.coloros.safecenter:id/storage",
            "com.miui.securitycenter:id/storage"
        )

        val DIALOG_CONFIRM_IDS = listOf(
            "android:id/button1",
            "com.android.settings:id/button1",
            "android:id/ok"
        )

        val CONFIRM_PHRASES = listOf("ok", "clear cache", "clear")

        val STORAGE_PHRASES = listOf(
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

        val CLEAR_CACHE_PHRASES = listOf(
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

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private var instanceRef: WeakReference<ForceStopAccessibilityService>? = null

        val instance: ForceStopAccessibilityService?
            get() = instanceRef?.get()

        fun setInstance(service: ForceStopAccessibilityService?) {
            instanceRef = if (service != null) WeakReference(service) else null
        }

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
    }
}
