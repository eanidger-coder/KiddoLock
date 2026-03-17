---
name: ai-workflow-architecture
description: Standards for designing human-AI collaboration systems and robust autonomous agent workflows in 2026.
---

# AI Workflow Architecture (2026)

This skill governs the design and execution of complex AI workflows, ensuring high reliability, transparency, and effective collaboration between humans and AI agents.

## Core Principles

- **Transparency**: Always document the "Why" and "What" before executing complex tasks.
- **Verification Loop**: Every significant action should be followed by a verification step.
- **Task Granularity**: Break large objectives into small, manageable TaskBoundaries to maintain context and reliability.

## Workflow Patterns

### 1. Task Management
- **Hierarchical Planning**: Start with a high-level `implementation_plan.md` and break it into granular entries in `task.md`.
- **Mode Switching**: Properly transition between `PLANNING`, `EXECUTION`, and `VERIFICATION` modes.
- **Checkpointing**: Use `walkthrough.md` to document progress and allow for easy resumption or review.

### 2. Agent Orchestration
- **Skill Delegation**: Assign specialized tasks to the most relevant "skills" or sub-agents.
- **Context Management**: Proactively manage tool outputs to avoid token bloat and context loss.
- **Error Handling**: Implement "Retry with Reflection" patterns when a tool call fails.

### 3. Human-in-the-Loop (HITL)
- **Proactive Notification**: Use `notify_user` for critical decisions or when blocked.
- **Concise Reporting**: Ensure all user-facing documentation is summarized and actionable.
- **Feedback Integration**: Update plans and tasks immediately based on user feedback.

## Execution Requirements

When managing a project:
- **Maintain task.md**: Keep the living documentation synchronized with actual progress.
- **Respect Boundaries**: Never skip the `PLANNING` phase for complex changes.
- **Review Artifacts**: Always provide clickable links to modified files in summaries.
