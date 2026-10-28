import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = process.argv[2]
if (!root) throw new Error('usage: node apply.mjs <qq-chat-exporter checkout>')
const ui = path.join(root, 'qce-v4-tool')
const patchDir = path.dirname(fileURLToPath(import.meta.url))

function file(relative) { return path.join(ui, relative) }
function read(relative) { return fs.readFileSync(file(relative), 'utf8') }
function write(relative, value) { fs.writeFileSync(file(relative), value) }
function replace(relative, before, after) {
  const source = read(relative)
  if (!source.includes(before)) throw new Error(`Pinned upstream patch context missing: ${relative}`)
  write(relative, source.replace(before, after))
}

write('lib/auth.ts', `/**
 * Yingge Cloud session adapter for the pinned QQ Chat Exporter WebUI.
 * QCE and Bridge credentials remain server-side and are never persisted by this UI.
 */
const QCE_GATEWAY = '/api/v1/qq-workbench/qce'
const QCE_ROOTS = ['/api/', '/health', '/security-status', '/downloads/', '/scheduled-downloads/', '/resources/']

let csrfToken = ''

function qceUrl(input: string): string {
  const parsed = new URL(input, window.location.origin)
  if (parsed.origin !== window.location.origin || parsed.pathname.startsWith('/api/v1/')) return input
  if (!QCE_ROOTS.some((prefix) => parsed.pathname.startsWith(prefix))) return input
  parsed.searchParams.delete('token')
  parsed.searchParams.delete('accessToken')
  return QCE_GATEWAY + parsed.pathname + parsed.search + parsed.hash
}

async function csrf(originalFetch: typeof window.fetch): Promise<string> {
  if (csrfToken) return csrfToken
  const response = await originalFetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法获取客户云安全令牌')
  csrfToken = String((await response.json()).token || '')
  return csrfToken
}

export class AuthManager {
  private static instance: AuthManager
  private installed = false
  static getInstance(): AuthManager {
    if (!AuthManager.instance) AuthManager.instance = new AuthManager()
    return AuthManager.instance
  }
  initialize() {
    if (typeof window === 'undefined' || this.installed) return
    this.installed = true
    const originalFetch = window.fetch.bind(window)
    window.fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const originalUrl = input instanceof Request ? input.url : input.toString()
      const rewritten = qceUrl(originalUrl)
      const method = (init.method || (input instanceof Request ? input.method : 'GET')).toUpperCase()
      const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined))
      if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && rewritten.startsWith(QCE_GATEWAY)) {
        headers.set('X-XSRF-TOKEN', await csrf(originalFetch))
      }
      const target = input instanceof Request ? new Request(rewritten, input) : rewritten
      const response = await originalFetch(target, { ...init, headers, credentials: 'same-origin' })
      // A 403 can be an intentional capability denial (for example QCE's
      // platform-level IP whitelist settings). Only 401 means the customer
      // cloud session needs to be recovered; treating every 403 as expired
      // causes the parent iframe to reload forever.
      if (rewritten.startsWith(QCE_GATEWAY) && response.status === 401) {
        window.parent.postMessage({ type: 'yingge:qq-auth-required', version: 1 }, window.location.origin)
      }
      return response
    }
  }
  setToken(_token: string) {}
  getToken(): string | null { return null }
  clearToken() { csrfToken = '' }
  isAuthenticated(): boolean { return true }
}

export default AuthManager
`)

