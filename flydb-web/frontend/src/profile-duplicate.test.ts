// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ProfileDialog from './components/ProfileDialog.vue'
import { en } from './i18n'
import { api } from './api'
vi.mock('./api', () => ({ api: vi.fn().mockResolvedValue([{ id: 'qa' }]) }))

it('只提交填写的环境差异，保留继承的连接和隐藏凭据', async () => {
  const profile = { id: 'dev', name: 'Dev', environment: 'dev', group: '', configPath: '/project/dev.conf', workingDirectory: '/project' }
  const snapshot = { revision: 'rev', values: { 'flydb.user': 'developer' }, effective: { 'flydb.url': 'jdbc:mysql://dev/app' }, sources: {}, secretKeys: ['flydb.password'] }
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(ProfileDialog, { mode: 'duplicate', profile, snapshot, initialDirectory: '/project',
    defaultDrivers: '/drivers', knownKeys: [] }).use(createI18n({ legacy: false, locale: 'en', messages: { en } }))
  app.mount(host)
  try {
    host.querySelector<HTMLInputElement>('input[type=checkbox]')!.click()
    const user = Array.from(host.querySelectorAll('input')).find(input => input.value === 'developer')!
    user.value = 'tester'
    user.dispatchEvent(new Event('input'))
    await nextTick()
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await vi.waitFor(() => expect(api).toHaveBeenCalledWith('/profiles', expect.objectContaining({
      mode: 'duplicate', sourceId: 'dev', revision: 'rev', inherit: true, values: { 'flydb.user': 'tester' },
    })))
  } finally { app.unmount(); host.remove() }
})
