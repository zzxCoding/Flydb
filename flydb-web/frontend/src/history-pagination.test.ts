// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { en } from './i18n'

const fixture = vi.hoisted(() => ({ count: 28 }))
vi.mock('./api', () => ({ connect: vi.fn().mockResolvedValue(undefined), ApiError: class extends Error {}, api: vi.fn(async (path: string) => {
  if (path === '/bootstrap?compact=true') return {
    profiles: ['a', 'b'].map(id => ({ id, name: `Project ${id}`, configPath: `/tmp/${id}.conf` })), knownKeys: [],
    runs: Array.from({ length: fixture.count }, (_, i) => ({ id: `run-${i}`, profileId: i < 23 ? 'a' : 'b', profileName: `Record ${i}`, command: `command-${i}`, status: 'SUCCEEDED' })),
  }
  if (path.endsWith('/config')) return { values: { 'flydb.url': 'jdbc:mysql://localhost/app' }, effective: {}, sources: {}, secretKeys: [] }
  throw new Error(path)
}) }))
let app: VueApp
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.useRealTimers(); vi.unstubAllGlobals() })
const button = (label: string) => Array.from(document.querySelectorAll('button')).find(el => el.textContent?.trim().startsWith(label))!
async function jump(scope: ParentNode, value: string) {
  const input = scope.querySelector<HTMLInputElement>('.pagination input')!
  input.value = value; input.dispatchEvent(new Event('change')); await nextTick()
}
it('paginates both histories independently, retains pages across polls, and clamps shortened lists', async () => {
  localStorage.clear(); fixture.count = 28
  vi.stubGlobal('EventSource', class { close() {} addEventListener() {} })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(App).use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(root)
  await vi.waitFor(() => expect(root.querySelector('.profile-heading h1')?.textContent).toBe('Project a'))
  button('Run history').click(); await nextTick()
  const project = root.querySelector('.run-history')!
  expect(project.querySelectorAll('.run-row')).toHaveLength(10)
  await jump(project, '3')
  expect(project.querySelectorAll('.run-row')).toHaveLength(3)
  expect(project.textContent).toContain('command-22')
  button('All runs').click(); await nextTick()
  const all = document.querySelector('.dialog-surface.run-history')!
  expect(all.querySelectorAll('.run-row')).toHaveLength(10)
  await jump(all, '3')
  expect(all.querySelectorAll('.run-row')).toHaveLength(8)
  expect(all.textContent).toContain('Record 27')
  // The actual five-second bootstrap poll returns newly allocated objects.
  await new Promise(resolve => setTimeout(resolve, 5200))
  expect(project.querySelector<HTMLInputElement>('.pagination input')!.value).toBe('3')
  expect(all.querySelector<HTMLInputElement>('.pagination input')!.value).toBe('3')
  fixture.count = 11
  await vi.waitFor(() => expect(all.querySelectorAll('.run-row')).toHaveLength(1), { timeout: 6000 })
  expect(project.querySelector<HTMLInputElement>('.pagination input')!.value).toBe('2')
  expect(all.querySelector<HTMLInputElement>('.pagination input')!.value).toBe('2')
  all.querySelector<HTMLButtonElement>('.section-heading button')!.click(); await nextTick()
  button('Project b').click(); await nextTick()
  expect(project.querySelectorAll('.run-row')).toHaveLength(0)
  button('Project a').click(); await nextTick()
  expect(project.querySelectorAll('.run-row')).toHaveLength(10)
}, 15000)
