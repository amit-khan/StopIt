# App Cache Cleaner & Storage Stats Implementation Plan

This plan establishes a dedicated, reliable automation pipeline and storage inspection architecture for Stop it to clear individual application cache data in one tap, and display real-time cache sizes for listed applications.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The plan incorporates your confirmed decisions from Phase 1:
> - **Cache Size Measurement**: Declare `PACKAGE_USAGE_STATS` and integrate Android's `StorageStatsManager` to inspect and display real on-device cache sizes (KB/MB) per app. A seamless permission prompt will guide users to the "Usage Access" settings screen when needed.
> - **Post-Clearing Navigation**: After successfully clicking "Clear cache" on the target app's storage page, the automation will remain on the app settings page as requested, smoothly entering low-power sleep mode without forcing back navigation.
> - **Dedicated Single-App Automation Engine**: Keep the code DRY and modular by isolating the cache-clearing flow into a dedicated `CacheCleanerAutomationManager` file, distinct from the bulk force-stop queue logic.

---

## 1. Overview & Core Concept

- **What It Does**: Adds a dedicated cache cleaning action (bin icon) to each app row in both the Running Apps and Whitelist screens. Tapping the bin icon opens the app's settings, navigates to the Storage page (handling OEM differences across Google Pixel and Samsung OneUI), and clicks "Clear cache". Displays real-time formatted cache sizes (e.g., `14.2 MB`, `850 KB`) directly in the app list when Usage Access is enabled.
- **Target Audience / Persona**: Users seeking to quickly reclaim disk space and remove bloated app caches without manually digging through nested Android system settings menus.
- **Key Value**: One-tap cache cleaning and visibility into disk bloat across both active and whitelisted background applications.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Viewing Cache Sizes**:
   - Each app row displays the App Icon, App Title, and a secondary caption showing formatted cache size (e.g. `Cache: 18.4 MB` or `Cache: < 1 MB`).
   - If Usage Access is not yet granted, a discrete info banner or chip at the top of the list prompts the user: *"Tap to show app cache sizes (Requires Usage Access)"*. Clicking it directs straight to `Settings.ACTION_USAGE_ACCESS_SETTINGS`.
