// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import RunPanel from './components/RunPanel.vue'
import { en, zh } from './i18n'
import type { Run } from './types'

let app: App | undefined
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.unstubAllGlobals() })
it('shows actionable validation details without opening raw JSON', async () => {
  const detail = '[FLYDB-2003] V20260901.3__data.sql 高于本地最高版本（FUTURE）'
  const root = document.createElement('div'); document.body.append(root)
  const run: Run = { id: 'test', status: 'FAILED', command: 'plan', result: { error: { code: 'FLYDB-2003', detail } } }
  app = createApp(RunPanel, { run }).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } }))
  app.mount(root); await nextTick()
  expect(root.querySelector('.notice.error')?.textContent).toContain(detail)
})
it.each(['zh-CN', 'en'])('renders and exports an unreadable record without invented outcomes (%s)', async locale => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const run: Run = JSON.parse('{"id":"1788763586957-8af30691-965f-49aa-a34c-98fc1813b552","status":"UNKNOWN","recovery":"UNREADABLE_RECORD"}')
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(RunPanel, { run }).use(createI18n({ legacy: false, locale, messages: { en, 'zh-CN': zh } }))
  app.mount(root); await nextTick()
  document.querySelector<HTMLButtonElement>('.dialog-actions button')!.click()
  await vi.waitFor(() => expect(writeText).toHaveBeenCalledOnce())
  const report: string = writeText.mock.calls[0]![0]
  expect(report).not.toMatch(/undefined|NaN|Invalid Date/)
  expect(root.textContent).not.toMatch(/undefined|NaN|Invalid Date/)
  expect(report).toContain(locale === 'en' ? 'Execution record could not be read' : '执行记录无法读取')
  expect(report).toContain(locale === 'en' ? 'Post-run verification: Unknown' : '执行后核验: 未知')
  expect(root.querySelector('.elapsed strong')?.textContent).toBe('—')
})

it.each([
  { status: 'SUCCEEDED', startedAt: '2026-09-07T00:00:00Z', endedAt: '2026-09-07T00:01:05Z', elapsed: '1:05' },
  { status: 'UNKNOWN', startedAt: '2026-09-07T00:00:00Z', endedAt: undefined, elapsed: '—' },
  { status: 'SUCCEEDED', startedAt: 'invalid', endedAt: '2026-09-07T00:01:05Z', elapsed: '—' },
  { status: 'SUCCEEDED', startedAt: '2026-09-07T00:00:00Z', endedAt: 'invalid', elapsed: '—' },
])('only calculates elapsed time from trustworthy timestamps ($status, $startedAt, $endedAt)', async fixture => {
  const root = document.createElement('div'); document.body.append(root)
  const run: Run = { id: 'test', ...fixture, verification: 'NOT_RUN' }
  app = createApp(RunPanel, { run }).use(createI18n({ legacy: false, locale: 'en', messages: { en } }))
  app.mount(root); await nextTick()
  expect(root.querySelector('.elapsed strong')?.textContent).toBe(fixture.elapsed)
  expect(root.querySelector('.run-outcomes')?.textContent).toContain('Not verified')
})
