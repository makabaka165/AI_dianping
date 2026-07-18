# ADR 0003: One Tool Pipeline with Protocol Adapters

Status: Accepted

Local Skill, MCP, HTTP, external search, Dify and sandbox execution share `ToolExecutionPipeline`. The domain `ToolProtocolAdapter` seam hides protocol details while the pipeline owns schemas, authorization, tenant isolation, approval, budgets, rate limits, retry, circuit breaking, bulkheads, timeout, idempotency, audit and result limits.

The current Java 11 baseline has no suitable official MCP Java SDK with compatible requirements, so the production adapter implements the standard HTTP JSON-RPC/streamable response contract and is covered by a real local protocol integration test. Replacing it with a compatible official SDK affects only the MCP adapter.
