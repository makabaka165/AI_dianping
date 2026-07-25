export type CanonicalError = {
  code: string
  message: string
  requestId?: string
  fieldErrors?: Record<string, string>
  retryable: boolean
  status?: number
}

export function isCanonicalError(value: unknown): value is CanonicalError {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as CanonicalError).code === 'string' &&
    typeof (value as CanonicalError).message === 'string' &&
    typeof (value as CanonicalError).retryable === 'boolean'
  )
}

export function createCanonicalError(partial: Partial<CanonicalError> & Pick<CanonicalError, 'code' | 'message'>): CanonicalError {
  return {
    retryable: false,
    ...partial,
  }
}
