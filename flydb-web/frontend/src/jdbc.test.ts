import { expect, it } from 'vitest'
import { parseJdbc, formatJdbc } from './jdbc'
it('edits Oracle service-name and SID targets without changing the connection form', () => {
  for (const url of [
    'jdbc:oracle:thin:@//localhost:1521/XEPDB1',
    'jdbc:oracle:thin:@localhost:1521:ORCL',
    'jdbc:oracle:thin:@db.example:1521/service?oracle.net.CONNECT_TIMEOUT=5000',
    'jdbc:oracle:thin:@//[::1]:1521/service',
  ]) {
    const parts = parseJdbc(url)
    expect(parts, url).toBeDefined()
    expect(formatJdbc(parts!)).toBe(url)
    expect(formatJdbc({ ...parts!, host: 'other.example' })).toContain('other.example:1521')
  }
})
it('preserves vendor options and IPv6 while editing a conventional JDBC target', () => {
  const original = 'jdbc:mysql://[::1]:3306/demo?useSSL=false&characterEncoding=UTF-8'
  const parsed = parseJdbc(original)!
  expect(formatJdbc(parsed)).toBe(original)
  expect(formatJdbc({ ...parsed, database: 'other' })).toBe(original.replace('/demo?', '/other?'))
})
it('leaves vendor-specific and credential-bearing URLs in direct-edit mode', () => {
  expect(parseJdbc('jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=example)))')).toBeUndefined()
  expect(parseJdbc('jdbc:oracle:thin:user/pass@//example:1521/service')).toBeUndefined()
  expect(parseJdbc('jdbc:oracle:thin:@tns_alias')).toBeUndefined()
  expect(parseJdbc('jdbc:mysql://user:pass@example/db')).toBeUndefined()
})
