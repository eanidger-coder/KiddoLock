---
name: qa-testing-mastery
description: Advanced quality assurance and testing strategies across all platforms (Android, Web, iOS). Focuses on automation, accessibility, and security compliance.
---

# QA & Testing Mastery Skill

## Testing Directives (2026)
1. **Automated-First**: Every feature must have an automated test (Unit or UI) before it is considered "Done".
2. **Cross-Platform Parity**: Use specialized MCP servers (Playwright for Web, Appium for Mobile) to verify consistent behavior across different environments.
3. **Accessibility Audit**: Mandatory `Aria-Accessible` and `ContentDescription` checks on all new UI components.
4. **Resilience Testing**: Implement "Monkey Testing" or random input stress tests on boundary-critical components.

## Technical Standards
- **Playwright-MCP**: Use for E2E web testing with recording and visual regression support.
- **Appium/Flutter-Driver**: Standard for mobile automation. Ensure all finders use stable keys (`ValueKey` or `ID`) rather than changing text.
- **Security Scans**: Integrate automated dependency audits and OWASP top-10 checks as part of the "QA" phase.

## Procedures
- **Bug Reproduction**: When a bug is reported, the first step is always creating a failing test case that isolates the issue.
- **Coverage Quality**: Don't just aim for high percentage; ensure branch coverage for all security and data-integrity logic.
- **Visual Regression**: Use screenshot comparison tools to detect subtle UI shifts during CSS or layout refactors.
