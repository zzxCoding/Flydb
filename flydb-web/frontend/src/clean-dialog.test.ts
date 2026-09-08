// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, h, ref, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import CleanDialog from './components/CleanDialog.vue'
import { en, zh } from './i18n'
import { api } from './api'
vi.mock('./api', () => ({ api: vi.fn().mockResolvedValue({ token: 'single-use', target: 'jdbc:missing:test', user: 'tester' }) }))
let app: App
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.clearAllMocks() })
it('preserves confirmation input across equivalent polling snapshots but resets on actual configuration changes', async () => {
  const profile = ref({ id: 'p', name: 'Test', group: '', environment: 'test', configPath: '/tmp/flydb.conf', workingDirectory: '/tmp' })
  const revision = ref('r1'), password = ref('')
  const root = document.createElement('div'); document.body.append(root)
  app = createApp({ setup: () => () => h(CleanDialog, { profile: profile.value, revision: revision.value, password: password.value, busy: false }) })
    .use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('jdbc:missing:test'))
  const input = root.querySelector<HTMLInputElement>('[placeholder="CLEAN"]')!, checkbox = root.querySelector<HTMLInputElement>('[type="checkbox"]')!
  input.value = 'CLE'; input.dispatchEvent(new Event('input')); checkbox.click(); await nextTick()
  for (let index = 0; index < 3; index++) {
    profile.value = { ...profile.value }; await nextTick(); await nextTick()
    expect(input.value).toBe('CLE'); expect(checkbox.checked).toBe(true)
  }
  expect(api).toHaveBeenCalledTimes(1)
  input.value = 'CLEAN'; input.dispatchEvent(new Event('input')); await nextTick()
  expect(root.querySelector<HTMLButtonElement>('.danger')!.disabled).toBe(false)
  revision.value = 'r2'; await nextTick(); await nextTick()
  expect(input.value).toBe(''); expect(checkbox.checked).toBe(false)
  expect(root.querySelector<HTMLButtonElement>('.danger')!.disabled).toBe(true)
  expect(api).toHaveBeenCalledTimes(2)
  input.value = 'CLEAN'; input.dispatchEvent(new Event('input')); checkbox.click(); await nextTick()
  password.value = 'synthetic-new-credential'; await nextTick(); await nextTick()
  expect(input.value).toBe(''); expect(checkbox.checked).toBe(false)
  expect(api).toHaveBeenCalledTimes(3)
  input.value = 'CLEAN'; input.dispatchEvent(new Event('input')); checkbox.click(); await nextTick()
  profile.value = { ...profile.value, workingDirectory: '/tmp/other-project' }; await nextTick(); await nextTick()
  expect(input.value).toBe(''); expect(checkbox.checked).toBe(false)
  expect(api).toHaveBeenCalledTimes(4)
})
it.each(['zh-CN', 'en'])('requires both risk acknowledgement and exact CLEAN text (%s)', async locale => {
  const execute = vi.fn(), close = vi.fn(), root = document.createElement('div'); document.body.append(root)
  app = createApp(CleanDialog, { profile: { id: 'p', name: 'Test', group: '', environment: 'test', configPath: '/tmp/flydb.conf', workingDirectory: '/tmp' }, revision: 'r1', password: '', busy: false, onExecute: execute, onClose: close })
    .use(createI18n({ legacy: false, locale, messages: { en, 'zh-CN': zh } })); app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('jdbc:missing:test'))
  const confirm = root.querySelector<HTMLButtonElement>('.danger')!, checkbox = root.querySelector<HTMLInputElement>('[type="checkbox"]')!, input = root.querySelector<HTMLInputElement>('[placeholder="CLEAN"]')!
  expect(confirm.disabled).toBe(true)
  input.value = 'CLEAN'; input.dispatchEvent(new Event('input')); await nextTick(); expect(confirm.disabled).toBe(true)
  checkbox.click(); await nextTick(); expect(confirm.disabled).toBe(false)
  input.value = 'clean'; input.dispatchEvent(new Event('input')); await nextTick(); expect(confirm.disabled).toBe(true)
  input.value = 'CLEAN'; input.dispatchEvent(new Event('input')); await nextTick(); confirm.click()
  expect(execute).toHaveBeenCalledExactlyOnceWith({ cleanToken: 'single-use', confirmationText: 'CLEAN', confirmed: true })
  expect(api).toHaveBeenCalledExactlyOnceWith('/profiles/p/clean-confirmation', { revision: 'r1', password: undefined })
  root.querySelector<HTMLButtonElement>('.icon-button')!.click(); expect(close).toHaveBeenCalledOnce()
})
