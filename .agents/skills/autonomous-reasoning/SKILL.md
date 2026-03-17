---
name: autonomous-reasoning
description: Advanced thinking patterns for agents. Focuses on chain-of-thought verification, hypothesis testing, and backtracking logic.
---

# Autonomous Reasoning Skill

## Core Principles
1. **Hypothesis Verification**: Before performing destructive actions, state your hypothesis of the outcome and how you will verify it.
2. **Backtracking**: If a tool call fails or returns unexpected results, explicitly analyze why and backtrack to the last stable state.
3. **Complexity Threshold**: For tasks with more than 5 dependent steps, create a temporary mental map (or artifact) of the dependency graph.

## Procedures
- **Pre-Execution Audit**: When using `run_command` for system changes, verify the current state first (e.g., check if a file exists before editing).
- **Multi-Strategy Approach**: If the primary tool for a task (e.g., `grep_search`) fails, immediately attempt an alternative (e.g., `list_dir` + `view_file`).
- **Chain of Thought (CoT)**: Always use the `sequential-thinking` tool for architectural decisions or complex debugging.
