import { afterEach, expect, it, vi } from 'vitest'
import { connect } from './api'

afterEach(() => vi.unstubAllGlobals())

it('connects a fresh browser from the plain URL without a startup fragment', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ready: true }) })
  const replaceState = vi.fn()
  vi.stubGlobal('fetch', fetch)
  vi.stubGlobal('location', { hash: '', pathname: '/', search: '' })
  vi.stubGlobal('history', { replaceState })
  await connect()
  expect(fetch).toHaveBeenCalledWith('/api/session', expect.objectContaining({
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }))
  expect(replaceState).not.toHaveBeenCalled()
})

it('can retry after an interrupted bootstrap and clears old fragments only on success', async () => {
  const fetch = vi.fn().mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({ ok: true, json: async () => ({ ready: true }) })
  const replaceState = vi.fn()
  vi.stubGlobal('fetch', fetch)
  vi.stubGlobal('location', { hash: '#key=old-startup-key', pathname: '/', search: '?view=local' })
  vi.stubGlobal('history', { replaceState })
  await expect(connect()).rejects.toThrow('offline')
  expect(replaceState).not.toHaveBeenCalled()
  await connect()
  expect(replaceState).toHaveBeenCalledWith(null, '', '/?view=local')
  expect(fetch.mock.calls[1][1].body).toBe('{}')
})
