import { readFileSync, readdirSync, existsSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const directory = dirname(fileURLToPath(import.meta.url))
const lock = JSON.parse(readFileSync(join(directory, 'package-lock.json'), 'utf8'))
const notices = ['Flydb Web frontend — third-party notices',
  'The following runtime packages may contribute code to the bundled interface.\n']
for (const [location, entry] of Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b))) {
  if (!location || entry.dev || entry.link) continue
  const path = join(directory, location)
  if (!existsSync(join(path, 'package.json'))) continue
  const pkg = JSON.parse(readFileSync(join(path, 'package.json'), 'utf8'))
  notices.push(`\n${'='.repeat(72)}\n${pkg.name} ${pkg.version}\nLicense: ${pkg.license ?? entry.license ?? 'See package metadata'}`)
  for (const name of readdirSync(path).filter(name => /^(?:licen[cs]e|copying|notice)(?:\.[a-z0-9-]+)?$/i.test(name)).sort()) {
    notices.push(readFileSync(join(path, name), 'utf8'))
  }
}
writeFileSync(resolve(directory, '../target/frontend/THIRD_PARTY_NOTICES.txt'), notices.join('\n'))
