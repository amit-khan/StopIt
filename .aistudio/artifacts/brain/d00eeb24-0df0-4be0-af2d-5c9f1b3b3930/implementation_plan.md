# Accessibility Service & Automation Optimization Plan

A comprehensive performance and lifecycle refactoring for `ForceStopAccessibilityService`, `AutomationManager`, and `CacheCleanerAutomationManager` to eliminate background flag leaks, redundant binder IPC calls, potential unhandled wake states, content-change event flood, and per-scan object allocations—while keeping 100% of the core automation functionality intact.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Scope & Approach**:
> - **Optimization Scope**: Apply all 5 performance, memory, and safety improvements.
> - **Functional Guarantee**: All OEM heuristics (Pixel, Samsung OneUI, OnePlus, Xiaomi) and UI behaviors (bulk stopping, single-app cache cleaner, HUD overlay, whitelist protection) will remain completely intact.

---

## 1. Overview & Core Concept

- **Problem**: When idle, the accessibility service previously retained interactive window-tracking flags in the Android OS accessibility framework. During automation, high-frequency `TYPE_WINDOW_CONTENT_CHANGED` events triggered duplicate scans in parallel with the polling loop. Additionally, memory was repeatedly allocated for phrase lists on every loop iteration, and abnormal task cancellations risked leaving the service awake.
- **Deliverable**:
  1. **Zero-Power Sleep Mode**: Explicitly clear all flags (`flags = 0`) and event types (`eventTypes = 0`) when entering sleep mode so Android ceases all window tracking.
  2. **Idempotent Wake/Sleep State**: Guard wake and sleep transitions with an internal atomic state flag to eliminate redundant IPC binder calls.
  3. **Guaranteed Cleanup**: Wrap automation executions in `try ... finally` blocks across both `AutomationManager` and `CacheCleanerAutomationManager` to guarantee `sleepService()` is always executed.
  4. **Static Phrase Caching**: Move all phrase and ID lists into companion object immutable constants, eliminating garbage collection pressure during scans.

---

## 2. User Experience & Visual Design

- **Visual Fidelity**: No UI changes. The appearance, controls, progress indicators, and animations in the app remain identical.
- **Performance Impact**:
  - Significantly reduced CPU utilization during bulk force-stopping and cache clearing.
  - Zero background battery drain when the app is idle.
  - Eliminated potential race conditions between the main event dispatch thread and background coroutines.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Clear Flags on Sleep (`flags = 0`)**
  - *Chosen Approach*: In `sleepService()`, set `info.flags = 0` in addition to `info.eventTypes = 0`.
  - *Why*: Setting `eventTypes = 0` stops event delivery to our service, but without resetting `flags = 0`, Android's system server continues tracking interactive windows and view IDs on every system window change. Resetting flags frees the OS from tracking windows for our service.
- **Decision 2: Internal Wake State Tracking**
  - *Chosen Approach*: Maintain a `private var isServiceAwake = false` flag in `ForceStopAccessibilityService`.
  - *Why*: Prevents re-assigning `serviceInfo = info` if the service is already awake or already asleep, eliminating redundant binder IPC transactions.
- **Decision 3: Robust `try ... finally` Lifecycle Guarantee**
  - *Chosen Approach*: Enclose the automation loops in `AutomationManager` and `CacheCleanerAutomationManager` with `try { ... } finally { ForceStopAccessibilityService.instance?.sleepService() }`.
  - *Why*: Ensures that even if a coroutine is cancelled, an unhandled exception occurs, or the user navigates away, the accessibility service is unconditionally returned to sleep mode.
- **Decision 4: Static Phrase & ID Constants**
  - *Chosen Approach*: Move `dialogIds`, `positiveIds`, `stopPhrases`, `oemResourceIds`, `storagePhrases`, and `clearCachePhrases` into `companion object` constants (`List<String>` / `Set<String>`).
  - *Why*: Eliminates thousands of short-lived list allocations during scans, reducing GC pauses and improving execution speed.

---

## 4. Technical Architecture & Implementation Steps

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   Optimized Accessibility & Automation Flow                 │
│                                                                             │
│  Automation Trigger (Bulk Stop / Clear Cache)                               │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ try {                                                                 │  │
│  │   service.wakeForAutomation()                                         │  │
│  │     - If already awake: NO-OP (no redundant IPC)                      │  │
│  │     - Set eventTypes = TYPE_WINDOW_CONTENT_CHANGED                    │  │
│  │     - Set flags = FLAG_RETRIEVE_INTERACTIVE_WINDOWS                   │  │
│  │   Execute Automation Loop (80ms interval)                             │  │
│  │     - References static companion object phrase constants             │  │
│  │     - Zero per-frame allocations                                      │  │
│  │ } finally {                                                           │  │
│  │   service.sleepService()                                              │  │
│  │     - If already asleep: NO-OP                                        │  │
│  │     - Set eventTypes = 0                                              │  │
│  │     - Set flags = 0 (completely unhooks OS window tracking)           │  │
│  │ }                                                                     │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Files to Update
1. `app/src/main/java/com/example/service/ForceStopAccessibilityService.kt`:
   - Add `isServiceAwake` check in `wakeForAutomation()` and `sleepService()`.
   - Clear `info.flags = 0` in `sleepService()`.
   - In `wakeForAutomation()`, register only `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`.
   - Move method-local `listOf(...)` collections into `companion object` constants.
2. `app/src/main/java/com/example/service/AutomationManager.kt`:
   - Wrap the coroutine execution inside `try { ... } finally { ForceStopAccessibilityService.instance?.sleepService() }`.
3. `app/src/main/java/com/example/service/CacheCleanerAutomationManager.kt`:
   - Wrap cache cleaning execution inside `try { ... } finally { ForceStopAccessibilityService.instance?.sleepService() }`.

### Verification Plan
- **Compilation**: Verify build with `compile_applet`.
- **Unit Testing**: Run `gradle :app:testDebugUnitTest` to ensure all existing test suites pass without regression.
