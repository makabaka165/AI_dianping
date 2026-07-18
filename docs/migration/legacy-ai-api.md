# Legacy AI API Migration

The existing shop AI routes remain available as compatibility adapters:

- `/api/shop-summary/ai/chat`
- `/api/shop-summary/ai/analyze/{shopId}`
- `/api/shop-summary/ai/ask/{shopId}`
- `/api/shop-summary/ai/compare`
- `/api/shop-summary/ai/recommend`

They delegate to `AgentRunApplicationService` and the published `shop-consultant` Agent rather than maintaining a second model path. Responses retain the legacy envelope where practical and include `Deprecation: true`.

New integrations should use:

```http
POST /api/v1/agent-runs
GET /api/v1/agent-runs/{runId}
GET /api/v1/agent-runs/{runId}/events
```

Migration steps:

1. Add Sa-Token authentication and tenant/workspace headers.
2. Convert query parameters to the `AgentRunRequest` JSON input.
3. Persist the returned `runId`.
4. For streaming, consume named SSE events instead of parsing legacy text deltas only.
5. Render `ResponseBlock`, Citation and Artifact structures.
6. Remove legacy calls after monitoring equivalent success, latency and citation metrics.

Frontend code is intentionally unchanged in this backend refactor.
