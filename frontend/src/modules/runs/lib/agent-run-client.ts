import type { StreamTransport } from '@/shared/stream/stream-transport'
import type { RunAdapter } from '@/modules/runs/adapters/http-run-adapter'
import type {
  AgentRunDetail,
  AgentRunEvent,
  CreateRunInput,
  RunStatus,
} from '@/modules/runs/contracts/run-contract'
import { isTerminalRunEventType } from '@/modules/runs/lib/map-run-output'

const EVENT_STATUS: Record<string, RunStatus> = {
  'run.created': 'CREATED',
  'run.queued': 'QUEUED',
  'run.started': 'RUNNING',
  'run.resumed': 'RUNNING',
  'feedback.required': 'WAITING_FOR_USER',
  'approval.required': 'WAITING_FOR_APPROVAL',
  'run.completed': 'COMPLETED',
  'run.failed': 'FAILED',
  'run.cancelled': 'CANCELLED',
  'run.timed_out': 'TIMED_OUT',
}

const DETAIL_REFRESH_EVENTS = new Set(['feedback.required', 'approval.required'])


export type AgentRunObservation = {
  status: RunStatus
  events: AgentRunEvent[]
  detail: AgentRunDetail | null
  observing: boolean
  error?: string | null
}

export type AgentRunClient = {
  createAndObserve(
    input: CreateRunInput,
    handlers: {
      onUpdate: (observation: AgentRunObservation) => void
      signal?: AbortSignal
    },
  ): Promise<AgentRunDetail>
  observe(
    runId: string,
    handlers: {
      onUpdate: (observation: AgentRunObservation) => void
      signal?: AbortSignal
      lastEventId?: string
    },
  ): Promise<AgentRunDetail>
}

export function createAgentRunClient(adapter: RunAdapter, stream: StreamTransport): AgentRunClient {
  return {
    async createAndObserve(input, handlers) {
      const created = await adapter.create(input)
      handlers.onUpdate({
        status: created.status,
        events: [],
        detail: null,
        observing: true,
      })
      return this.observe(created.runId, handlers)
    },

    async observe(runId, handlers) {
      const events: AgentRunEvent[] = []
      let detail: AgentRunDetail | null = null
      let status: RunStatus = 'QUEUED'

      const publish = (observing: boolean, error?: string | null) => {
        handlers.onUpdate({ status, events: [...events], detail, observing, error })
      }

      try {
        await stream.open(`/api/v1/agent-runs/${encodeURIComponent(runId)}/events`, {
          signal: handlers.signal,
          lastEventId: handlers.lastEventId,
          isTerminalEvent: (event) => isTerminalRunEventType(event.event),
          onEvent: async (event) => {
            let payload: unknown = event.data
            try {
              payload = event.data ? JSON.parse(event.data) : null
            } catch {
              payload = event.data
            }
            const sequence =
              typeof payload === 'object' &&
              payload &&
              'sequence' in payload &&
              typeof (payload as { sequence?: unknown }).sequence === 'number'
                ? (payload as { sequence: number }).sequence
                : Number(event.id || events.length + 1)
            const item: AgentRunEvent = {
              sequence,
              runId,
              type: event.event,
              payload,
              createdAt:
                typeof payload === 'object' &&
                payload &&
                'createdAt' in payload &&
                typeof (payload as { createdAt?: unknown }).createdAt === 'string'
                  ? (payload as { createdAt: string }).createdAt
                  : new Date().toISOString(),
            }
            events.push(item)
            const eventStatus = EVENT_STATUS[event.event]
            if (eventStatus) status = eventStatus
            if (
              typeof payload === 'object' &&
              payload &&
              'status' in payload &&
              typeof (payload as { status?: unknown }).status === 'string'
            ) {
              status = (payload as { status: string }).status
            }
            if (DETAIL_REFRESH_EVENTS.has(event.event)) {
              try {
                const refreshed = await adapter.get(runId)
                detail = refreshed
                if (!eventStatus || refreshed.status !== 'RUNNING') {
                  status = refreshed.status
                }
              } catch {
                // The state event remains authoritative if the follow-up detail read races or fails.
              }
            }
            publish(true)
          },
        })
      } catch (error) {
        // Observation interruption is not equivalent to run failure.
        publish(
          false,
          error instanceof Error ? error.message : 'SSE observation interrupted',
        )
      }

      detail = await adapter.get(runId)
      status = detail.status
      publish(false)
      // Final status always comes from run detail, never from SSE disconnect alone.
      return detail
    },
  }
}
