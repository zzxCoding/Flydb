// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import Handoff from './components/AgentHandoff.vue'
import { agentContext } from './agent-context'
import { en, zh } from './i18n'
import type { ConfigSnapshot, Profile, Run } from './types'
const profile: Profile = { id: 'p', name: 'Order service', group: 'Production', environment: 'prod', configPath: '/tmp/flydb.conf', workingDirectory: '/tmp' }
const config: ConfigSnapshot = { revision: 'new', values: { 'flydb.password': 'do-not-copy', 'flydb.custom.secret': 'secret-value' }, effective: { 'flydb.url': 'jdbc:mysql://localhost/app', 'flydb.password': 'do-not-copy', 'flydb.user': 'test' }, sources: {}, secretKeys: ['flydb.password'] }
let app: App
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.unstubAllGlobals() })
it('bounds large summaries, excludes credentials and SQL, and marks stale inspections and unsaved drafts', () => {
  const run: Run = { id: 'inspect', profileId: 'p', command: 'inspect', status: 'SUCCEEDED', configRevision: 'old', result: { migrations: Array.from({ length: 5000 }, (_, i) => ({ script: `V${i}.sql`, version: String(i), state: 'PENDING', type: 'SQL', description: '', statements: [{ lineNumber: 1, sql: 'secret SQL body' }] })) } }
  const text = agentContext({ profile, config, runs: Array.from({ length: 12 }, () => run), version: 'test', stateDirectory: '/tmp/state', locale: 'zh-CN', dirty: true, offline: false })
  expect(text).not.toMatch(/do-not-copy|secret-value|secret SQL body/)
  expect(text).toContain('"omittedMigrations": 4950')
  expect(text).toContain('"unsavedDraftNotIncluded": true')
  expect(text).toContain('"stale": true')
  expect(text).toContain('复制上下文不代表授权')
  expect(text.length).toBeLessThan(20000)
})
it.each(['zh-CN', 'en'])('copies localized context from the visible button (%s)', async locale => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(Handoff, { profile, config, runs: [], version: 'test', stateDirectory: '/tmp/state', dirty: false, offline: false }).use(createI18n({ legacy: false, locale, messages: { en, 'zh-CN': zh } })); app.mount(root)
  root.querySelector<HTMLButtonElement>('button')!.click()
  await vi.waitFor(() => expect(writeText).toHaveBeenCalledOnce())
  expect(writeText.mock.calls[0]![0]).toContain('/tmp/flydb.conf')
  expect(writeText.mock.calls[0]![0]).toContain('"inspection": null')
  await nextTick()
  expect(root.querySelector('[role=status]')?.textContent).toContain(locale === 'en' ? 'Copied' : '已复制')
})
it('provides a focusable manual-copy fallback without falsely reporting clipboard success', async () => {
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(Handoff, { profile, config, runs: [], version: 'test', stateDirectory: '/tmp/state', dirty: false, offline: true }).use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(root)
  root.querySelector<HTMLButtonElement>('button')!.click()
  await vi.waitFor(() => expect(document.querySelector('textarea')).not.toBeNull())
  const field = document.querySelector('textarea')!
  field.focus(); expect(document.activeElement).toBe(field)
  expect(field.value).toContain('"serviceUnavailable": true')
  expect(root.querySelector('[role=status]')).toBeNull()
})