write('components/auth-provider.tsx', `'use client'

import { useEffect, useState } from 'react'
import AuthManager from '@/lib/auth'
import { Loader } from '@/components/ui/loader'

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<'checking' | 'ready' | 'failed'>('checking')
  useEffect(() => {
    const auth = AuthManager.getInstance()
    auth.initialize()
    fetch('/api/v1/qq-workbench/bootstrap', { credentials: 'same-origin' })
      .then((response) => { if (!response.ok) throw new Error('Cloud session rejected'); setState('ready') })
      .catch(() => setState('failed'))
  }, [])
  if (state !== 'ready') return (
    <div className="fixed inset-0 bg-neutral-50 dark:bg-neutral-950 flex items-center justify-center z-50">
      <div className="flex flex-col items-center gap-5 text-center px-6">
        {state === 'checking' ? <Loader size={24} className="text-neutral-600 dark:text-neutral-300" /> : null}
        <div className="text-xl font-semibold text-neutral-900 dark:text-neutral-100">
          {state === 'failed' ? 'QQ 工作台授权已失效' : '正在验证客户云会话'}
        </div>
        {state === 'failed' ? <p className="text-sm text-neutral-500">请回到客户云刷新连接状态后重试。</p> : null}
      </div>
    </div>
  )
  return <>{children}</>
}
`)

write('app/auth/page.tsx', `'use client'

import { useEffect } from 'react'

export default function DisabledTokenLoginPage() {
  useEffect(() => { window.location.replace('/qq-workbench') }, [])
  return <main className="min-h-screen flex items-center justify-center bg-neutral-50 text-neutral-700">请从英格客户云进入 QQ 工作台</main>
}
`)

write('hooks/use-api.ts', `import { useCallback, useMemo } from "react"
import type { APIResponse } from "@/types/api"

export function useApi() {
  const apiCall = useCallback(async <T,>(endpoint: string, options?: RequestInit): Promise<APIResponse<T>> => {
    const response = await fetch(endpoint, {
      ...options,
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'X-Request-ID': \`req_\${Date.now()}_\${Math.random().toString(36).substring(2, 11)}\`,
        ...options?.headers,
      },
    })
    const data = await response.json()
    if (!response.ok) throw new Error(data.error?.message || \`HTTP \${response.status}\`)
    return data
  }, [])

  const downloadFile = useCallback(async (fileName: string, downloadUrl?: string) => {
    if (!downloadUrl?.startsWith('/api/v1/qq-workbench/media/')) {
      throw new Error('下载地址无效或已过期，请刷新任务列表后重试')
    }
    const response = await fetch(downloadUrl, { credentials: 'same-origin' })
    if (!response.ok) throw new Error(\`HTTP \${response.status}\`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    window.URL.revokeObjectURL(url)
    document.body.removeChild(anchor)
  }, [])

  return useMemo(() => ({ apiCall, downloadFile }), [apiCall, downloadFile])
}
`)

replace('hooks/use-export-tasks.ts',
  'await downloadFile(task.fileName)',
  'await downloadFile(task.fileName, task.downloadUrl)')

replace('app/layout.tsx',
  "import { Analytics } from '@vercel/analytics/next'\n",
  '')
replace('app/layout.tsx',
  '        <Analytics />\n',
  '')

replace('hooks/use-websocket.ts',
  'const websocket = new WebSocket(`${wsProtocol}//${window.location.host}`)',
  'const websocket = new WebSocket(`${wsProtocol}//${window.location.host}/api/v1/qq-workbench/ws`)')
replace('lib/useStreamSearch.ts',
  'const wsUrl = `${protocol}//${window.location.host}`;',
  'const wsUrl = `${protocol}//${window.location.host}/api/v1/qq-workbench/ws`;')

replace('app/page.tsx',
  "peer: { chatType: number, peerUid: string }\n  } | null>(null)",
  "peer: { chatType: number, peerUid: string, peerUin?: string }\n  } | null>(null)")
replace('app/page.tsx',
  'const basePath = tabId === "overview" ? "/qce" : `/qce/${tabId}`',
  'const basePath = tabId === "overview" ? "/qq-workbench" : `/qq-workbench/${tabId}`')
replace('app/page.tsx',
  "peer: { chatType: number, peerUid: string }\n  ) => {",
  "peer: { chatType: number, peerUid: string, peerUin?: string }\n  ) => {")

replace('components/ui/session-list.tsx',
  "onPreviewChat?: (type: 'group' | 'friend', id: string, name: string, peer: { chatType: number, peerUid: string }) => void",
  "onPreviewChat?: (type: 'group' | 'friend', id: string, name: string, peer: { chatType: number, peerUid: string, peerUin?: string }) => void")
