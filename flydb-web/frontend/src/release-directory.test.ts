// @vitest-environment happy-dom
import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { expect, it, vi } from 'vitest'
import App from './App.vue'
import { api } from './api'
import { zh } from './i18n'
import type { Run } from './types'

vi.mock('./api', () => ({ connect: vi.fn(), api: vi.fn(), ApiError: class extends Error {} }))

it('切换发布目录和用回默认目录都只检查状态，按库历史标出旧目录缺少的脚本', async () => {
  localStorage.clear()
  vi.stubGlobal('EventSource', class { close() {} addEventListener() {} })
  const root = '/orders/releases', first = root + '/release-1', second = root + '/release-2'
  const runs: Run[] = []
  const revision = 'original'
  vi.mocked(api).mockImplementation(async (path, body) => {
    if (path.startsWith('/bootstrap')) return {
      profiles: [{ id: 'qa', name: 'QA', environment: 'QA', group: 'Orders', configPath: '/orders/qa.conf', workingDirectory: '/orders' }], runs: [...runs], knownKeys: [],
    }
    if (path.endsWith('/config')) return { revision, values: {}, sources: {}, effective: { 'flydb.locations': 'filesystem:' + first }, secretKeys: [] }
    if (path === '/profiles/qa/scripts') {
      expect(body).toEqual({ revision, description: 'add_order_note', releaseDirectory: second })
      return { path: second + '/V3__add_order_note.sql', version: '3' }
    }
    if (path.startsWith('/files?')) {
      const dir = decodeURIComponent(path.split('=')[1]).replace(/\/$/, '')
      return { path: dir, parent: root, entries: dir === root ? [{ name: 'release-2', path: second, directory: true }] : [] }
    }
    if (path.endsWith('/actions')) {
      const request = body as { command: string; releaseDirectory?: string }
      expect(request.command).toBe('inspect')
      const run: Run = { id: String(runs.length + 1), profileId: 'qa', command: 'inspect', status: 'SUCCEEDED', configRevision: revision,
        releaseDirectory: request.releaseDirectory, result: { current: '2', migrations: ['V1__init.sql', 'V2__index.sql'].map((script, index) => ({
          script, version: String(index + 1), state: index && !request.releaseDirectory ? 'FUTURE' : 'SUCCESS', description: '', type: 'SQL',
        })) } }
      runs.unshift(run)
      return run
    }
    throw new Error(path)
  })
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(App).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } }))
  app.mount(host)
  const button = (label: string) => Array.from(document.querySelectorAll('button')).find(el => el.textContent?.trim() === label)!
  try {
    await vi.waitFor(() => expect(button('更换 SQL 目录')?.disabled).toBe(false))
    button('更换 SQL 目录').click()
    await vi.waitFor(() => expect(button('release-2')).toBeTruthy())
    button('release-2').click()
    await vi.waitFor(() => expect(document.querySelector<HTMLInputElement>('.file-picker input')?.value).toBe(second))
    button('使用此目录并检查').click()
    await vi.waitFor(() => expect(host.querySelector('.migration-summary')?.textContent).toContain('数据库当前版本2'))
    expect(runs[0].releaseDirectory).toBe(second)
    expect(host.querySelector('.release-paths summary')?.textContent).toBe('release-2')
    expect(host.querySelector('.history-mismatch')).toBeNull()
    expect(host.querySelector('.migration-footer')?.textContent).not.toContain(first)
    button('新建 SQL 脚本').click()
    await vi.waitFor(() => expect(document.querySelector('.new-script code')?.textContent).toBe(second))
    expect(button('创建空脚本').disabled).toBe(true)
    document.querySelector<HTMLElement>('.new-script summary')!.click()
    for (const key of ['scriptVersion', 'directory', 'description']) {
      const field = document.querySelector<HTMLInputElement>(`.new-script input[name="${key}"]`)!
      field.focus()
      expect(document.activeElement === field, `${key} 应能保留键盘焦点`).toBe(true)
    }
    const name = document.querySelector<HTMLInputElement>('.new-script input[name="description"]')!
    name.value = 'add_order_note'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.waitFor(() => expect(button('创建空脚本').disabled).toBe(false))
    button('创建空脚本').click()
    await vi.waitFor(() => expect(document.querySelector('.new-script code')?.textContent).toBe(second + '/V3__add_order_note.sql'))
    expect(runs).toHaveLength(1)
    document.querySelector<HTMLButtonElement>('.new-script .dialog-actions button')!.click()
    button('用回默认目录（release-1）').click()
    await vi.waitFor(() => expect(host.querySelector('.history-mismatch')?.textContent).toContain('V2__index.sql'))
    expect(runs.map(r => r.releaseDirectory)).toEqual([undefined, second])
    expect(host.querySelector('.release-paths summary')?.textContent).toBe('release-1')
    expect(Array.from(host.querySelectorAll('.migration-summary strong'), el => el.textContent)).toEqual(['2', '0', '2'])
    expect(document.querySelector('.run-panel')).toBeNull()
    button('新建 SQL 脚本').click()
    await vi.waitFor(() => expect(document.querySelector('.new-script .warning')?.textContent).toContain('V2__index.sql'))
  } finally { app.unmount(); host.remove(); vi.unstubAllGlobals() }
})
