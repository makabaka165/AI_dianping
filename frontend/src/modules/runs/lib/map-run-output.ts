import type { ResponseBlock } from '@/modules/runs/contracts/run-contract'

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

export function extractFallbackReason(output: unknown): string | null {
  const record = asRecord(output)
  if (!record) return null
  const value = record.fallbackReason
  return typeof value === 'string' && value.trim() ? value : null
}

export function extractResponseBlocks(output: unknown): ResponseBlock[] {
  if (!output) return []
  if (typeof output === 'string') {
    return [{ type: 'TEXT', text: output }]
  }
  const record = asRecord(output)
  if (!record) {
    return [{ type: 'TEXT', text: JSON.stringify(output, null, 2) }]
  }

  const candidates = [record.blocks, record.responseBlocks, record.parts, record.content]
  for (const candidate of candidates) {
    if (Array.isArray(candidate) && candidate.length > 0) {
      return candidate.map(normalizeBlock)
    }
  }

  if (typeof record.markdown === 'string') return [{ type: 'MARKDOWN', markdown: record.markdown }]
  if (typeof record.text === 'string') return [{ type: 'TEXT', text: record.text }]
  if (typeof record.summary === 'string') return [{ type: 'TEXT', text: record.summary }]

  return [{ type: 'TEXT', text: JSON.stringify(record, null, 2) }]
}

function normalizeBlock(value: unknown): ResponseBlock {
  const record = asRecord(value)
  if (!record) {
    return { type: 'TEXT', text: String(value) }
  }
  const type = typeof record.type === 'string' ? record.type.toUpperCase() : 'UNKNOWN'
  switch (type) {
    case 'TEXT':
      return { type: 'TEXT', text: String(record.text ?? record.content ?? '') }
    case 'MARKDOWN':
      return { type: 'MARKDOWN', markdown: String(record.markdown ?? record.content ?? '') }
    case 'TABLE':
      return {
        type: 'TABLE',
        columns: Array.isArray(record.columns) ? record.columns.map(String) : [],
        rows: Array.isArray(record.rows)
          ? record.rows.map((row) => (Array.isArray(row) ? row.map((cell) => (cell == null ? null : String(cell))) : []))
          : [],
      }
    case 'CARD':
      return {
        type: 'CARD',
        title: record.title ? String(record.title) : undefined,
        body: record.body ? String(record.body) : undefined,
        fields: asStringRecord(record.fields),
      }
    case 'CITATION':
      return {
        type: 'CITATION',
        title: record.title ? String(record.title) : undefined,
        uri: record.uri ? String(record.uri) : undefined,
        snippet: record.snippet ? String(record.snippet) : undefined,
      }
    case 'FILE':
      return {
        type: 'FILE',
        name: record.name ? String(record.name) : undefined,
        uri: record.uri ? String(record.uri) : undefined,
        mediaType: record.mediaType ? String(record.mediaType) : undefined,
      }
    case 'IMAGE':
      return {
        type: 'IMAGE',
        alt: record.alt ? String(record.alt) : undefined,
        uri: record.uri ? String(record.uri) : undefined,
      }
    case 'FORM':
      return {
        type: 'FORM',
        formId: record.formId ? String(record.formId) : undefined,
        fields: Array.isArray(record.fields) ? (record.fields as Array<Record<string, unknown>>) : [],
      }
    case 'PROGRESS':
      return {
        type: 'PROGRESS',
        label: record.label ? String(record.label) : undefined,
        percent: typeof record.percent === 'number' ? record.percent : undefined,
      }
    case 'WARNING':
      return { type: 'WARNING', message: String(record.message ?? record.text ?? 'Warning') }
    default:
      return { type, ...record }
  }
}

function asStringRecord(value: unknown): Record<string, string> | undefined {
  const record = asRecord(value)
  if (!record) return undefined
  const result: Record<string, string> = {}
  for (const [key, item] of Object.entries(record)) {
    result[key] = String(item)
  }
  return result
}

export function isTerminalRunStatus(status: string | undefined | null): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED' || status === 'TIMED_OUT'
}

export function isTerminalRunEventType(type: string): boolean {
  return type === 'run.completed' || type === 'run.failed' || type === 'run.cancelled' || type === 'run.timed_out'
}