replace('components/ui/session-list.tsx',
  "chatType: isGroup ? 2 : 1,\n                  peerUid: item.id,",
  "chatType: isGroup ? 2 : 1,\n                  peerUid: item.id,\n                  peerUin: isGroup ? item.id : friend?.uin,")
replace('components/ui/session-list.tsx',
  `chatType: isGroup ? 2 : 1,
                  peerUid: item.id,
                  peerUin: isGroup ? item.id : friend?.uin,`,
  `chatType: isGroup ? 2 : Number(friend?.chatType || 1),
                  peerUid: item.id,
                  peerUin: isGroup ? item.id : String(friend?.uin || ''),`)
replace('components/ui/session-list.tsx',
  `chatType: isGroup ? 2 : 1,
                    peerUid: item.id,
                    peerUin: isGroup
                      ? item.id
                      : String((item.raw as Friend).uin || ""),`,
  `chatType: isGroup ? 2 : Number((item.raw as Friend).chatType || 1),
                    peerUid: item.id,
                    peerUin: isGroup
                      ? item.id
                      : String((item.raw as Friend).uin || ""),`)

replace('hooks/use-session-filter.ts',
  'const [search, setSearch] = useState("")',
  'const [search, setSearch] = useState("")\n  const [debouncedSearch, setDebouncedSearch] = useState("")')
replace('hooks/use-session-filter.ts',
  '  const filteredItems = useMemo<SessionItem[]>(() => {\n    let items = sortedItems\n\n    // Type filter',
  `  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim().toLowerCase()), 300)
    return () => clearTimeout(timer)
  }, [search])

  const filteredItems = useMemo<SessionItem[]>(() => {
    let items = sortedItems

    // Type filter`)
replace('hooks/use-session-filter.ts',
  `    if (search.trim()) {
      const searchLower = search.toLowerCase().trim()
      items = items.filter(item => item.searchText.includes(searchLower))
    }

    return items
  }, [sortedItems, search, type])`,
  `    if (debouncedSearch) {
      const rank = (item: SessionItem) => {
        const raw = item.raw as Group & Friend
        const ids = [raw.uin, raw.uid, raw.groupCode].filter(Boolean).map(String).map(value => value.toLowerCase())
        const names = [item.name, item.subName].filter(Boolean).map(String).map(value => value.toLowerCase())
        if (ids.includes(debouncedSearch)) return 0
        if ([...ids, ...names].some(value => value.startsWith(debouncedSearch))) return 1
        return 2
      }
      items = items.filter(item => item.searchText.includes(debouncedSearch))
        .sort((left, right) => rank(left) - rank(right))
    }

    return items
  }, [sortedItems, debouncedSearch, type])`)
replace('hooks/use-search.ts',
  "friend.uid.toLowerCase().includes(term)",
  "friend.uid.toLowerCase().includes(term) ||\n      String(friend.uin || '').toLowerCase().includes(term)")
replace('hooks/use-session-filter.ts',
  'g.avatarUrl || `https://p.qlogo.cn/gh/${g.groupCode}/${g.groupCode}/640/`',
  'g.avatarUrl || `/api/v1/qq-workbench/public-media?kind=group-avatar&id=${encodeURIComponent(String(g.groupCode))}`')
replace('hooks/use-session-filter.ts',
  'f.avatarUrl || `https://q1.qlogo.cn/g?b=qq&nk=${f.uin}&s=640`',
  'f.avatarUrl || `/api/v1/qq-workbench/public-media?kind=qq-avatar&id=${encodeURIComponent(String(f.uin))}`')

replace('components/ui/message-preview-modal.tsx',
  "peer: { chatType: number, peerUid: string }",
  "peer: { chatType: number, peerUid: string, peerUin?: string }")
