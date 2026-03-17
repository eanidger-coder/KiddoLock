---
name: multi-agent-orchestration
description: Standards for sub-agent delegation, context sharing, and conflict resolution in multi-agent environments.
---

# Multi-Agent Orchestration Skill

## Orchestration Patterns
1. **Delegation**: Split large tasks into specialized sub-tasks assigned to dedicated agent roles (e.g., UI Sub-agent, Security Sub-agent).
2. **Context Synchronization**: Use shared artifacts (e.g., `task.md`, `implementation_plan.md`) as the single source of truth for all agents in the workspace.
3. **Conflict Resolution**: If two agents propose conflicting changes, the primary orchestrator must perform a "diff-collision" check before applying.

## Procedures
- **Task Handover**: When spawning a `browser_subagent`, provide a highly specific JSON schema of the expected return object.
- **Traceability**: Ensure all sub-agent decisions are logged with their reasoning and the ID of the triggering task.
- **Resource Locking**: Use the `task_boundary` tool to "lock" a component's state while an agent is performing atomic modifications.
