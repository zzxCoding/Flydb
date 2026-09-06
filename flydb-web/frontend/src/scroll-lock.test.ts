// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { en } from './i18n'
import type { Bootstrap, ConfigSnapshot, Run } from './types'

const fixture = vi.hoisted(() => ({ bootstrap: {} as Bootstrap, config: {} as ConfigSnapshot }))
vi.mock('./api', () => ({
  connect: vi.fn().mockResolvedValue(undefined),
  ApiError: class extends Error {},
  api: vi.fn(async (path: string) => {
    if (path === '/bootstrap') return fixture.bootstrap
    if (path.endsWith('/config')) return fixture.config
    if (path.endsWith('/actions')) return fixture.bootstrap.runs[1]
    throw new Error('Unexpected API request: ' + path)
  }),
}))
let app: VueApp
const button = (label: string, within: ParentNode = document) => {
  const found = Array.from(within.querySelectorAll('button')).find(el =>
    (el.getAttribute('aria-label') || el.textContent?.trim() || '').startsWith(label))
  if (!found) throw new Error('Missing button: ' + label)
  return found
}
async function locked() { await vi.waitFor(() => expect(document.documentElement.style.overflow).toBe('hidden')) }
async function unlocked() {
  await vi.waitFor(() => {
    expect(document.querySelector('.n-modal')).toBeNull()
    expect(document.documentElement.style.overflow).not.toBe('hidden')
    expect(document.documentElement.style.overflowY).toBe('scroll')
  })
}
beforeEach(async () => {
  localStorage.clear()
  document.documentElement.style.overflowY = 'scroll'
  vi.stubGlobal('EventSource', class { close() {} addEventListener() {} })
  const run: Run = { id: 'history', profileId: 'project', profileName: 'Project', command: 'inspect',
    source: 'WEB', status: 'SUCCEEDED', startedAt: '2026-09-06T00:00:00Z', endedAt: '2026-09-06T00:00:01Z',
    verification: 'PASSED', sequence: 1 }
  fixture.bootstrap = { profiles: [{ id: 'project', name: 'Project', environment: '', group: '',
    configPath: '/tmp/project/flydb.conf', workingDirectory: '/tmp/project' }],
    runs: [run, { ...run, id: 'plan', command: 'plan' }], version: 'test', initialDirectory: '/tmp',
    driversDirectory: '/tmp/drivers', stateDirectory: '/tmp/state', knownKeys: [] }
  fixture.config = { revision: 'one', values: { 'flydb.url': 'jdbc:mysql://localhost/app' },
    effective: {}, sources: {}, secretKeys: [] }
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(App).use(createI18n({ legacy: false, locale: 'en', messages: { en } }))
  app.mount(root)
  await vi.waitFor(() => expect(document.querySelector('.profile-heading h1')?.textContent).toBe('Project'))
})
afterEach(async () => {
  app?.unmount(); await nextTick()
  document.body.replaceChildren(); document.documentElement.removeAttribute('style')
  vi.unstubAllGlobals()
})
it('releases the real modal scroll lock after closing execution details repeatedly', async () => {
  button('Run history').click(); await nextTick()
  for (let index = 0; index < 3; index++) {
    document.querySelector<HTMLButtonElement>('.run-row')!.click(); await locked()
    button('Close', document.querySelector('article')!).click()
    await unlocked()
  }
})
it('keeps the history list locked until both nested dialogs close', async () => {
  button('All runs').click(); await locked()
  document.querySelector<HTMLButtonElement>('.run-history .run-row')!.click(); await nextTick()
  button('Close', document.querySelector('article')!).click()
  await vi.waitFor(() => expect(document.querySelector('article')).toBeNull())
  expect(document.documentElement.style.overflow).toBe('hidden')
  button('Close', document.querySelector('.run-history')!).click()
  await unlocked()
})
it('releases the lock after cancelling a migration preview', async () => {
  button('Preview migration').click(); await locked()
  button('Cancel', document.querySelector('.plan-panel')!).click()
  await unlocked()
})
it('releases the lock after cancelling an advanced operation without executing it', async () => {
  document.querySelector<HTMLElement>('.advanced-actions summary')!.click()
  button('Set baseline').click(); await locked()
  button('Cancel', document.querySelector('.n-modal')!).click()
  await unlocked()
})