replace('components/ui/message-preview-modal.tsx',
  "  const base = `https://gxh.vip.qq.com/club/item/parcel/item/${prefix}/${emojiId}/raw300`\n  return { primary: `${base}.gif`, fallback: `${base}.png` }",
  "  const base = `/api/v1/qq-workbench/public-media?kind=market-face&id=${encodeURIComponent(emojiId)}&format=`\n  return { primary: `${base}gif`, fallback: `${base}png` }")
replace('components/ui/message-preview-modal.tsx',
  "const qqFaceUrl = faceId ? `https://res.qlogo.cn/qqface/${faceId}/100` : ''",
  "const qqFaceUrl = faceId ? `/api/v1/qq-workbench/public-media?kind=qq-face&id=${encodeURIComponent(String(faceId))}` : ''")
replace('components/ui/message-preview-modal.tsx',
  fs.readFileSync(path.join(patchDir, 'message-media-before.txt'), 'utf8').trimEnd(),
  fs.readFileSync(path.join(patchDir, 'message-media-after.txt'), 'utf8').trimEnd())
replace('components/ui/message-preview-modal.tsx',
  "              <AvatarImage src={chat.type === 'group' \n                ? `https://p.qlogo.cn/gh/${chat.id}/${chat.id}/40`\n                : `https://q1.qlogo.cn/g?b=qq&nk=${chat.id}&s=40`\n              } />",
  "              <AvatarImage src={`/api/v1/qq-workbench/public-media?kind=${chat.type === 'group' ? 'group-avatar' : 'qq-avatar'}&id=${encodeURIComponent(String(chat.peer.peerUin || chat.id))}`} />")
replace('components/ui/message-preview-modal.tsx',
  '<AvatarImage src={`https://q1.qlogo.cn/g?b=qq&nk=${msg.senderUin}&s=40`} />',
  '<AvatarImage src={`/api/v1/qq-workbench/public-media?kind=qq-avatar&id=${encodeURIComponent(String(msg.senderUin))}`} />')
replace('components/ui/message-preview-modal.tsx',
  '<p className="text-sm text-foreground/80 break-words leading-relaxed">\n                          {formatMessageElements(msg.elements)}\n                        </p>',
  '<div className="text-sm text-foreground/80 break-words leading-relaxed">\n                          {formatMessageElements(msg.elements)}\n                        </div>')
replace('components/ui/message-preview-modal.tsx',
  `  const handleExportWithTimeRange = () => {`,
  `  const handleSyncToCloud = () => {
    if (!chat || messages.length === 0) return
    window.parent.postMessage({
      type: 'yingge:qq-sync',
      version: 1,
      payload: {
        chatType: chat.peer.chatType,
        peerUid: chat.peer.peerUid,
        peerQq: chat.peer.peerUin || chat.id,
        sessionName: chat.name,
        rangeStart: currentFilter?.startTime || 0,
        rangeEnd: currentFilter?.endTime || Date.now(),
        messageCount: totalCount,
      },
    }, window.location.origin)
  }

  const handleExportWithTimeRange = () => {`)
replace('components/ui/message-preview-modal.tsx',
  `          <div className="flex items-center gap-2">
            <Button
              variant="outline"`,
  `          <div className="flex items-center gap-2">
            <Button
              size="sm"
              onClick={handleSyncToCloud}
              disabled={loading || messages.length === 0}
              className="rounded-full h-8 text-xs px-4"
            >
              <MessageSquare className="w-3.5 h-3.5 mr-1.5" />
              同步到客户云
            </Button>
            <Button
              variant="outline"`)

replace('app/page.tsx',
  'link.href = `/api/download-file?path=${encodeURIComponent(selectedFile.filePath)}&token=${encodeURIComponent(AuthManager.getInstance().getToken() || "")}`',
  "if (!selectedFile.filePath.startsWith('/api/v1/qq-workbench/media/')) return\n                          link.href = selectedFile.filePath")
replace('app/page.tsx',
  'src={`/api/exports/files/${encodeURIComponent(selectedFile.fileName)}/preview?token=${encodeURIComponent(AuthManager.getInstance().getToken() || "")}`}',
  'src={`/api/v1/qq-workbench/qce/api/exports/files/${encodeURIComponent(selectedFile.fileName)}/preview`}')
