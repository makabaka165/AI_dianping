# ADR 0002: MySQL Is the Runtime Source of Truth

Status: Accepted

Agent definitions, immutable versions, run/node state, messages, memory facts, feedback, evaluation and ingestion jobs are durable in MySQL. Redis is an acceleration/distribution layer, Redis Stack is a rebuildable search index and MinIO stores binary objects.

This supports restart recovery, auditability and multi-instance correctness. In-memory repositories are test-only.
