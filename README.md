# SafeLock

**SafeLock** is a unified Android parental-control app that merges two previously
separate projects — **KiddoLock** (system-wide app blocker and time scheduler)
and **SafeKids** (YouTube Kids violent-content filter) — into a single,
coordinated experience.

> **Version:** 2.0.0 (major merge)
> **Package:** `com.kiddolock.app` (preserved for in-place upgrade over KiddoLock installs)
> **Minimum Android:** 8.0 (API 26) · targets Android 15 (API 35)

---

## What you get

### 1. App blocking (inherited from KiddoLock)
- Block any installed app system-wide
- Per-app time limits and daily quotas
- Bedtime / quiet hours scheduler
- Overlay service that intercepts launch attempts
- BypassGuard — blocks Settings → Uninstall for SafeLock itself
- Device Admin anti-uninstall
- Cloud settings sync (Cloudflare Worker + D1, HMAC-signed)

### 2. YouTube content filter (inherited from SafeKids)
A 3-layer engine scans YouTube Kids and regular YouTube while the child uses
them:

| Layer | Class | What it does |
|-------|------------------------|--------------------------------------------|
| 1 | `ContentClassifier`   | Weighted keyword scoring (HE/EN/AR), 6 categories: physical violence, verbal violence, horror, Elsagate, weapons, dark themes |
| 2 | `EscalationTracker`   | Linear-regression gradient across recent videos — catches YouTube recommendation rabbit-holes even when no single video is violent enough to block |
| 3 | `ChannelAnalyzer`     | Parent-managed channel blacklist with repeat-offender tracking |

Sensitivity is adjustable (strict / balanced / relaxed). When content is
blocked, the child sees a friendly dark-neon block screen and can either return
to the home screen or request a parent unlock.

### 3. Unified UI
- Dark neon aesthetic (cyan `#00F2FF` / purple `#7000FF` on deep space `#050810`)
- Hebrew RTL throughout with English fallbacks
- Parent Admin area gated by a 6-digit PIN
- New **Content Filter** tab inside Admin for filter configuration
- Weekly Insights report

---

## Architecture highlights

### Single AccessibilityService
Android permits only one `AccessibilityService` per app, so the two engines
were merged into one class:

- `com.kiddolock.app.services.SafeLockAccessibilityService`

It handles both `TYPE_WINDOW_STATE_CHANGED` (app blocking) and
`TYPE_VIEW_SCROLLED | TYPE_VIEW_TEXT_CHANGED` (YouTube content scanning) from
the same event stream. The content filter only activates when a monitored
YouTube package is in the foreground.

### Room database (`SafeLockDatabase`)
Content-filter persistence lives in its own database (`safelock_content_db`) to
keep it independent of KiddoLock's existing `SharedPreferences` state:

- `BlacklistedChannel` — parent-added channel blocks
- `BlacklistedKeyword` — parent-added custom keywords
- `ViewingSession` — per-video violence scores (fuel for EscalationTracker)
- `BlockedEvent` — audit log of blocks shown to the child

### Settings
- `com.kiddolock.app.utils.Prefs` — KiddoLock settings (unchanged)
- `com.kiddolock.app.content.ContentPreferences` — content filter on/off,
  sensitivity, lifetime block count

### Tech stack
- Kotlin 2.1.0 + KSP 2.1.0-1.0.29
- Android Gradle Plugin 8.7.3, Java 17
- Room 2.6.1, WorkManager 2.9.1
- Material 3 dark theme, ViewBinding
- Coroutines 1.9.0, OkHttp 4.12.0, Gson 2.10.1

---

## Build

```bash
./gradlew :app:assembleDebug
```

Outputs to `app/build/outputs/apk/debug/`.

## Required runtime permissions
1. **Accessibility Service** — powers both app blocking and YouTube scanning
2. **Display over other apps** — block overlay
3. **Usage Access** — active-app detection (KiddoLock side)
4. **Device Admin** — uninstall protection
5. **Notifications** — warnings, block receipts

---

## Project history

- **KiddoLock** lived at `eanidger-coder/kiddolock`
- **SafeKids** lived at `eanidger-coder/safekids`
- The merge branch here, `claude/merge-parental-control-apps-J5v8S`, now hosts
  the unified SafeLock 2.0 codebase.

Next up: moving the merged codebase to its own repository once GitHub App
permissions allow repo creation.
