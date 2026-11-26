// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { expect, it, vi } from 'vitest'
import ConnectionEditor from './components/ConnectionEditor.vue'
import { api } from './api'
import { zh } from './i18n'

vi.mock('./api', () => ({ api: vi.fn(), ApiError: class extends Error {} }))

it('集中核对环境覆盖和继承值，按用途找到未配置的键，多项修改只保存一次', async () => {
  const snapshot = {
    revision: 'qa-1', values: { 'flydb.user': 'developer' }, secretKeys: ['flydb.password'],
    effective: { 'flydb.user': 'runner', 'flydb.batch-size': '20', 'flydb.out-of-order': 'false' },
    sources: { 'flydb.user': 'ENVIRONMENT', 'flydb.batch-size': 'IMPORTED_FILE', 'flydb.out-of-order': 'DEFAULT', 'flydb.password': 'PASSWORD_FILE' },
    declaredAt: { 'flydb.user': 'FLYDB_USER', 'flydb.batch-size': '/project/team.conf' },
  }
  const knownKeys = [...Object.keys(snapshot.sources), 'flydb.target-version', 'flydb.version-selection']
  const props = { profile: { id: 'qa', name: 'QA', group: '', environment: 'qa', configPath: '/project/qa.conf', workingDirectory: '/project' },
    knownKeys, snapshot, defaults: { 'flydb.batch-size': '1', 'flydb.out-of-order': 'false' }, temporaryPassword: '' }
  vi.mocked(api).mockResolvedValue(snapshot)
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(ConnectionEditor, props).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } }))
  app.mount(host)
  const button = (label: string) => Array.from(host.querySelectorAll('button')).find(el => el.textContent?.trim().startsWith(label))!
  const input = (name: string) => host.querySelector<HTMLInputElement>(`input[aria-label="${name}"]`)!
  const select = (name: string) => host.querySelector<HTMLSelectElement>(`select[aria-label="${name}"]`)!
  async function choose(name: string, value: string) {
    const el = select(name)
    el.value = value; el.dispatchEvent(new Event('change')); await nextTick()
  }
  async function type(el: HTMLInputElement, value: string) {
    el.focus()
    expect(document.activeElement === el).toBe(true)
    el.value = value; el.dispatchEvent(new Event('input')); await nextTick()
  }
  try {
    expect(input('flydb.user').value).toBe('developer')
    expect(host.querySelector('.catalog-list')?.textContent).toContain('runner')
    expect(host.textContent).toContain('/project/team.conf')
    expect(input('flydb.password').value).toBe('')
    expect(host.textContent).toContain('${env:DB_PASSWORD}')
    expect(select('flydb.out-of-order')).toBeNull()
    button('优先核对').click(); await nextTick()
    expect(host.querySelectorAll('.catalog-row')).toHaveLength(1)
    expect(host.querySelector('.catalog-list')?.textContent).toContain('FLYDB_USER')
    expect(button('保存配置').disabled).toBe(true)
    expect(api).not.toHaveBeenCalled()
    const search = host.querySelector<HTMLInputElement>('.catalog-search input')!
    await type(search, 'family-range')
    const mode = 'flydb.version-selection'
    expect(select(mode).textContent).toContain('含结束版本的子版本')
    expect(select(mode).value).toBe('@inherit')
    await choose(mode, '@custom')
    expect(button('保存配置').disabled).toBe(true)
    await type(input('手动输入或引用环境变量 ' + mode), '${env:RELEASE_MODE}')
    await type(search, 'batch-size')
    expect(document.getElementById(input('flydb.batch-size').getAttribute('aria-describedby')!)?.textContent).toContain('最小为 1')
    await type(search, 'family-range')
    expect(input('手动输入或引用环境变量 ' + mode).value).toBe('${env:RELEASE_MODE}')
    await choose(mode, 'family-range')
    expect(select(mode).value).toBe('family-range')
    await choose(mode, '@inherit')
    expect(button('保存配置').disabled).toBe(true)
    await type(search, '乱序')
    expect(host.textContent).toContain('默认值')
    await choose('flydb.out-of-order', 'true')
    await type(search, 'target-version')
    await type(input('flydb.target-version'), '3')
    button('全部配置').click(); await nextTick()
    expect(host.querySelectorAll('.catalog-row')).toHaveLength(knownKeys.length)
    expect(input('flydb.batch-size').value).toBe('')
    expect(host.textContent).toContain('待保存 2 项')
    button('保存配置').click()
    await vi.waitFor(() => expect(api).toHaveBeenCalledTimes(1))
    expect(api).toHaveBeenCalledWith('/profiles/qa/config', { revision: 'qa-1', values: {
      'flydb.out-of-order': 'true', 'flydb.target-version': '3',
    } }, 'PUT')
  } finally { app.unmount(); host.remove() }
})
