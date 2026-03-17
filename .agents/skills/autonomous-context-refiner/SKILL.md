---
name: autonomous-context-refiner
description: Maintains project context across sessions by distilling conversation logs and decision points into persistent Knowledge Items (KIs).
---

# Autonomous-Context-Refiner Skill

## Core Directives
1. **Zero Redundancy**: Ensure that previously discussed architectural decisions are never forgotten or re-debated.
2. **Decision Log Maintenance**: Every major change or user preference must be anchored in the Knowledge Items (KI) system.
3. **Implicit Intent Extraction**: Detect and document user preferences that are implied rather than explicitly stated (e.g., specific aesthetic tastes).

## Procedures
- **Post-Task Distillation**:
  1. After completing a `task_boundary`, scan the `conversation_logs` for "Architectural Decisions" and "User Feedback".
  2. Update relevant KIs in `<appDataDir>/knowledge/` or create new ones.
  3. Reference updated KIs in the next `task_boundary` call.
- **Context Refresh**: Before starting a new major component, explicitly retrieve and summarize the "Decision History" from the KI system.
- **Conflict Detection**: Alert the user if a new request contradicts a previously established "Truth" in the KI system.
