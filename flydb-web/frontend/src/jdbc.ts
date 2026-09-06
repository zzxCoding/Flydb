export interface JdbcParts { prefix: string; host: string; port: string; database: string; suffix: string; separator?: '/' | ':' }
export function parseJdbc(value: string): JdbcParts | undefined {
  const oracle = /^(jdbc:oracle:thin:@(?:\/\/)?)(\[[^\]]+\]|[^\s/:?#;@()]+)(?::(\d+))?([/:])([^\s?;#:/()]+)(.*)$/.exec(value)
  if (oracle) return { prefix: oracle[1], host: oracle[2].replace(/^\[|\]$/g, ''), port: oracle[3] ?? '', separator: oracle[4] as '/' | ':', database: oracle[5], suffix: oracle[6] }
  const match = /^(jdbc:(?:mysql|postgresql|dm|kingbase8|opengauss):\/\/)(\[[^\]]+\]|[^/:?#;@]+)(?::(\d+))?\/([^?;#]*)(.*)$/.exec(value)
  if (!match) return undefined
  return { prefix: match[1], host: match[2].replace(/^\[|\]$/g, ''), port: match[3] ?? '', database: match[4], suffix: match[5] }
}
export function formatJdbc(parts: JdbcParts): string {
  const host = parts.host.includes(':') ? `[${parts.host}]` : parts.host
  return `${parts.prefix}${host}${parts.port ? `:${parts.port}` : ''}${parts.separator ?? '/'}${parts.database}${parts.suffix}`
}
