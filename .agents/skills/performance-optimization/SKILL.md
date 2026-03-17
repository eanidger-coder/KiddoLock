---
name: performance-optimization
description: App startup optimization, memory leak detection, battery optimization, network efficiency, and UI rendering performance.
---

# Performance Optimization Skill

## App Startup
1. **Lazy initialization** — Defer non-critical initialization to after `onResume()`.
2. **Avoid disk I/O on main thread** — Use `SharedPreferences.apply()` (async) instead of `commit()` (sync).
3. **Background thread for heavy work** — Move JSON parsing, database queries, and network calls off the main thread.
4. **Measure startup** — Use `adb shell am start -W` to measure cold/warm/hot start times.

## Memory Management
1. **Avoid memory leaks** — Never hold Activity references in static fields or singletons. Use `WeakReference` when necessary.
2. **Use `applicationContext`** — For objects that outlive an Activity lifecycle.
3. **Bitmap recycling** — Use `inSampleSize` for loading large images. Consider `Glide` or `Coil` for automatic memory management.
4. **Profile with Android Studio** — Use the Memory Profiler to detect allocations and leaks.

## Battery Optimization
1. **Batch network requests** — Combine multiple API calls into a single request where possible.
2. **Use WorkManager** — For deferred work with battery-aware scheduling.
3. **Avoid wake locks** — Use `JobScheduler` or `WorkManager` instead.
4. **Minimize GPS usage** — Use fused location provider with appropriate accuracy levels.

## Network Efficiency
1. **Cache responses** — Use OkHttp's built-in cache with `Cache-Control` headers.
2. **Compress payloads** — Enable gzip compression for API responses.
3. **Retry with backoff** — Implement exponential backoff for failed requests.
4. **Prefetch data** — Load data the user is likely to need before they navigate.

## UI Rendering
1. **Avoid overdraw** — Use Layout Inspector to identify overlapping draws.
2. **Flatten view hierarchies** — Use `ConstraintLayout` to reduce nesting.
3. **RecyclerView optimization** — Use `setHasFixedSize(true)`, implement `DiffUtil`, and use `ViewHolder` pattern.
4. **Animate efficiently** — Use `ObjectAnimator` or `ValueAnimator` instead of manual view invalidation.

## Profiling Tools
| Tool | Use |
|------|-----|
| Android Studio Profiler | CPU, Memory, Network, Energy |
| Systrace | Frame rendering analysis |
| LeakCanary | Memory leak detection |
| Flipper | Network inspection, layout debugging |
