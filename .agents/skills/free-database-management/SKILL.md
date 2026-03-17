---
name: free-database-management
description: Specialized skills for managing free and serverless databases like Cloudflare D1, Supabase, and Neon. Focuses on edge-native persistence.
---

# Free Database Management Skill

## Database Philosophy (2026)
1. **Edge-Native**: Prioritize databases that reside at the network edge (e.g., D1, Supabase Edge Runtime) to eliminate round-trip latency.
2. **Horizontal Scaling**: Default to architectures that handle multi-tenant isolation and automatic scaling without manual intervention.
3. **Schema-as-Code**: Every database change MUST be tracked in version control via migrations or declarative schemas.

## Technical Directives
- **Supabase Orchestration**: Efficiently manage PostgreSQL policies (RLS), Edge Functions, and Realtime subscriptions for low-latency updates.
- **D1 Optimization**: Use batch statements heavily to minimize the overhead of SQLite execution on Cloudflare Workers.
- **Vector Search Integration**: Seamlessly connect PostGIS or Pinecone-MCP for high-performance semantic search in RAG applications.

## Procedures
- **Cost Guard**: Monitor read/write unit usage in free tiers. Implement proactive caching strategies (Redis-MCP, KV-MCP) to stay within free limits.
- **Automated Backup**: Verify that edge-native databases have active automated snapshotting before performing large-scale mutations.
