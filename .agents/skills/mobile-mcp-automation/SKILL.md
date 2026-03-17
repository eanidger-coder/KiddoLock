---
name: mobile-mcp-automation
description: Patterns for autonomous mobile device interaction and UI testing using Mobile MCP servers in 2026.
---

# Mobile MCP Automation (2026)

This skill enables AI agents to autonomously interact with Android devices (physical or emulated) via Model Context Protocol (MCP) servers for testing, verification, and automation.

## Core Principles

- **Contextual Interaction**: Understand the UI hierarchy via `get_ui_dump` before performing actions.
- **Reliable Execution**: Wait for elements to be visible and interactable before tapping or typing.
- **Verification Driven**: Always verify the outcome of an action (e.g., "After tapping button X, verify screen Y is visible").

## Automation Patterns

### 1. UI Navigation & Interaction
- **Dynamic Finders**: Use text, ID, or accessibility labels to locate elements instead of fixed coordinates.
- **Gesture Mastery**: Implement complex gestures (swiping, long-tapping, dragging) via MCP system actions.
- **State Verification**: Periodically check device state (battery, connectivity, foreground app) to ensure test integrity.

### 2. Automated Testing
- **End-to-End (E2E) Verification**: Walk through complete user flows (e.g., "Install app -> Grant permissions -> Verify blocking").
- **Edge Case Discovery**: Attempt to trigger boundary conditions (e.g., blocking system-critical apps, low battery scenarios).
- **Log Correlation**: Correlate UI actions with `logcat` output to identify silent failures.

### 3. Device Management
- **Installation & Setup**: Automate APK installation and initial configuration.
- **Permission Orchestration**: Handle runtime permission dialogs autonomously.
- **Lifecycle Testing**: Test app behavior during rotations, backgrounding, and process deaths (via `am force-stop`).

## Tool Usage Requirements

When using Mobile MCP tools:
- **Snapshot Often**: Call `get_ui_dump` after every significant interaction.
- **Error Recovery**: If a tap fails, search for the element again before retrying.
- **Clear Documentation**: Record each step taken so the user can follow the autonomous flow.
