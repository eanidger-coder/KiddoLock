---
name: memory-systems
description: Patterns for implementing agent memory, focusing on append-only logs for history and knowledge graphs for structured relationships.
---

# Memory Systems Skill (2026 Standard)

Memory systems enable agents to transcend individual conversation windows and build a persistent "world model" of the project.

## Memory Architecture Layers

### 1. Working Memory (Short-Term)
The current context window. Volatile and discarded after the session. Use for active task logic.

### 2. Append-Only Memory (Log-Based)
Use JSONL for conversation history, task logs, and user feedback.
-   **Structure**: Each line is an independent event.
-   **Benefit**: Agent-friendly (one line per entry), resilient to corruption, easy to stream.
-   **Pattern**: Store in `<appDataDir>/memory/history.jsonl`.

### 3. Structural Memory (Knowledge Graph)
Use for complex relationships (Entity-Relation-Entity).
-   **Example**: `AdminAdminManager` -> *manages* -> `AdminPin`
-   **Pattern**: Store as a Mermaid diagram or a simple triple-set in `memory/graph.md`.
-   **Usage**: Discover related files by traversing connections instead of just keyword search.

## Decision Tree: Where to Store?
-   **Is it a one-off execution step?** -> Markdown task list (`task.md`).
-   **Is it a key architectural decision?** -> Implementation plan (`implementation_plan.md`).
-   **Is it a factoid or project relationship?** -> Knowledge Graph (`knowledge/graph.md`).
-   **Is it raw history or logs?** -> Append-only log (`logs/events.jsonl`).

## The "Memory Pruning" Protocol
When memory becomes too large:
1.  **Condense**: Turn 10 pages of logs into 1 summary page.
2.  **Move to Arch**: Shift old task logs to `<appDataDir>/archive/`.
3.  **Refine Graph**: Delete obsolete or false relationships in the knowledge graph.

## References
-   [Memory MCP Server](https://github.com/mcp-servers/memory)
-   [Advanced Agent Memory Design (2026)](https://practical.yuv.ai/memory)
