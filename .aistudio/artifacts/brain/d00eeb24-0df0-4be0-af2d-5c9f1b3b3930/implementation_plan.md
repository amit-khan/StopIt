# APK Size Optimization & Dependency Trimming Plan

A targeted optimization strategy to trim the Stop it APK footprint from **~17 MB down to ~2–3 MB** (an ~85% reduction) by removing bloated icon libraries, pruning unused dependencies, and converting sparse icon references into lightweight local vector drawables, without changing any app behavior or functionality.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Key Finding — Source of Bloat Identified:**
> - Inspection of `.build-outputs/app-debug.apk` revealed that DEX classes alone consume **over 55 MB uncompressed** across 9 multidex files.
> - The primary culprit is `libs.androidx.compose.material.icons.extended` in `app/build.gradle.kts`. This single dependency packages over 10,000 Material icons into DEX, adding **~14 MB** of compressed overhead to the APK.
> - The app only utilizes **7 icons** from this extended set: `PowerSettingsNew`, `Shield`, `AccessibilityNew`, `Pause`, `CheckCircle`, `Android`, and `Outlined.Delete`.
> - **The Solution**: We will extract and bundle these 7 icons as native, compact Android XML vector drawables in `app/src/main/res/drawable/` (< 15 KB total) and completely remove `material.icons.extended`.

---

## 1. Overview & Core Concept

- **What This Delivers**: A dramatically lighter, faster-installing, and lower-memory APK (~2–3 MB) that launches quicker and consumes less disk storage on user devices.
- **Scope & Safety**: Zero regressions. All existing features remain 100% functional:
  - Accessibility-driven bulk force-stop automation.
  - Multi-OEM cache cleaning automation (Pixel, Samsung OneUI, OnePlus, Xiaomi).
  - Real-time RAM statistics and Usage Access cache size queries.
  - Whitelist database persistence with Room.
  - Full Material 3 design system, colors, and layout aesthetics.

---

## 2. User Experience & Visual Design

- **Visual Fidelity**: Identical to current app design. The icons displayed for the primary power button, whitelist shields, accessibility prompt, cache bin buttons, and status indicators will use the exact official Google Material vector paths.
- **Icon Replacements**:
  1. `ic_power_settings.xml`: Replaces `Icons.Default.PowerSettingsNew` in the top app bar and "Stop Selected Apps" button.
  2. `ic_shield.xml`: Replaces `Icons.Default.Shield` in whitelist badges and empty states.
  3. `ic_accessibility_new.xml`: Replaces `Icons.Default.AccessibilityNew` in permission dialog.
  4. `ic_pause.xml`: Replaces `Icons.Default.Pause` in the automation HUD overlay.
  5. `ic_check_circle.xml`: Replaces `Icons.Default.CheckCircle` in service status chips.
  6. `ic_android.xml`: Replaces `Icons.Default.Android` for fallback app icon rendering.
  7. `ic_delete_outline.xml`: Replaces `Icons.Outlined.Delete` for the clear cache bin icon.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Local XML Vector Drawables vs. `material-icons-extended`**
  - *Chosen Approach*: Define lightweight XML vector drawables in `res/drawable/` and access them via `painterResource(R.drawable.ic_...)`.
  - *Why*: Eliminates the ~14 MB DEX overhead of 10,000 unused classes while maintaining infinite-resolution SVG scaling.
  - *Alternatives Considered*: Retaining the dependency and relying solely on R8 minify. *Trade-off*: Debug APKs and intermediate builds still suffer massive compile times and huge file sizes, whereas removing the library fixes both debug and release artifacts permanently.
- **Decision 2: Remove Unused Firebase Platform BOM**
  - *Chosen Approach*: Remove `implementation(platform(libs.firebase.bom))` from `app/build.gradle.kts`.
  - *Why*: Stop it does not use Firebase Auth or Firestore; it runs fully local using Room. Removing the unused BOM declaration prevents Gradle from pulling extraneous metadata and compiler checks.
- **Decision 3: Service & Resource File Audit**
  - *Chosen Approach*: Review `ForceStopAccessibilityService.kt`, `CacheCleanerAutomationManager.kt`, and `strings.xml`. Ensure zero orphaned imports, clean up commented-out template references, and verify efficient memory management.
  - *Why*: Ensures the codebase remains pristine, DRY, and fast.

---

## 4. Technical Architecture & Implementation Steps

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       APK Size Optimization Pipeline                        │
│                                                                             │
│  BEFORE OPTIMIZATION (~17 MB)                                               │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ classes.dex (44 MB) + classes9.dex (11 MB) = 9 DEX Files              │  │
│  │  - compose.material.icons.extended (10,000+ unused icon classes)      │  │
│  │  - firebase.bom metadata declarations                                 │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│                                   ▼                                         │
│                                                                             │
│  STEP 1: Bundle 7 Vector Drawables in res/drawable/ (< 15 KB total)         │
│  - ic_power_settings.xml                                                    │
│  - ic_shield.xml                                                            │
│  - ic_accessibility_new.xml                                                 │
│  - ic_pause.xml                                                             │
│  - ic_check_circle.xml                                                      │
│  - ic_android.xml                                                           │
│  - ic_delete_outline.xml                                                    │
│                                                                             │
│                                   ▼                                         │
│                                                                             │
│  STEP 2: Update Composables to use painterResource()                        │
│  - HomeScreen.kt, WhitelistRowItem.kt, AppRowItem.kt                        │
│  - AutomationOverlayHud.kt, AccessibilityDialog.kt, AddAppsDialog.kt        │
│                                                                             │
│                                   ▼                                         │
│                                                                             │
│  STEP 3: Remove Bloated Dependencies from app/build.gradle.kts              │
│  - Remove libs.androidx.compose.material.icons.extended                     │
│  - Remove libs.firebase.bom                                                 │
│                                                                             │
│                                   ▼                                         │
│                                                                             │
│  AFTER OPTIMIZATION (~2–3 MB APK)                                           │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 1-2 compact DEX files, fast launch, zero bloat, ~85% size reduction   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Verification & Validation Plan

1. **Compilation Check**: Run `compile_applet` to ensure all vector drawables compile cleanly and all Compose icons render with `painterResource`.
2. **Automated Unit Tests**: Execute `gradle :app:testDebugUnitTest` to guarantee all existing test cases pass without regressions.
3. **APK Size Verification**: Run `ls -lh .build-outputs/app-debug.apk` and inspect uncompressed DEX files to verify the expected ~85% reduction.
