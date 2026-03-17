---
name: vision-ux-auditor
description: Enables autonomous visual auditing of Android applications using AI vision capabilities to scan for UI, UX, and accessibility issues.
---

# Vision-UX-Auditor Skill

## Core Directives
1. **Visual Consistency**: Check for alignment, spacing, and font consistency across screens.
2. **UX Friction Identification**: Identify flows where the user might get stuck or confused (e.g., small touch targets, hidden actions).
3. **Accessibility (WCAG 2026)**: Scan for color contrast issues, missing content descriptions, and screen reader compatibility.
4. **2026 Aesthetics**: Verify "Premium" feel (glassmorphism, Material 3, micro-animations).

## Procedures
- **UI Snapshot Audit**: 
  1. Capture a screenshot of the current screen using `adb-deepcheck` or `agent-browser`.
  2. Use `gemini-3.0-pro` to analyze the image.
  3. Generate a "Visual Debt" report with specific fix recommendations.
- **Dynamic Flow Audit**: Record a video walkthrough of a user flow and analyze frame-by-frame for transition "jank" or layout shifts.
- **Contrast Check**: Specifically query the AI to check the contrast ratio of critical interactive elements (e.g., PIN buttons, Close buttons).
