// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import Sidebar from './components/ProfileSidebar.vue'
import { en, zh } from './i18n'
import { api } from './api'
vi.mock('./api', () => ({ api: vi.fn().mockResolvedValue([]), ApiError: class extends Error {} }))
let app: App
afterEach(() => { app?.unmount(); document.body.replaceChildren(); vi.clearAllMocks() })
function mount(locale = 'en') {
  localStorage.clear()
  const props = reactive({ profiles: [{ id: 'p', name: 'Project', group: 'A', environment: '', configPath: '/tmp/p.conf', workingDirectory: '/tmp' }], groups: ['A', 'B'], runs: [], selected: 'p', stateDirectory: '/tmp/groups' })
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(Sidebar, props).use(createI18n({ legacy: false, locale, messages: { en, 'zh-CN': zh } })); app.mount(root)
  return { root, props }
}
const click = async (selector: string) => { document.querySelector<HTMLButtonElement>(selector)!.click(); await nextTick() }
const input = async (selector: string, value: string) => { const el = document.querySelector<HTMLInputElement>(selector)!; el.value = value; el.dispatchEvent(new Event('input')); await nextTick() }
const submit = async () => { document.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await nextTick(); await nextTick() }
it('keeps keyboard focus in the rename input and move selector with the real modal focus trap', async () => {
  mount('zh-CN')
  await click('[data-group="A"] .group-menu-button')
  await click('[data-group="A"] .group-menu button:first-child')
  const field = document.querySelector<HTMLInputElement>('.n-modal input')!
  field.focus()
  expect(document.activeElement).toBe(field)
  await input('.n-modal input', '可输入的新名称')
  await click('.n-modal .primary'); await nextTick()
  expect(api).toHaveBeenLastCalledWith('/groups', { action: 'rename', name: 'A', newName: '可输入的新名称' })
  await vi.waitFor(() => expect(document.querySelector('.n-modal')).toBeNull())
  await click('.profile-move')
  const select = document.querySelector<HTMLSelectElement>('.n-modal select')!
  select.focus()
  expect(document.activeElement).toBe(select)
})
it('saves a renamed group by clicking the actual submit button after repeated opens', async () => {
  mount('zh-CN')
  for (let i = 0; i < 3; i++) {
    await click('[data-group="A"] .group-menu-button')
    await click('[data-group="A"] .group-menu button:first-child')
    await input('form input', `重命名${i}`)
    await click('form .primary'); await nextTick()
    expect(api).toHaveBeenLastCalledWith('/groups', { action: 'rename', name: 'A', newName: `重命名${i}` })
    await vi.waitFor(() => expect(document.querySelector('form')).toBeNull())
  }
})
it('retains collapse and new-group drafts across fresh props, searches inside collapsed groups', async () => {
  const { root, props } = mount()
  await click('[data-group="A"] .group-toggle')
  expect(root.querySelector('[data-group="A"] .group-toggle')?.getAttribute('aria-expanded')).toBe('false')
  expect(localStorage.getItem('flydb.collapsedGroups:/tmp/groups')).toBe('["A"]')
  await input('.search input', 'Project')
  expect(root.querySelector('[data-group="A"] .group-toggle')?.getAttribute('aria-expanded')).toBe('true')
  await input('.search input', '')
  await click('.group-tools button')
  await input('form input', 'New group')
  props.profiles = props.profiles.map(p => ({ ...p })); props.groups = [...props.groups]; await nextTick()
  expect(document.querySelector<HTMLInputElement>('form input')!.value).toBe('New group')
  await submit()
  expect(api).toHaveBeenCalledWith('/groups', { action: 'create', name: '', newName: 'New group' })
})
it.each(['en', 'zh-CN'])('requires explicit deletion and offers profile movement without dragging in %s', async locale => {
  mount(locale)
  await click('[data-group="A"] .group-menu-button')
  await click('[data-group="A"] .group-menu button:last-child')
  expect(api).not.toHaveBeenCalled()
  expect(document.querySelector('form')!.textContent).toContain(locale === 'en' ? 'Configuration files' : '不会删除配置文件')
  await submit()
  expect(api).toHaveBeenLastCalledWith('/groups', { action: 'delete', name: 'A', newName: '' })
  await click('.profile-move')
  const select = document.querySelector<HTMLSelectElement>('form select')!
  select.value = 'B'; select.dispatchEvent(new Event('change')); await nextTick(); select.closest('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await nextTick(); await nextTick()
  expect(api).toHaveBeenLastCalledWith('/groups', { action: 'moveProfile', profileId: 'p', name: 'B' })
})
it('uses internal drag state for group ordering and cross-group moves, ignoring outside drops', async () => {
  const { root } = mount()
  const drop = () => root.querySelector('[data-group="B"]')!.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }))
  drop(); await nextTick(); expect(api).not.toHaveBeenCalled()
  const start = (selector: string) => {
    const event = new Event('dragstart', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'dataTransfer', { value: { setData: vi.fn(), effectAllowed: '' } })
    root.querySelector(selector)!.dispatchEvent(event)
  }
  start('[data-group="A"] .group-toggle'); drop(); await nextTick(); await nextTick()
  expect(api).toHaveBeenLastCalledWith('/groups', { action: 'move', name: 'A', before: 'B' })
  start('.group-profile'); drop(); await nextTick(); await nextTick()
  expect(api).toHaveBeenLastCalledWith('/groups', { action: 'moveProfile', profileId: 'p', name: 'B' })
})