replace('app/page.tsx',
  'link.href = `/api/download-file?path=${encodeURIComponent(selectedFile.filePath)}&token=${encodeURIComponent(AuthManager.getInstance().getToken() || "")}`',
  "if (!selectedFile.filePath.startsWith('/api/v1/qq-workbench/media/')) return\n                          link.href = selectedFile.filePath")
replace('hooks/use-chat-history.ts',
  `    const token = AuthManager.getInstance().getToken() || '';
    link.href = \`${'${file.relativePath}'}?token=${'${encodeURIComponent(token)}'}\`;`,
  `    if (!file.relativePath.startsWith('/api/v1/qq-workbench/media/')) return;
    link.href = file.relativePath;`)

const patchedSessionList = read('components/ui/session-list.tsx')
const directPreviewPeer = `chatType: isGroup ? 2 : Number(friend?.chatType || 1),
                  peerUid: item.id,
                  peerUin: isGroup ? item.id : String(friend?.uin || ''),`
if (!patchedSessionList.includes(directPreviewPeer)
    || !patchedSessionList.includes('Number((item.raw as Friend).chatType || 1)')) {
  throw new Error('Special QQ sessions no longer preserve their upstream chatType')
}
const patchedApi = read('hooks/use-api.ts')
const patchedTasks = read('hooks/use-export-tasks.ts')
const patchedLayout = read('app/layout.tsx')
if (!patchedApi.includes("downloadUrl?.startsWith('/api/v1/qq-workbench/media/')")
    || !patchedTasks.includes('downloadFile(task.fileName, task.downloadUrl)')) {
  throw new Error('QCE task downloads must use a Yingge session-bound media ticket')
}
if (patchedLayout.includes('@vercel/analytics') || patchedLayout.includes('<Analytics />')) {
  throw new Error('Embedded QCE must not load external analytics')
}
const patchedAuth = read('lib/auth.ts')
if (patchedAuth.includes('localStorage') || patchedAuth.includes('Authorization')) {
  throw new Error('QCE browser authentication patch must not retain long-lived token handling')
}

const mobileCssMarker = '/* yingge-cloud-mobile-workbench */'
const mobileCssRelative = ['app/globals.css', 'styles/globals.css']
  .find((relative) => fs.existsSync(file(relative)))
