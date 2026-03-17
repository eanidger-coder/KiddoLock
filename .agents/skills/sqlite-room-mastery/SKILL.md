---
name: sqlite-room-mastery
description: Expert persistence patterns for SQLite and Room, focusing on performance, safety, and migration in 2026.
---

# SQLite & Room Mastery (2026)

This skill provides the patterns required to build highly reliable and performant local persistence layers using SQLite and the Room persistence library.

## Core Principles

- **ACID Integrity**: Ensure data consistency even during crashes or unexpected process exits.
- **Performance at Scale**: Use proper indexing and query optimization to handle large datasets.
- **Safety First**: Leverage compile-time SQL verification and safe migration patterns.

## Implementation Standards

### 1. Schema Design
- **Normalized Data**: Use proper relationships (`One-to-One`, `One-to-Many`) while avoiding over-normalization that hurts performance.
- **Type Safety**: Expert usage of `TypeConverters` for complex objects and Enums.
- **Indexes**: Mandatory indexing for frequently queried columns and foreign keys.

### 2. DAO & Query Optimization
- **Coroutine Integration**: All database operations must be `suspend` functions and run on a non-UI dispatcher.
- **Reactive Streams**: Prefer `Flow` or `LiveData` for automated UI updates upon data changes.
- **Transaction Safety**: Use `@Transaction` for multi-step operations (e.g., "Delete old data -> Insert new data").

### 3. Migrations & Versioning
- **Safe Migrations**: Implement explicit `Migration` objects for every schema version bump.
- **Data Preservation**: Always verify that user data is preserved and not cleared during migration unless explicitly intended.
- **Automated Testing**: Generate and run schema export tests to verify migration integrity.

## Interaction Patterns

When asked to modify the database layer:
1. **Schema Check**: Review existing `@Entity` definitions before adding new ones.
2. **Migration Plan**: Always provide the `Migration` code alongside the schema change.
3. **Query Review**: Verify that new SQL queries are efficient and leverage existing indexes.
