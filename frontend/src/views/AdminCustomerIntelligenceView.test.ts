import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminCustomerIntelligenceView from './AdminCustomerIntelligenceView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {},
}))

describe('admin customer intelligence runtime status', () => {
  beforeEach(() => apiMock.mockReset())

  it('renders safe CPU, memory, database and queue pressure metrics', async () => {
    apiMock.mockImplementation((path?: string) => {
      if (!path) return Promise.resolve({})
      if (path.endsWith('/settings')) return Promise.resolve({
        enabled: true, autoProfileAnalysis: true, autoTimelineAnalysis: true,
        embeddingBaseUrl: 'http://embedding:8080', embeddingModel: 'bge-m3',
        recentInteractionLimit: 10, semanticChunkLimit: 12, backfillEnabled: true,
        backfillPaused: false, nightlyRatePerMinute: 10, pendingJobs: 7,
        completedCustomers: 90, totalCustomers: 100, version: 3,
      })
      if (path.endsWith('/runtime')) return Promise.resolve({
        sampledAt: '2026-08-19T10:00:00Z', pressureLevel: 'BUSY', pressureLabel: '较忙',
        pressureMessage: '服务仍可用，但资源或分析队列已出现持续占用，请留意趋势。',
        availableProcessors: 4, systemCpuPercent: 68.2, processCpuPercent: 32.1,
        systemMemoryUsedBytes: 4294967296, systemMemoryMaxBytes: 8589934592, systemMemoryPercent: 50,
        jvmHeapUsedBytes: 536870912, jvmHeapMaxBytes: 2147483648, jvmHeapPercent: 25,
        liveThreads: 42, uptimeSeconds: 7200, dbActiveConnections: 4, dbIdleConnections: 2,
        dbTotalConnections: 6, dbAwaitingConnections: 0, dbMaxConnections: 10, dbPressurePercent: 40,
        pendingJobs: 7, runningJobs: 1, failedJobs: 0, oldestPendingSeconds: 22,
      })
      return Promise.reject(new Error('unexpected request'))
    })

    const wrapper = mount(AdminCustomerIntelligenceView)
    await flushPromises()

    const runtime = wrapper.get('[data-testid="intelligence-runtime"]')
    expect(runtime.text()).toContain('服务器性能与分析压力')
    expect(runtime.text()).toContain('较忙')
    expect(runtime.text()).toContain('系统 CPU')
    expect(runtime.text()).toContain('68.2%')
    expect(runtime.text()).toContain('数据库连接')
    expect(runtime.text()).toContain('7 待处理')
    wrapper.unmount()
  })
})
