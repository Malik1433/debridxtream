// Builds the folder a TV's packager turns into an app: the web build + that TV's manifest and icon,
// and the W0 test account if one sits next to package.json (gitignored, never committed).
//   node packaging/assemble.mjs tizen   -> build-tizen/  (then: tizen package -t wgt ...)
//   node packaging/assemble.mjs webos   -> build-webos/  (then: ares-package build-webos)
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const MANIFEST = { tizen: 'config.xml', webos: 'appinfo.json' }
const target = process.argv[2]
if (!MANIFEST[target]) {
  console.error('usage: node packaging/assemble.mjs <tizen|webos>')
  process.exit(1)
}
const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const out = join(root, `build-${target}`)
if (!existsSync(join(root, 'dist', 'index.html'))) {
  console.error('dist/ is missing: run `npm run build` first')
  process.exit(1)
}
rmSync(out, { recursive: true, force: true })
mkdirSync(out)
cpSync(join(root, 'dist'), out, { recursive: true })
cpSync(join(here, target, MANIFEST[target]), join(out, MANIFEST[target]))
cpSync(join(here, target, 'icon.png'), join(out, 'icon.png'))
const account = join(root, 'w0.local.json')
if (existsSync(account)) cpSync(account, join(out, 'w0.local.json'))
console.log(`build-${target}/ ready${existsSync(account) ? ' (with w0.local.json for w0.html)' : ''}`)
