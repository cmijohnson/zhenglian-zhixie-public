import { defineStore } from 'pinia'
import { api, jsonBody, resetCsrf } from '../lib/api'
import type { Me } from '../types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as Me | null,
    checked: false,
  }),
  getters: {
    isAdmin: (state) => state.user?.roles.includes('ORG_ADMIN') ?? false,
    isTeamManager: (state) => state.user?.roles.includes('TEAM_MANAGER') ?? false,
    canWriteCustomers: (state) =>
      state.user?.roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER' || role === 'MEMBER') ?? false,
    canSubmitDailyReport: (state) =>
      state.user?.roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER' || role === 'MEMBER') ?? false,
    canBulkTransfer: (state) =>
      state.user?.roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER') ?? false,
    canViewTeamData: (state) =>
      state.user?.roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER') ?? false,
    canReviewOperations: (state) =>
      state.user?.roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER') ?? false,
    canPublishAnnouncements: (state) =>
      state.user?.roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER') ?? false,
  },
  actions: {
    adopt(user: Me) {
      this.user = user
      this.checked = true
    },
    async ensure() {
      if (this.checked) return this.user
      try {
        this.user = await api<Me>('/api/v1/auth/me')
      } catch {
        this.user = null
      } finally {
        this.checked = true
      }
      return this.user
    },
    async login(username: string, password: string) {
      this.adopt(await api<Me>('/api/v1/auth/login', {
        method: 'POST',
        body: jsonBody({ username, password }),
      }))
    },
    async logout() {
      await api<void>('/api/v1/auth/logout', { method: 'POST' })
      this.user = null
      this.checked = true
      resetCsrf()
    },
  },
})
