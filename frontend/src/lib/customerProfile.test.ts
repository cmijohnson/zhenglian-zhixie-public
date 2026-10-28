import { describe, expect, it } from 'vitest'
import { formatCustomerGrade, isGradeTag, normalizeCustomerGrade } from './customerProfile'

describe('customer profile vocabulary', () => {
  it('normalizes known cohort formats without guessing semantic legacy values', () => {
    expect(normalizeCustomerGrade('2026级')).toBe('2026')
    expect(normalizeCustomerGrade('26级')).toBe('2026')
    expect(normalizeCustomerGrade('大二')).toBe('大二')
    expect(formatCustomerGrade('2026')).toBe('2026级')
    expect(formatCustomerGrade('大二')).toBe('大二')
  })

  it('recognizes grade tags by reserved category or year-like name', () => {
    expect(isGradeTag({ name: '重点客户', category: '业务' })).toBe(false)
    expect(isGradeTag({ name: '毕业年级', category: '年级' })).toBe(true)
    expect(isGradeTag({ name: '2026', category: '业务' })).toBe(true)
    expect(isGradeTag({ name: '25级', category: '业务' })).toBe(true)
  })
})
