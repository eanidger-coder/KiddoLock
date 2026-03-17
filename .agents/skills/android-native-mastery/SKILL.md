---
name: android-native-mastery
description: Expert patterns for Android native development including Services, Accessibility, Permissions, WorkManager, and Notifications.
---

# Android Native Mastery Skill

## Core Principles
1. **Service Lifecycle Awareness** — Always respect Android's service lifecycle. Use `startForeground()` immediately in `onCreate()` for foreground services on API 34+.
2. **Accessibility Services** — Filter events aggressively using `serviceInfo.eventTypes`. Only listen to `TYPE_WINDOW_STATE_CHANGED` for app-switch detection. Avoid `TYPE_WINDOW_CONTENT_CHANGED` unless explicitly needed.
3. **Permission Model** — Always check permissions at runtime before use. Use `Settings.canDrawOverlays()` before overlay operations. Never assume permissions persist across app updates.
4. **WorkManager over AlarmManager** — Prefer WorkManager for deferred work. Use `PeriodicWorkRequest` for recurring background tasks with constraints.
5. **Notification Channels** — Create channels in `Application.onCreate()`. Use separate channels for different priority levels. Always specify `IMPORTANCE_*` explicitly.

## Anti-Patterns to Avoid
- Never create `new AppManager()` inside hot loops (e.g., accessibility event handlers). Use cached instances with TTL.
- Never use `GlobalScope.launch` for coroutines tied to a lifecycle. Use `viewModelScope` or `lifecycleScope`.
- Never hardcode notification channel IDs — define them as constants.
- Never call `startActivity()` from a background service without `FLAG_ACTIVITY_NEW_TASK`.

## Best Practices
- Use `applicationContext` (not `Activity` context) for long-lived objects to prevent memory leaks.
- Implement `onTrimMemory()` in your Application class for proactive memory management.
- Use `ViewBinding` or `DataBinding` instead of `findViewById()`.
- Always provide a `contentDescription` for ImageViews used by accessibility readers.
- Use `ContextCompat.checkSelfPermission()` for permission checks.
