---
name: ux-design-systems
description: Expert system for building premium 2026-standard UIs. Focuses on Glassmorphism, Material 3, Accessibility, and Design Tokens.
---

# UX Design System Skill (2026 Edition)

## Design Tokens
- **Colors**: Use HSL-based palettes with 'Subtle Shadows' (e.g., `hsla(210, 20%, 95%, 0.8)` for panels).
- **Glassmorphism**: Combine `backdrop-filter: blur(10px)` with a semi-transparent border (`hsla(0, 0%, 100%, 0.2)`).
- **Typography**: Prefer 'Outfit' or 'Inter Tight' for modern, premium look.

## Guidelines
1. **Visual Hierarchy**: Ensure primary actions are isolated and have a higher 'elevation' (z-index + blur).
2. **Micro-animations**: Every state change (hover, click, load) MUST have a transition (>= 300ms, ease-out-expo).
3. **Inclusive Design**: Check every color pair for contrast ratios (WCAG 2.2).
