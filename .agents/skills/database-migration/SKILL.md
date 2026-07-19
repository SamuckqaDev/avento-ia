---
name: database-migration
description: Design, implement, review, or validate Avento relational database changes. Use for PostgreSQL schema changes, Flyway migrations, constraints, indexes, backfills, entity mappings, repository queries, data retention, and safe chat or media deletion.
---

# Database Migration

1. Inspect existing migrations, entities, repositories, constraints, and production access patterns before writing SQL.
2. Add a new immutable Flyway migration; never edit an applied migration to change current behavior.
3. Prefer additive transitions for risky changes: add, backfill, validate, switch reads/writes, then remove in a later release.
4. Add foreign keys, uniqueness, nullability, and indexes that express real invariants and query paths.
5. Keep PostgreSQL as durable truth and Redis reconstructible. Do not migrate durable records into cache-only storage.
6. Align JPA mappings and DTOs with the schema without exposing entities at transport boundaries.
7. Test migration from the previous schema and on an empty database, then exercise affected repository queries.
8. For destructive deletion, verify the complete ownership graph and remove database rows and filesystem artifacts consistently.

Document operational impact, expected lock duration, data backfill, and the recovery strategy for non-trivial migrations.
