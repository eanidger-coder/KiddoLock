---
name: mermaid-diagrams
description: Standard for creating architectural visualizations using Mermaid.js syntax.
---

# Mermaid Diagram Skill

## Usage
- **Flowcharts**: Use for process logic (blocking path, PIN verification).
- **Sequence Diagrams**: Use for communication between components (Service -> Manager -> Activity).
- **Class Diagrams**: Use for documenting the data model.

## Syntax Guidelines
- Keep labels short.
- Use subgraphs for logical grouping (e.g., 'UI', 'Management', 'Persistence').
- Prefix node IDs with their role (e.g., `srvMain`, `mgrBlock`).
