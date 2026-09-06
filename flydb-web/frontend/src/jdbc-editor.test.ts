import { expect, it, vi } from 'vitest'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createI18n } from 'vue-i18n'
import JdbcEditor from './components/JdbcEditor.vue'

vi.stubGlobal('localStorage', { getItem: () => null })
vi.stubGlobal('navigator', { language: 'en-US' })
const { zh, en } = await import('./i18n')

it('renders Oracle in the shared selector and labels imported service/SID connections in both languages', async () => {
  for (const [locale, messages] of Object.entries({ 'zh-CN': zh, en })) {
    for (const [url, label] of [
      ['jdbc:mysql://localhost:3306/app', messages.database],
      ['jdbc:oracle:thin:@//localhost:1521/XEPDB1', messages.oracleService],
      ['jdbc:oracle:thin:@localhost:1521:ORCL', messages.oracleSid],
    ]) {
      const app = createSSRApp(JdbcEditor, { modelValue: url })
      app.use(createI18n({ legacy: false, locale, messages: { [locale]: messages } }))
      const html = await renderToString(app)
      expect(html).toMatch(/<option value="oracle"[^>]*>Oracle<\/option>/)
      expect(html).toContain(`<span>${label}</span>`)
      if (url.startsWith('jdbc:oracle:')) expect(html).toContain('<select value="oracle">')
    }
  }
})
