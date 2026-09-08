// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import PlanPanel from './components/PlanPanel.vue'
import { en } from './i18n'
vi.mock('./components/SqlPreview.vue', async () => { const { h } = await import('vue'); return { default: {
  props: ['text'], setup: (props: { text: string }) => () => h('div', { class: 'sql-document', 'data-length': props.text.length }, props.text.slice(-40)),
} } })
let app: App
afterEach(() => { app?.unmount(); document.body.replaceChildren() })
it('finds and selects scripts outside the first page without changing the plan', async () => {
  const root = document.createElement('div'); document.body.append(root)
  const execute = vi.fn()
  app = createApp(PlanPanel, { busy: false, onExecute: execute, run: { id: 'many', status: 'SUCCEEDED', result: {
    planId: 'unchanged', migrations: Array.from({ length: 5000 }, (_, index) => ({ script: `V${index + 1}__many.sql`, version: String(index + 1), type: 'SQL', description: '', statements: [{ lineNumber: 1, sql: `SELECT ${index + 1}` }] })),
  } } }).use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(root); await nextTick()
  expect(root.querySelectorAll('.plan-files nav button').length).toBe(50)
  const search = root.querySelector<HTMLInputElement>('.plan-files input[type="search"]')!
  search.value = 'V5000__'; search.dispatchEvent(new Event('input')); await nextTick()
  expect(root.querySelectorAll('.plan-files nav button').length).toBe(1)
  root.querySelector<HTMLButtonElement>('.plan-files nav button')!.click()
  await vi.waitFor(() => expect(root.querySelector('.sql-document')?.textContent).toContain('SELECT 5000'))
  root.querySelector<HTMLButtonElement>('.dialog-actions .primary')!.click(); expect(execute).toHaveBeenCalledOnce()
})
it('keeps large previews bounded in DOM while preserving the last SQL statement', async () => {
  const root = document.createElement('div'); document.body.append(root)
  const statements = Array.from({ length: 10000 }, (_, index) => ({ lineNumber: index + 1, sql: `SELECT ${index}` }))
  app = createApp(PlanPanel, { busy: false, run: { id: 'large', status: 'SUCCEEDED', result: {
    planId: 'unchanged', migrations: [{ script: 'V1__large.sql', version: '1', type: 'SQL', description: '', statements, statementCount: statements.length }],
  } } }).use(createI18n({ legacy: false, locale: 'en', messages: { en } }))
  app.mount(root); await nextTick()
  await vi.waitFor(() => expect(root.textContent).toContain('SELECT 9999'))
  expect(root.querySelectorAll('*').length).toBeLessThan(150)
})
