import fs from 'node:fs'
import path from 'node:path'

const root = process.argv[2]
const patchDir = process.argv[3]
if (!root || !patchDir) {
  throw new Error('usage: node apply-mobile-overlay.mjs <patched checkout> <qce patch dir>')
}

const ui = path.join(root, 'qce-v4-tool')
const cssCandidates = [
  path.join(ui, 'app', 'globals.css'),
  path.join(ui, 'styles', 'globals.css'),
]
const cssPath = cssCandidates.find((candidate) => fs.existsSync(candidate))
if (!cssPath) throw new Error('Pinned QCE source has no global stylesheet')
const marker = '/* yingge-cloud-mobile-workbench */'
let css = fs.readFileSync(cssPath, 'utf8')
if (!css.includes(marker)) {
  css = `${css.trimEnd()}

${marker}
html, body { max-width: 100%; overflow-x: hidden; }
button, [role="button"], a, input, select, textarea { touch-action: manipulation; }
@media (max-width: 760px) {
  input, select, textarea { font-size: 16px !important; }
  button, [role="button"] { min-height: 44px; }
  [role="dialog"] { max-width: 100vw !important; max-height: 100dvh !important; }
  [role="dialog"] > div { max-width: 100%; }
  .fixed.inset-0 { overscroll-behavior: contain; }
  .overflow-y-auto, .overflow-auto { overscroll-behavior: contain; -webkit-overflow-scrolling: touch; }
  .break-words { overflow-wrap: anywhere; }
}
`
  fs.writeFileSync(cssPath, css)
}

fs.writeFileSync(path.join(root, 'YINGGE-MODIFICATIONS.md'), `# Yingge Cloud modifications

This pinned GPL-3.0 component is adapted at build time to use Yingge Cloud session/CSRF authentication, an allowlisted server-side gateway, the /qq-workbench path, a session-bound WebSocket relay, ranked 300 ms session search, an explicit CRM sync action, mobile-safe dialogs and form controls, and member-volume-backed QQ auto-login restoration. No QCE or Bridge token is exposed to the browser.
`)

const entrypoint = path.join(root, 'docker', 'docker-entrypoint-qce.sh')
fs.copyFileSync(path.join(patchDir, 'docker-entrypoint-qce.sh'), entrypoint)
fs.chmodSync(entrypoint, 0o755)

const dockerfile = path.join(root, 'docker', 'Dockerfile')
let dockerSource = fs.readFileSync(dockerfile, 'utf8')
if (!dockerSource.includes('RUN rm -rf /app/napcat/static/qce')) {
  dockerSource = dockerSource.replace(
    '# Copy frontend static export\nCOPY --from=build /build/qce-v4-tool/out /app/napcat/static/qce',
    '# The runtime base can contain an older QCE WebUI. Remove it so stale chunks cannot survive the overlay.\nRUN rm -rf /app/napcat/static/qce\nCOPY --from=build /build/qce-v4-tool/out /app/napcat/static/qce',
  )
  fs.writeFileSync(dockerfile, dockerSource)
}

const requiredChecks = [
  [path.join(ui, 'lib', 'auth.ts'), "const QCE_GATEWAY = '/api/v1/qq-workbench/qce'"],
  [path.join(ui, 'hooks', 'use-websocket.ts'), '/api/v1/qq-workbench/ws'],
  [cssPath, marker],
  [entrypoint, 'autoLoginAccount // empty'],
  [dockerfile, '/usr/share/licenses/qq-chat-exporter/'],
  [dockerfile, 'RUN rm -rf /app/napcat/static/qce'],
]
for (const [file, expected] of requiredChecks) {
  if (!fs.readFileSync(file, 'utf8').includes(expected)) {
    throw new Error(`Previously patched QCE source is missing required integration: ${file}`)
  }
}

console.log('Applied idempotent Yingge Cloud QCE mobile overlay')
