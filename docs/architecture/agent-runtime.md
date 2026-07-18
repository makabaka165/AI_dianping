# Agent Runtime

`DefaultAgentRuntime` executes an explicitly published Agent version and persists every transition.

```mermaid
sequenceDiagram
  participant C as Client
  participant A as AgentRunApplicationService
  participant R as DefaultAgentRuntime
  participant W as WorkflowRuntime
  participant DB as MySQL
  participant E as SSE/Event hub
  C->>A: POST /api/v1/agent-runs
  A->>A: authenticate, authorize, validate schema
  A->>DB: create ai_run + version snapshot
  A->>R: enqueue run
  R->>DB: claim QUEUED -> RUNNING
  R->>W: execute published workflow
  loop each node
    W->>DB: persist ai_node_run
    W-->>E: node/tool/retrieval events
  end
  R->>R: validate and assemble output
  R->>DB: messages, memory candidate, artifacts, terminal state
  R-->>E: run.completed or run.failed
```

`ExecutionContext` is immutable and carries tenant, workspace, user, session, run, Agent version, authorization, deadline, budget and trace data. Controllers never call models or construct prompts.

Run status supports created, queued, running, waiting for user/approval, completed, failed, cancelled and timed out. Retry creates a related run; cancellation and resume are conditional database updates. Hidden model reasoning is neither persisted nor exposed: only route/tool/node decisions, evidence IDs and short summaries are stored.
