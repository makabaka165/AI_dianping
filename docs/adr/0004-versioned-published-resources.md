# ADR 0004: Published Resources Are Immutable

Status: Accepted

Prompt, Agent, workflow, Tool, document and knowledge versions are content-hashed and immutable after publication. Edits and rollbacks create new versions. Every Agent run records the exact version/index snapshot used.

This prevents configuration drift from making historical runs irreproducible.
