// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { en } from './i18n'
import { api } from './api'
const signal = vi.hoisted(() => ({ refresh: () => {} }))
vi.mock('./api', () => {
  const run = { id: 'large', profileId: 'project', command: 'plan', sequence: 2, status: 'SUCCEEDED', detailsOmitted: true,
    result: { planId: 'original-confirmation', migrations: [{ script: 'V1__large.sql', statementCount: 1 }] } }
  return { connect: vi.fn().mockResolvedValue(undefined), ApiError: class extends Error {}, api: vi.fn(async (path: string) => {
    if (path === '/bootstrap?compact=true') return { profiles: [{ id: 'project', name: 'Project', configPath: '/tmp/flydb.conf' }], runs: [run], knownKeys: [] }
    if (path.endsWith('/config')) return { values: { 'flydb.url': 'jdbc:mysql://localhost/app' }, effective: {}, sources: {}, secretKeys: [], revision: 'one' }
    if (path.endsWith('/actions')) return run
    if (path === '/runs/large') return { ...run, detailsOmitted: undefined, result: { ...run.result, migrations: [{ script: 'V1__large.sql', statements: [{ lineNumber: 1, sql: 'SELECT LAST_SQL' }] }] } }
    throw new Error(path)
  }) }
})
vi.mock('./components/SqlPreview.vue', async () => { const { h } = await import('vue'); return { default: {
  props: ['text'], setup: (props: { text: string }) => () => h('div', props.text),
} } })
let app: VueApp
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.unstubAllGlobals(); vi.clearAllMocks() })
it('loads complete SQL once on opening and preserves it across repeated summary polling', async () => {
  localStorage.clear()
  vi.stubGlobal('EventSource', class { set onmessage(handler: () => void) { signal.refresh = handler } close() {} addEventListener() {} })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(App).use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(root)
  await vi.waitFor(() => expect(root.querySelector('.profile-heading h1')?.textContent).toBe('Project'))
  expect(vi.mocked(api).mock.calls.filter(([path]) => path === '/runs/large')).toHaveLength(0)
  Array.from(root.querySelectorAll('button')).find(el => el.textContent?.includes('Preview migration'))!.click()
  await nextTick()
  expect(root.querySelector('.notice.error')?.textContent ?? '').toBe('')
  await vi.waitFor(() => expect(vi.mocked(api).mock.calls.some(([path]) => path.endsWith('/actions'))).toBe(true))
  await nextTick(); signal.refresh()
  await vi.waitFor(() => expect(document.querySelector('.plan-panel')?.textContent).toContain('SELECT LAST_SQL'))
  for (let i = 0; i < 3; i++) { signal.refresh(); await nextTick(); await nextTick() }
  expect(vi.mocked(api).mock.calls.filter(([path]) => path === '/runs/large')).toHaveLength(1)
  expect(document.querySelector('.plan-panel')?.textContent).toContain('SELECT LAST_SQL')
  expect(document.querySelector<HTMLButtonElement>('.plan-panel .button.primary')?.disabled).toBe(false)
})