if (!mobileCssRelative) throw new Error('Pinned QCE source has no global stylesheet')
let globalCss = read(mobileCssRelative)
if (!globalCss.includes(mobileCssMarker)) {
  globalCss = `${globalCss.trimEnd()}

${mobileCssMarker}
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
  write(mobileCssRelative, globalCss)
}
if (!read(mobileCssRelative).includes(mobileCssMarker)) {
  throw new Error('Embedded QCE mobile workbench styles were not installed')
}

fs.writeFileSync(path.join(root, 'YINGGE-MODIFICATIONS.md'), `# Yingge Cloud modifications\n\nThis pinned GPL-3.0 component is adapted at build time to use Yingge Cloud session/CSRF authentication, an allowlisted server-side gateway, the /qq-workbench path, a session-bound WebSocket relay, ranked 300 ms session search, an explicit CRM sync action, mobile-safe dialogs and form controls, and member-volume-backed QQ auto-login restoration. No QCE or Bridge token is exposed to the browser.\n`)

const qceEntrypoint = path.join(root, 'docker', 'docker-entrypoint-qce.sh')
fs.copyFileSync(path.join(patchDir, 'docker-entrypoint-qce.sh'), qceEntrypoint)
fs.chmodSync(qceEntrypoint, 0o755)
const qceEntrypointSource = fs.readFileSync(qceEntrypoint, 'utf8')
if (!qceEntrypointSource.includes("persisted_account=\"$(jq -r '.autoLoginAccount // empty'")) {
  throw new Error('QCE entrypoint must restore the member-specific auto-login account')
}

const dockerIgnore = path.join(root, '.dockerignore')
let dockerIgnoreSource = fs.readFileSync(dockerIgnore, 'utf8')
if (!dockerIgnoreSource.includes('!YINGGE-MODIFICATIONS.md')) {
  dockerIgnoreSource = `${dockerIgnoreSource.trimEnd()}\n!YINGGE-MODIFICATIONS.md\n`
  fs.writeFileSync(dockerIgnore, dockerIgnoreSource)
}

const dockerfile = path.join(root, 'docker', 'Dockerfile')
let dockerSource = fs.readFileSync(dockerfile, 'utf8')
dockerSource = dockerSource
  .replace('RUN corepack enable && corepack prepare pnpm@9.15.4 --activate\n\n', '')
  .replace('RUN cargo build --release --manifest-path qq-chat-export-server/Cargo.toml', 'ARG QCE_CARGO_REGISTRY_INDEX=sparse+https://index.crates.io/\nRUN printf \'[source.crates-io]\\nreplace-with = "yingge-registry"\\n[source.yingge-registry]\\nregistry = "%s"\\n\' "$QCE_CARGO_REGISTRY_INDEX" > /usr/local/cargo/config.toml \\\n    && CARGO_HTTP_TIMEOUT=600 CARGO_HTTP_LOW_SPEED_LIMIT=1 cargo build --release --manifest-path qq-chat-export-server/Cargo.toml')
  .replace('ARG QCE_CARGO_REGISTRY_INDEX=sparse+https://index.crates.io/', 'ARG QCE_CARGO_REGISTRY_INDEX=sparse+https://rsproxy.cn/index/')
  .replace('COPY qce-v4-tool/package.json qce-v4-tool/pnpm-lock.yaml ./\nRUN pnpm install --frozen-lockfile', 'COPY qce-v4-tool/package.json qce-v4-tool/package-lock.json ./\nRUN npm ci')
  .replace('RUN pnpm run build', 'RUN npm run build')
  .replace('FROM mlikiowa/napcat-docker:latest', 'FROM ${QCE_RUNTIME_BASE_IMAGE}')
  .replace('# Copy frontend static export\nCOPY --from=build /build/qce-v4-tool/out /app/napcat/static/qce',
    '# The NapCat runtime base can already contain an older QCE WebUI. Remove it first so stale token-login chunks cannot survive an overlay COPY.\nRUN rm -rf /app/napcat/static/qce\nCOPY --from=build /build/qce-v4-tool/out /app/napcat/static/qce')
  .replace('# Copy custom entrypoint', 'RUN mkdir -p /usr/share/licenses/qq-chat-exporter\nCOPY LICENSE YINGGE-MODIFICATIONS.md /usr/share/licenses/qq-chat-exporter/\n\n# Copy custom entrypoint')
dockerSource = `ARG QCE_RUNTIME_BASE_IMAGE=mlikiowa/napcat-docker:latest\n${dockerSource}`
if (!dockerSource.includes('RUN npm ci') || dockerSource.includes('RUN pnpm run build')) {
  throw new Error('Pinned upstream Dockerfile dependency patch failed')
}
if (!dockerSource.includes('ARG QCE_CARGO_REGISTRY_INDEX=') || !dockerSource.includes('replace-with = "yingge-registry"') || !dockerSource.includes('CARGO_HTTP_LOW_SPEED_LIMIT=1 cargo build')) {
  throw new Error('Pinned upstream Dockerfile Cargo network tolerance patch failed')
}
if (!dockerSource.includes('/usr/share/licenses/qq-chat-exporter/')) {
  throw new Error('Pinned upstream Dockerfile license retention patch failed')
}
if (!dockerSource.startsWith('ARG QCE_RUNTIME_BASE_IMAGE=')) {
  throw new Error('Pinned upstream Dockerfile runtime base patch failed')
}
if (!dockerSource.includes('RUN rm -rf /app/napcat/static/qce')) {
  throw new Error('Pinned upstream Dockerfile stale WebUI cleanup patch failed')
}
fs.writeFileSync(dockerfile, dockerSource)
console.log('Applied Yingge Cloud QCE WebUI integration patch')
