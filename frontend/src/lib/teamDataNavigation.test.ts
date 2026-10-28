import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  consumeTeamDataResume,
  isCustomerDetailHistoryEntry,
  markTeamDataCustomerSaved,
  rememberTeamDataPosition,
  safeTeamDataReturnTo,
} from './teamDataNavigation'

describe('team data return navigation', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    vi.useRealTimers()
  })

  it('accepts only internal team-data return paths', () => {
    expect(safeTeamDataReturnTo('/team-data?grade=2026&view=cards')).toBe('/team-data?grade=2026&view=cards')
    expect(safeTeamDataReturnTo('https://example.com/team-data')).toBeUndefined()
    expect(safeTeamDataReturnTo('/customers')).toBeUndefined()
    expect(safeTeamDataReturnTo('/team-data#hidden')).toBeUndefined()
    expect(safeTeamDataReturnTo(['/team-data'])).toBeUndefined()
  })

  it('recognizes the expected customer detail history entry', () => {
    expect(isCustomerDetailHistoryEntry('/customers/customer-1?returnTo=%2Fteam-data', 'customer-1')).toBe(true)
    expect(isCustomerDetailHistoryEntry('/customers/customer-2', 'customer-1')).toBe(false)
    expect(isCustomerDetailHistoryEntry('https://example.com/customers/customer-1', 'customer-1')).toBe(false)
  })

  it('restores scroll position and consumes the saved notice once', () => {
    const path = '/team-data?grade=2026&tab=customers&view=table'
    rememberTeamDataPosition(path, 640)
    markTeamDataCustomerSaved(path, '张同学')

    expect(consumeTeamDataResume(path)).toEqual({
      scrollY: 640,
      savedMessage: '张同学的客户档案已保存',
    })
    expect(consumeTeamDataResume(path)).toEqual({ scrollY: 640, savedMessage: undefined })
    expect(consumeTeamDataResume('/team-data?grade=2025')).toBeUndefined()
  })
})
