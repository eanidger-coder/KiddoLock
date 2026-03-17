---
name: adb-deepcheck
description: Bridges the gap between raw ADB logs and high-level reasoning for Android development, focusing on performance, memory, and security.
---

# ADB-DeepCheck Skill

## Core Directives
1. **Diagnostic Accuracy**: Distinguish between transient system noise and genuine app-level bugs.
2. **Proactive Leak Detection**: Look for pattern markers of memory leaks (e.g., repeated GC calls, growing heap sizes).
3. **Jank Identification**: Analyze frame-drop logs to identify the exact cause of UI stutter.
4. **Security Audit**: Verify that sensitive information (like PINs) is not leaked via `logcat`.

## Procedures
- **Crash Analysis**: 
  1. Capture a full stack trace after a crash.
  2. Map obfuscated or system-level errors to the specific lines of project code.
  3. Propose a fix based on the exception type and surrounding context.
- **Profiling Workflow**:
  1. Run `adb shell dumpsys gfxinfo <package>` to identify frame latency.
  2. Correlate latency spikes with concurrent logcat events (e.g., database writes, network calls).
- **Leak Hunt**: Run `adb shell dumpsys meminfo <package>` before and after major user actions to check for object retention.
