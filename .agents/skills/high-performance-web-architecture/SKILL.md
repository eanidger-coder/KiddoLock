---
name: high-performance-web-architecture
description: Standards for building lightning-fast, scalable web applications using 2026 best practices.
---

# High Performance Web Architecture Skill

## Core Architecture
1. **Island Architecture**: Use frameworks like Astro or modern Next.js to deliver zero-JS by default, hydrating only interactive components.
2. **Edge-First Thinking**: Prioritize logic execution at the edge (Cloudflare Workers, Vercel Edge) to minimize latency.
3. **State Management**: Use signals or atomic state libraries to minimize UI re-renders and memory pressure.

## Performance Directives
- **Zero CLS**: Always specify dimensions for media and use `font-display: swap` to prevent layout shifts.
- **Asset Optimization**: Use Next-Gen formats (AVIF, WebP) and dynamic component loading.
- **Lazy Hydration**: Only hydrate components when they enter the viewport to save client-side CPU cycles.

## Deployment Patterns
- **Atomic Commits**: Ensure every deployment is idempotent and can be rolled back instantly.
- **Cache Warming**: Implement automated preview warming for distributed edge caches.
