// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, type App as VueApp } from 'vue'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { en, zh } from './i18n'

vi.mock('./api', () => ({
  connect: vi.fn().mockResolvedValue(undefined), ApiError: class extends Error {},
  api: vi.fn(async (path: string) => {
    if (path === '/bootstrap?compact=true') return {
      profiles: [{ id: 'project', name: 'Project', configPath: '/tmp/project/flydb.conf', workingDirectory: '/tmp/project' }],
      runs: [{ id: 'inspection', profileId: 'project', command: 'inspect', status: 'SUCCEEDED', result: {
        current: '20260901.3', migrations: [
          { script: 'V20260101.1__older.sql', version: '20260101.1', state: 'OUT_OF_ORDER' },
          { script: 'V20260101.2__older.sql', version: '20260101.2', state: 'OUT_OF_ORDER' },
          { script: 'V20260901.3__newer.sql', version: '20260901.3', state: 'FUTURE' },
        ],
      } }], version: 'test', knownKeys: [],
    }
    if (path.endsWith('/config')) return { revision: 'one', values: {}, effective: {}, sources: {}, secretKeys: [] }
    throw new Error('Unexpected API request: ' + path)
  }),
}))
let app: VueApp
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.unstubAllGlobals() })
it.each(['zh-CN', 'en'])('counts out-of-order scripts as pending, with a truthful label (%s)', async locale => {
  localStorage.clear()
  localStorage.setItem('flydb.language', locale)
  vi.stubGlobal('EventSource', class { close() {} addEventListener() {} })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(App).use(createI18n({ legacy: false, locale, messages: { en, 'zh-CN': zh } }))
  app.mount(root)
  await vi.waitFor(() => expect(root.querySelector('.migration-summary')).not.toBeNull())
  expect(Array.from(root.querySelectorAll('.migration-summary strong'), el => el.textContent)).toEqual(['20260901.3', '2', '1'])
  expect(root.querySelector('[data-state="OUT_OF_ORDER"]')?.textContent).toBe(locale === 'en' ? 'Out of order (pending)' : '乱序待执行')
  const guidance = root.querySelector('.history-mismatch')
  expect(guidance?.textContent).toContain(locale === 'en' ? 'locations' : '迁移目录')
  guidance?.querySelector<HTMLButtonElement>('button')?.click()
  await vi.waitFor(() => expect(root.querySelector('.content-tabs [aria-current="page"]')?.textContent).toContain(locale === 'en' ? 'Connection' : '连接'))
})
