import { describe, expect, it } from 'vitest'
import {
  extractFallbackReason,
  extractResponseBlocks,
  isTerminalRunStatus,
} from '@/modules/runs/lib/map-run-output'

describe('map-run-output', () => {
  it('extracts known blocks and unknown fallbackReason safely', () => {
    const blocks = extractResponseBlocks({
      fallbackReason: 'PROVIDER_TIMEOUT_CUSTOM',
      blocks: [
        { type: 'TEXT', text: 'hello' },
        { type: 'WEIRD_FUTURE_BLOCK', foo: 'bar' },
      ],
    })
    expect(extractFallbackReason({ fallbackReason: 'PROVIDER_TIMEOUT_CUSTOM' })).toBe(
      'PROVIDER_TIMEOUT_CUSTOM',
    )
    expect(blocks[0]).toMatchObject({ type: 'TEXT', text: 'hello' })
    expect(blocks[1]?.type).toBe('WEIRD_FUTURE_BLOCK')
  })

  it('detects terminal statuses without inventing failure from disconnects', () => {
    expect(isTerminalRunStatus('COMPLETED')).toBe(true)
    expect(isTerminalRunStatus('RUNNING')).toBe(false)
  })
})
