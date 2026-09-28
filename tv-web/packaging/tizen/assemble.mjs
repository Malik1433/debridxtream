// Builds the folder `tizen package` turns into a .wgt: the web build + config.xml + icon, and the
// W0 test account if one sits next to package.json (it is gitignored and never committed).
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const out = join(root, 'build-tizen')

if (!existsSync(join(root, 'dist', 'index.html'))) {
  console.error('dist/ is missing: run `npm run build` first')
  process.exit(1)
}
rmSync(out, { recursive: true, force: true })
mkdirSync(out)
cpSync(join(root, 'dist'), out, { recursive: true })
cpSync(join(here, 'config.xml'), join(out, 'config.xml'))
cpSync(join(here, 'icon.png'), join(out, 'icon.png'))
const account = join(root, 'w0.local.json')
if (existsSync(account)) cpSync(account, join(out, 'w0.local.json'))
console.log(`build-tizen/ ready${existsSync(account) ? ' (with w0.local.json)' : ' (NO w0.local.json - the page will say so)'}`)
