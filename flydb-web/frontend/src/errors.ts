import { ApiError } from './api'

/** Translate the actionable explanation, while preserving the original diagnostic. */
export function explainError(error: unknown, t: (key: string) => string, te: (key: string) => boolean): string {
  if (error instanceof ApiError) {
    const key = `errors.${error.code}`
    return `${te(key) ? t(key) : t('operationFailed')}\n${error.code}: ${error.message}`
  }
  return `${t('operationFailed')}\n${String(error)}`
}
