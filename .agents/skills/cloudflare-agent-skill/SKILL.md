---
name: cloudflare-agent-skill
description: Expert system for building on the Cloudflare developer platform (2026). Focuses on Workers, D1 (SQL), R2 (Storage), and Pages.
---

# Cloudflare Agent Skill

## Edge Execution Standard (2026)
1. **Workers First**: All API logic should default to Cloudflare Workers to leverage low-latency global execution.
2. **D1 Persistence**: Use D1 for relational state. Always implement migration scripts (`npx wrangler d1 migrations create`) for every schema change.
3. **R2 Integrity**: Use R2 for binary storage. Always verify object MD5 hashes after upload to ensure data integrity at the edge.

## Architecture Directives
- **Zero Latency Bindings**: Prefer service bindings over internal HTTP calls for Worker-to-Worker communication.
- **Smart Placement**: Automatically optimize Worker placement for data-heavy tasks to minimize transit costs and latency.
- **KV Caching**: Use Cloudflare KV for global configuration and hot paths to avoid D1 read pressure.

## Procedures
- **Security Tunnels**: Use Cloudflare Tunnel for secure, zero-trust access to internal resources without exposing public ports.
- **Deployment Safety**: Use `Wrangler` environments to isolate `staging` from `production`. Always verify secret propagation after a deploy.
