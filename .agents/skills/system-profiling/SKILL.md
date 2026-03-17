---
name: system-profiling
description: Resource and performance optimization skill. Focuses on memory, CPU, and network efficiency.
---

# System Profiling Skill

## Core Directives
1. **Memory Awareness**: In Android development, monitor for context leaks, especially in long-running services or observers.
2. **CPU Efficiency**: Avoid heavy computation in the Main Thread. Ensure all intensive tasks (app listing, storage scanning) run in `Dispatchers.IO` or `Dispatchers.Default`.
3. **Network Hygiene**: Implement exponential backoff for all API calls in `SettingsSyncManager`. Validate payload sizes before synchronization.

## Procedures
- **Performance Audit**: Use `Log.v` with timestamps to measure execution time of critical paths (e.g., `onAccessibilityEvent`).
- **Resource Cleanup**: Ensure all `BroadcastReceivers`, `Listeners`, and `Observers` are unregistered in `onDestroy` or when the service stops.
- **Battery Impact**: Minimize wakeup frequency for background sync. Use `WorkManager` for non-urgent tasks.
