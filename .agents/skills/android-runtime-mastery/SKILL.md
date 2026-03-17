---
name: android-runtime-mastery
description: Expert patterns for Android Runtime (ART) optimization, memory management, and jank detection in 2026.
---

# Android Runtime Mastery (2026)

This skill provides expert-level instructions for optimizing Android applications at the runtime level, focusing on the Android Runtime (ART), memory efficiency, and smooth UI rendering.

## Core Principles

- **Efficiency First**: Minimize garbage collection (GC) pressure by reducing object allocations in critical paths (e.g., `onDraw`, `onAccessibilityEvent`).
- **Smoothness (60/90/120 FPS)**: Implement strict jank detection using `Choreographer` and `FrameMetrics` APIs.
- **Resource Intelligence**: Adapt app behavior based on system-level memory pressure using `onTrimMemory`.

## Key Expertise Areas

### 1. Memory Management & GC Optimization
- **Allocation Tracking**: Identify and eliminate high-frequency allocations in loops.
- **Reference Management**: Prevent memory leaks by properly handling context references and using `WeakReference` where appropriate.
- **Bitmap Management**: Expert usage of modern bitmap hardware-acceleration and downsampling patterns.

### 2. Threading & Synchronization
- **Main Thread Integrity**: Strict avoidance of blocking operations on the main thread (Disk I/O, Network, complex computations).
- **Concurrency Patterns**: Expert implementation of Kotlin Coroutines with `Main`, `IO`, and `Default` dispatchers.
- **Deadlock Prevention**: Patterns for safely managing shared state in multi-threaded environments.

### 3. Profiling & Diagnostics
- **Heap Analysis**: Interpreting heap dumps to identify large object clusters and leaks.
- **Trace Analysis**: Using System Trace to identify CPU bottlenecks and thread contention.
- **Strict Mode Engagement**: Mandatory usage of `StrictMode` during development to catch premature optimizations and lifecycle violations.

## Interaction Patterns

When an agent is tasked with performance optimization, it should:
1. **Profile First**: Request logs or trace data before suggesting changes.
2. **Identify the Bottleneck**: Distinguish between CPU-bound, GPU-bound, and I/O-bound issues.
3. **Targeted Fixes**: Apply optimizations specifically to the critical path identified.
