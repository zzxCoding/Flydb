// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { en } from './i18n'
vi.mock('./api', () => ({ connect: vi.fn().mockResolvedValue(undefined), ApiError: class extends Error {}, api: vi.fn(async (path: string) => {
  if (path === '/bootstrap?compact=true') return { profiles: [{ id: 'many', name: 'Many versions', configPath: '/tmp/flydb.conf' }], knownKeys: [], runs: [{
    id: 'info', profileId: 'many', command: 'inspect', status: 'SUCCEEDED', result: { migrations: Array.from({ length: 5000 }, (_, index) => ({
      version: String(index + 1), script: `V${index + 1}__migration.sql`, description: '', state: 'PENDING',
    })) },
  }] }
  if (path.endsWith('/config')) return { values: {}, effective: {}, sources: {}, secretKeys: [] }
  throw new Error(path)
}) }))
let app: VueApp
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.unstubAllGlobals() })
it('renders only one page of 5000 versions while supporting last-page jumps and searching the entire set', async () => {
  localStorage.clear(); vi.stubGlobal('EventSource', class { close() {} addEventListener() {} })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(App).use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(root)
  await vi.waitFor(() => expect(root.querySelectorAll('.migration-table tbody tr').length).toBe(10))
  const page = root.querySelector<HTMLInputElement>('.pagination input')!
  page.value = '500'; page.dispatchEvent(new Event('change')); await nextTick()
  expect(root.querySelector('.migration-table')?.textContent).toContain('V5000__migration.sql')
  const search = root.querySelector<HTMLInputElement>('.migration-search')!
  search.value = 'V5000__'; search.dispatchEvent(new Event('input')); await nextTick()
  expect(root.querySelectorAll('.migration-table tbody tr').length).toBe(1)
  expect(root.querySelector('.migration-summary')?.textContent).toContain('5,000')
  search.value = 'no-such-version'; search.dispatchEvent(new Event('input')); await nextTick()
  expect(root.querySelectorAll('.migration-table tbody tr').length).toBe(0)
  expect(root.querySelector('.pagination')?.textContent).toContain('0–0 of 0')
})
