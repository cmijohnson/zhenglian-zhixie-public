import type { LocationQueryValue, Router } from 'vue-router'

const RESUME_KEY = 'yingge:team-data-resume'
const RESUME_TTL_MS = 30 * 60 * 1000

interface TeamDataResumeState {
  path: string
  scrollY: number
  expiresAt: number
  savedMessage?: string
}

function queryValue(value: LocationQueryValue | LocationQueryValue[]): string | undefined {
  return typeof value === 'string' ? value : undefined
}

export function safeTeamDataReturnTo(
  value: LocationQueryValue | LocationQueryValue[],
): string | undefined {
  const raw = queryValue(value)
  if (!raw || raw.length > 2_048 || raw.includes('#')) return undefined
  try {
    const parsed = new URL(raw, 'https://yingge.internal')
    if (parsed.origin !== 'https://yingge.internal' || parsed.pathname !== '/team-data') return undefined
    return `${parsed.pathname}${parsed.search}`
  } catch {
    return undefined
  }
}

export function isCustomerDetailHistoryEntry(value: unknown, customerId: string) {
  if (typeof value !== 'string') return false
  try {
    const parsed = new URL(value, 'https://yingge.internal')
    return parsed.origin === 'https://yingge.internal'
      && parsed.pathname === `/customers/${encodeURIComponent(customerId)}`
  } catch {
    return false
  }
}

function readResumeState(): TeamDataResumeState | undefined {
  try {
    const parsed = JSON.parse(window.sessionStorage.getItem(RESUME_KEY) || '') as TeamDataResumeState
    if (!parsed.path || parsed.expiresAt < Date.now()) {
      window.sessionStorage.removeItem(RESUME_KEY)
      return undefined
    }
    return parsed
  } catch {
    return undefined
  }
}

function writeResumeState(state: TeamDataResumeState) {
  try {
    window.sessionStorage.setItem(RESUME_KEY, JSON.stringify(state))
  } catch {
    // Navigation remains functional when storage is unavailable.
  }
}

export function rememberTeamDataPosition(path: string, scrollY = window.scrollY) {
  writeResumeState({
    path,
    scrollY: Math.max(0, Math.round(scrollY)),
    expiresAt: Date.now() + RESUME_TTL_MS,
  })
}

export function markTeamDataCustomerSaved(path: string, customerName: string) {
  const current = readResumeState()
  writeResumeState({
    path,
    scrollY: current?.path === path ? current.scrollY : 0,
    expiresAt: Date.now() + RESUME_TTL_MS,
    savedMessage: `${customerName}的客户档案已保存`,
  })
}

export function consumeTeamDataResume(path: string) {
  const current = readResumeState()
  if (!current || current.path !== path) return undefined
  if (current.savedMessage) {
    writeResumeState({ ...current, savedMessage: undefined })
  }
  return { scrollY: current.scrollY, savedMessage: current.savedMessage }
}

export function returnFromTeamDataEdit(router: Router, customerId: string, returnTo: string) {
  const historyState = router.options.history.state
  const canPopDetailAndEdit = isCustomerDetailHistoryEntry(historyState.back, customerId)
    && typeof historyState.position === 'number'
    && historyState.position >= 2
  if (canPopDetailAndEdit) {
    router.go(-2)
    return
  }
  void router.replace(returnTo)
}
