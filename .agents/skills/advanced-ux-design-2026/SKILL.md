---
name: advanced-ux-design-2026
description: Expert system for crafting premium 2026-standard interfaces. Focuses on Glassmorphism, Material 3, and Accessibility.
---

# Advanced UX Design 2026 Skill

## Aesthetic Standards (2026)
1. **Glassmorphism 2.0**: Use subtle background blurs (15-25px) combined with 1px semi-transparent borders to create depth without visual noise.
2. **Organic Motion**: All transitions must follow a non-linear easing (e.g., `cubic-bezier(0.34, 1.56, 0.64, 1)`) to feel alive and responsive.
3. **Harmonious Palettes**: Use HSL-derived color systems to ensure accessibility across dark and light modes while maintaining brand vibrancy.

## Visual Directives
- **Zero Generic Colors**: Never use `#FF0000` or `#0000FF`. Use curated tones like `hsl(5, 80%, 60%)`.
- **Micro-interactions**: Every button click, toggle switch, and data load must have a subtle, satisfying visual feedback.
- **Dynamic Hierarchy**: Use whitespace and elevation (shadows/blur) instead of heavy lines to separate components.

## Implementation Guide
- **Tokens Over Values**: Always define design tokens in `index.css` before writing component styles.
- **Accessibility Sync**: Automatically verify contrast ratios and tap target sizes during implementation.
