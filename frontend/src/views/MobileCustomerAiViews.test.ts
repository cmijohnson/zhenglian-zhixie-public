import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import AssistantView from './AssistantView.vue'
import CustomerPoolView from './CustomerPoolView.vue'

vi.mock('../lib/api', async (loadOriginal) => {
  const original = await loadOriginal<typeof import('../lib/api')>()
  return { ...original, api: vi.fn() }
})

const apiMock = vi.mocked(api)

beforeEach(() => apiMock.mockReset())

describe('customer and AI mobile disclosures', () => {
  it('renders assistant output as reviewable fields and keeps raw JSON behind a disclosure', async () => {
    apiMock.mockImplementation(async (path, init) => {
      if (path === '/api/v1/assistant/availability') return { available: true, provider: 'mock', model: 'test' } as never
      if (path === '/api/v1/assistant/customer-draft' && init?.method === 'POST') return {
        fullName: '张三', demandSummary: '很长的客户需求'.repeat(30), focusDirections: ['升学', '转专业'],
      } as never
      return {} as never
    })
    const wrapper = mount(AssistantView)
    await flushPromises()
    await wrapper.get('textarea').setValue('张三希望了解转专业方案')
    await wrapper.get('.assistant-card>.primary-button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.result-grid article')).toHaveLength(3)
    expect(wrapper.get('.raw-json').attributes('open')).toBeUndefined()
    const longField = wrapper.findAll('.result-grid article').find((item) => item.text().includes('demandSummary'))!
    expect(longField.get('.content-toggle').attributes('aria-expanded')).toBe('false')
    await longField.get('.content-toggle').trigger('click')
    expect(longField.get('pre').classes()).toContain('expanded')
  })

  it('shows only the first two masked timeline entries until the user expands a pool card', async () => {
    apiMock.mockImplementation(async (path) => {
      if (String(path).startsWith('/api/v1/customer-pools')) return [{
        id: 'pool-1', customerId: 'customer-1', poolType: 'C', releaseReason: '重新激活',
        customerName: '客户甲', school: '示例大学', grade: '2026', source: 'QQ',
        demandSummary: '希望进一步了解课程安排'.repeat(10), availableAt: '2026-08-21T01:00:00Z',
        canClaim: true, status: 'OPEN', timeline: [
          { occurredAt: '2026-08-18T01:00:00Z', channel: 'QQ', description: '第一次沟通' },
          { occurredAt: '2026-08-19T01:00:00Z', channel: '微信', description: '第二次沟通' },
          { occurredAt: '2026-08-20T01:00:00Z', channel: '电话', description: '第三次沟通' },
        ],
      }] as never
      if (path === '/api/v1/second-line-recruitments') return [] as never
      return [] as never
    })
    const wrapper = mount(CustomerPoolView)
    await flushPromises()

    expect(wrapper.findAll('.masked-timeline>span')).toHaveLength(2)
    const toggle = wrapper.get('.masked-timeline .content-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    await toggle.trigger('click')
    expect(wrapper.findAll('.masked-timeline>span')).toHaveLength(3)
    expect(toggle.attributes('aria-expanded')).toBe('true')
  })
})
