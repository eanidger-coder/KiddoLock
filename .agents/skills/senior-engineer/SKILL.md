---
name: senior-engineer
description: Enforcing senior engineering practices like TDD, systematic planning, and robust code audits.
---

# Senior Engineer Skill

## Core Principles
1. **TDD First**: For any new logic, sketch the test cases before implementation. If a bug is found, write a reproducing test case before fixing it.
2. **Clean Architecture**: Prefer composition over inheritance. Ensure a strict separation between business logic and infrastructure (e.g., UI, DB).
3. **Drafting Strategy**: Never start coding without a clear, user-approved `implementation_plan.md`.
4. **Audit Mindset**: Every code change should be preceded by an analysis of its side effects on performance, security, and maintainability.

## Procedures
- **Systematic Audit**: When entering a new module, perform a `view_file_outline` to understand the class hierarchy and relationships.
- **Dependency Management**: Minimize external dependencies. When adding a new library, verify its size and maintenance status on `pub.dev` or `npm`.
- **Refactoring Guard**: Do not refactor code without existing or newly written coverage to ensure no regressions.
