---
name: context-engineering
description: Strategies for managing the agent's attention budget, optimizing context window usage, and avoiding cognitive overload during complex tasks.
---

# Context Engineering Skill

## Core Mechanisms
1. **Strategic Pruning**: Identify and summarize historical threads that are no longer relevant to the current objective.
2. **Context Anchoring**: Use the `task_boundary` tool to create clear thematic anchors, keeping the "Attention Budget" focused on the immediate problem.
3. **Knowledge Retrieval**: Favor reading curated Knowledge Items (KIs) or summarized documentation over raw directory listings.
4. **Token Economy**: Be concise in tool outputs and thought blocks to preserve context for reasoning.

## Procedures
- **Context Refresh**: When switching between major tasks, explicitly state what context is being carried forward and what is being archived.
- **Data Summarization**: When dealing with 100+ files, create a temporary map or summary index to avoid re-reading file lists.
- **Precision Reading**: Use `view_file` with specific line ranges instead of reading the whole file whenever possible.
