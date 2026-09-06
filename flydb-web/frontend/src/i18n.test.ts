import { describe, expect, it, vi } from 'vitest'
vi.stubGlobal('localStorage', { getItem: () => null })
vi.stubGlobal('navigator', { language: 'en-US' })
const { zh, en, i18n } = await import('./i18n')

function keys(value: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(value).flatMap(([key, item]) => typeof item === 'object' && item !== null
    ? keys(item as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]).sort()
}
describe('bilingual interface', () => {
  it('has a complete English counterpart and compiles every message in both locales', () => {
    expect(keys(en)).toEqual(keys(zh))
    for (const locale of ['en', 'zh-CN'] as const) {
      i18n.global.locale.value = locale
      for (const key of keys(zh)) {
        expect(i18n.global.t(key, { count: 2, line: 1 })).not.toEqual(key)
      }
    }
  })
  it('uses English singular and plural forms', () => {
    i18n.global.locale.value = 'en'
    expect(i18n.global.t('statementCount', { count: 1 })).toBe('1 SQL statement')
    expect(i18n.global.t('statementCount', { count: 2 })).toBe('2 SQL statements')
  })
})
