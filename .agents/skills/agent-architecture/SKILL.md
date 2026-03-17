---
name: agent-architecture
description: Patterns and strategies for designing and building effective multi-agent systems, focusing on orchestrator and peer-to-peer relationships.
---

# Agent Architecture Skill

## Architectural Patterns
1. **The Orchestrator**: A central node that handles planning, delegation, and result synthesis from specialized sub-agents.
2. **Functional Specialists**: Agents with deep expertise in specific domains (e.g., Security, UI, Data).
3. **P2P Collaboration**: Standards for direct communication and context sharing between agents without a central bottleneck.

## Directives
- **Atomic Delegation**: Ensure every sub-task handed to another agent is atomic, well-defined, and has clear success criteria.
- **Shared Memory**: Use centralized artifacts (e.g., `task.md`, `shared_state.json`) as the single source of truth for the entire swarm.
- **Error Propagation**: Implement robust patterns for handling and recovering from sub-agent failures.

## Procedures
- **Role Definition**: Before spawning a sub-agent, explicitly define its `system_prompt` and tool access scope.
- **Convergence Checks**: Periodically verify that all active agents are working towards a consistent global objective.
