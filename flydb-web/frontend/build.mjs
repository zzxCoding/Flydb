import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
import { resolve } from 'node:path'
import { rmSync } from 'node:fs'

const cwd = dirname(fileURLToPath(import.meta.url))
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const options = { cwd, stdio: 'inherit', shell: process.platform === 'win32' }
execFileSync(npm, ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], options)
execFileSync(npm, ['run', 'build'], options)
// Maven's resource copy does not remove obsolete hashed bundles from earlier builds.
rmSync(resolve(cwd, '../target/classes/web'), { recursive: true, force: true })
if (!process.argv.includes('--skip-tests=true')) execFileSync(npm, ['test'], options)
