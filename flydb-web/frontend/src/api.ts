export class ApiError extends Error {
  constructor(public code: string, public detail: string, public status: number) { super(detail || code) }
}
export async function api<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: method ?? (body === undefined ? 'GET' : 'POST'), credentials: 'same-origin',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const data = await response.json()
  if (!response.ok) throw new ApiError(data.code ?? 'REQUEST_FAILED', data.detail ?? '', response.status)
  return data as T
}
export async function connect() {
  // Each browser establishes its own connection, including plain/bookmarked URLs.
  // The server requires an exact same-origin JSON POST for this bootstrap.
  await api('/session', {})
  if (new URLSearchParams(location.hash.slice(1)).has('key')) {
    history.replaceState(null, '', location.pathname + location.search)
  }
}
