export type RunStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING_FOR_USER'
  | 'WAITING_FOR_APPROVAL'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | string

export type AgentRunSummary = {
  runId: string
  agentId: string
  agentVersion: number
  status: RunStatus
  sessionId: string
  errorCode?: string | null
  errorMessage?: string | null
  queuedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
}

export type AgentRunDetail = AgentRunSummary & {
  conversationId?: string
  traceId?: string
  versionSnapshot?: unknown
  output?: unknown
  deadlineAt?: string | null
}

export type AgentRunCreated = {
  runId: string
  status: RunStatus
  agentDefinitionId: string
  agentCode: string
  agentVersion: number
}

export type AgentRunEvent = {
  sequence: number
  runId: string
  type: string
  payload: unknown
  createdAt: string
}

export type CreateRunInput = {
  agentId: string
  agentVersion: number
  sessionId: string
  text: string
  responseMode?: 'STREAM' | 'BLOCKING'
}

export type ResponseBlock =
  | { type: 'TEXT'; text: string }
  | { type: 'MARKDOWN'; markdown: string }
  | { type: 'TABLE'; columns: string[]; rows: Array<Array<string | number | null>> }
  | { type: 'CARD'; title?: string; body?: string; fields?: Record<string, string> }
  | { type: 'CITATION'; title?: string; uri?: string; snippet?: string }
  | { type: 'FILE'; name?: string; uri?: string; mediaType?: string }
  | { type: 'IMAGE'; alt?: string; uri?: string }
  | { type: 'FORM'; formId?: string; fields?: Array<Record<string, unknown>> }
  | { type: 'PROGRESS'; label?: string; percent?: number }
  | { type: 'WARNING'; message: string }
  | { type: string; [key: string]: unknown }