2. **Clearing an App's Cache**:
   - User taps the bin icon (`DeleteOutline`) on any app row.
   - The app launches `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the target package.
   - `CacheCleanerAutomationManager` coordinates with `ForceStopAccessibilityService`:
     - **Step 1**: Detects and clicks `"Storage and cache"` (Google Pixel / stock Android) or `"Storage"` (Samsung OneUI / other OEMs).
     - **Step 2**: Waits for the storage sub-page to render and locates the `"Clear cache"` button (or OEM localized equivalents).
     - **Step 3**: Clicks `"Clear cache"`, verifies execution, puts the service back to sleep, and stays on the settings page as requested.
   - A haptic pulse and completion toast/snackbar confirm that the cache was cleared.

### Visual Identity & Layout

- **Bin Icon Placement**: Positioned immediately to the left of the Checkbox (Running Apps) or Toggle Switch (Whitelist) on each row.
- **Touch Target & Styling**: 40dp x 40dp interactive component with ripple effect, styled with `TextSecondary` (or subtle `AccentRed.copy(alpha = 0.85f)` accent) so it does not overwhelm the primary row actions.
- **Cache Size Typography**: Material 3 `labelSmall` with `TextSecondary` directly below the app title, maintaining a neat 2-line layout without increasing overall row height unnecessarily.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Dedicated `CacheCleanerAutomationManager.kt` vs. Modifying `AutomationManager.kt`**
  - *Chosen Approach*: Create a separate `CacheCleanerAutomationManager` file.
  - *Why*: The existing `AutomationManager` is a stateful queue runner designed for bulk force-stop operations with pause/resume HUD, multiple attempts, and task reordering. Single-app cache clearing is an on-demand, targeted two-step navigation action. Separating it keeps both managers focused, maintainable, and DRY.
- **Decision 2: Dual OEM Heuristics (Pixel vs. Samsung OneUI)**
  - *Chosen Approach*: Search for `"Storage and cache"` first (Pixel), fallback to `"Storage"` (Samsung OneUI), followed by common resource ID matchers (`*storage*`). Once in the storage window, target `"Clear cache"` (and resource ID `*clear_cache*`).
  - *Why*: Eliminates OEM fragmentation errors without hardcoding device models.
- **Decision 3: Asynchronous Cache Size Loading**
  - *Chosen Approach*: Query `StorageStatsManager.queryStatsForPackage()` in background coroutines on `Dispatchers.IO`, caching results in memory and updating `AppInfoItem.cacheSizeBytes`.
  - *Why*: Storage stats queries can take 20-50ms per package. Offloading to background coroutines prevents any frame drops or list stuttering.

---

## 4. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Cache Cleaner & Storage Flow                          │
│                                                                             │
│  UI Layer (Compose)                                                         │
│  ┌───────────────────────┐         ┌──────────────────────────────────────┐ │
│  │ AppRowItem            │         │ WhitelistRowItem                     │ │
│  │ [Icon] [Name + Cache] │         │ [Icon] [Name + Cache]                │ │
│  │ [Bin Icon] [Checkbox] │         │ [Bin Icon]    [Switch]               │ │
│  └───────────┬───────────┘         └──────────────────┬───────────────────┘ │
│              │ onClearCacheClick                      │ onClearCacheClick   │
│              ▼                                        ▼                     │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ MainViewModel                                                          │ │
│  │ - clearAppCache(packageName)                                           │ │
│  │ - checkUsageAccessAndLoadCacheSizes()                                  │ │
│  └───────────────────────────────────┬────────────────────────────────────┘ │
│                                      │                                      │
│  Automation & Service Layer          ▼                                      │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ CacheCleanerAutomationManager (New File)                               │ │
│  │ 1. Opens Settings.ACTION_APPLICATION_DETAILS_SETTINGS                  │ │
│  │ 2. Wakes ForceStopAccessibilityService                                 │ │
│  │ 3. Step 1: Scan for "Storage and cache" / "Storage" -> Click           │ │
│  │ 4. Step 2: Scan for "Clear cache" button -> Click                      │ │
│  │ 5. Remains on settings page & puts service to sleep                    │ │
│  └───────────────────────────────────┬────────────────────────────────────┘ │
│                                      │                                      │
│  Storage Stats Layer                 ▼                                      │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ StorageStatsHelper / AppRepository                                     │ │
│  │ - Checks AppOpsManager.OPSTR_GET_USAGE_STATS                           │ │
│  │ - StorageStatsManager.queryStatsForPackage() on Dispatchers.IO         │ │
│  │ - Formats bytes to KB/MB (e.g. Formatter.formatFileSize)               │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Components & Responsibilities

1. **`AndroidManifest.xml`**:
   - Add `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />`.
2. **`CacheCleanerAutomationManager.kt`**:
   - Manages state, launch coroutines, and step sequencing (Step 1: Open Details -> Step 2: Click Storage -> Step 3: Click Clear Cache).
   - Safe timeouts (3000ms max per step) and clean resource recycling.
3. **`ForceStopAccessibilityService.kt`**:
   - Exposes `clickStorageNode(root)` and `clickClearCacheNode(root)` with node recycling and fallback gesture taps.
4. **`AppInfoItem.kt`**:
   - Adds `val cacheSizeBytes: Long? = null` and helper `formattedCacheSize: String?`.
5. **`AppRepository.kt` & `MainViewModel.kt`**:
   - Batch-fetches cache stats in background when Usage Access is granted.
   - Provides `hasUsageAccessPermission()` and `openUsageAccessSettings()`.
6. **`AppRowItem.kt` & `WhitelistRowItem.kt`**:
   - Adds bin icon button with proper test tags (`app_clear_cache_<pkg>` and `whitelist_clear_cache_<pkg>`).
   - Renders cache size text when available.

---

## 5. Technical Verification & Testing

1. **Compilation Check**: Run `compile_applet` to verify all imports, permissions, and Compose layouts compile cleanly.
2. **Unit Tests**: Run `gradle :app:testDebugUnitTest` to ensure all existing unit tests pass without regression.
3. **Automation Heuristics Verification**: Verify that both Pixel ("Storage and cache") and Samsung OneUI ("Storage") node labels are matched case-insensitively, and that "Clear cache" is clicked accurately.
4. **Permission & Fallback Verification**: Verify that when Usage Access is absent, the app continues to operate without crashes and gracefully displays sizes once granted.
