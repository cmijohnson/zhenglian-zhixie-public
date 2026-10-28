import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import AppLayout from './components/AppLayout.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import DashboardView from './views/DashboardView.vue'
import CustomersView from './views/CustomersView.vue'
import CustomerFormView from './views/CustomerFormView.vue'
import CustomerDetailView from './views/CustomerDetailView.vue'
import RemindersView from './views/RemindersView.vue'
import ImportsView from './views/ImportsView.vue'
import ExportsView from './views/ExportsView.vue'
import AssistantView from './views/AssistantView.vue'
import AdminMembersView from './views/AdminMembersView.vue'
import AdminTeamsView from './views/AdminTeamsView.vue'
import AdminDictionariesView from './views/AdminDictionariesView.vue'
import AdminAuditView from './views/AdminAuditView.vue'
import SettingsView from './views/SettingsView.vue'
import ContentTasksView from './views/ContentTasksView.vue'
import CircleView from './views/CircleView.vue'
import DailyReportView from './views/DailyReportView.vue'
import DailySummaryView from './views/DailySummaryView.vue'
import TaskCenterView from './views/TaskCenterView.vue'
import TeamDataView from './views/TeamDataView.vue'
import AnnouncementsView from './views/AnnouncementsView.vue'
import AdminAssistantSettingsView from './views/AdminAssistantSettingsView.vue'
import AdminLeadershipScreenSettingsView from './views/AdminLeadershipScreenSettingsView.vue'
import MyAgentView from './views/MyAgentView.vue'
import AdminAgentsView from './views/AdminAgentsView.vue'
import OAuthConsentView from './views/OAuthConsentView.vue'
import AgentApprovalView from './views/AgentApprovalView.vue'
import LeadershipDashboardView from './views/LeadershipDashboardView.vue'
import PerformanceView from './views/PerformanceView.vue'
import AdminPerformanceView from './views/AdminPerformanceView.vue'
import QqChatView from './views/QqChatView.vue'
import ConsultationsView from './views/ConsultationsView.vue'
import AdminFollowUpSettingsView from './views/AdminFollowUpSettingsView.vue'
import AdminCustomerEnrichmentView from './views/AdminCustomerEnrichmentView.vue'
import AdminCustomerIntelligenceView from './views/AdminCustomerIntelligenceView.vue'
import NotificationsView from './views/NotificationsView.vue'
import AdminGovernanceView from './views/AdminGovernanceView.vue'
import CustomerPoolView from './views/CustomerPoolView.vue'
import ReleasesView from './views/ReleasesView.vue'
import MarketInsightsView from './views/MarketInsightsView.vue'
import AdminFeishuIntegrationView from './views/AdminFeishuIntegrationView.vue'
import FeishuWorkbenchView from './views/FeishuWorkbenchView.vue'
import FeishuHomeView from './views/FeishuHomeView.vue'
import FeishuCrmEntryView from './views/FeishuCrmEntryView.vue'
import FeishuDealsView from './views/FeishuDealsView.vue'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { public: true } },
    { path: '/feishu', name: 'feishu-home', component: FeishuHomeView, meta: { feishuEntry: true } },
    { path: '/feishu/crm', name: 'feishu-crm', component: FeishuCrmEntryView, meta: { feishuEntry: true } },
    { path: '/feishu/deals', name: 'feishu-deals', component: FeishuDealsView, meta: { feishuEntry: true, writable: true } },
    { path: '/leadership-screen', name: 'leadership-screen', component: LeadershipDashboardView, meta: { teamData: true } },
    { path: '/daily-summary', name: 'daily-summary', component: DailySummaryView, meta: { dailyReportContributor: true } },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: DashboardView },
        { path: 'customers', component: CustomersView },
        { path: 'customers/new', component: CustomerFormView },
        { path: 'customers/:id/edit', component: CustomerFormView },
        { path: 'customers/:id', component: CustomerDetailView },
        { path: 'reminders', component: RemindersView },
        { path: 'customer-pool', component: CustomerPoolView, meta: { writable: true } },
        { path: 'notifications', component: NotificationsView },
        { path: 'releases', component: ReleasesView },
        { path: 'tasks', component: TaskCenterView },
        { path: 'content-tasks', component: ContentTasksView },
        { path: 'circle', component: CircleView },
        { path: 'announcements', component: AnnouncementsView },
        { path: 'team-data', component: TeamDataView, meta: { teamData: true } },
        { path: 'market-insights', component: MarketInsightsView, meta: { teamData: true } },
        { path: 'leadership-dashboard', component: LeadershipDashboardView, meta: { teamData: true } },
        { path: 'daily-report', component: DailyReportView },
        { path: 'performance', component: PerformanceView },
        { path: 'consultations', component: ConsultationsView },
        { path: 'qq-chat', component: QqChatView, meta: { writable: true } },
        { path: 'imports', component: ImportsView, meta: { bulk: true } },
        { path: 'exports', component: ExportsView, meta: { bulk: true } },
        { path: 'assistant', component: AssistantView },
        { path: 'agent', component: MyAgentView },
        { path: 'feishu-workbench', component: FeishuWorkbenchView },
        { path: 'agent/approvals/:id', component: AgentApprovalView },
        { path: 'oauth/authorize', component: OAuthConsentView },
        { path: 'admin/members', component: AdminMembersView, meta: { admin: true } },
        { path: 'admin/teams', component: AdminTeamsView, meta: { admin: true } },
        { path: 'admin/dictionaries', component: AdminDictionariesView, meta: { admin: true } },
        { path: 'admin/audit', component: AdminAuditView, meta: { admin: true } },
        { path: 'admin/governance', component: AdminGovernanceView, meta: { admin: true } },
        { path: 'admin/assistant', component: AdminAssistantSettingsView, meta: { admin: true } },
        { path: 'admin/leadership-screen', component: AdminLeadershipScreenSettingsView, meta: { admin: true } },
        { path: 'admin/agents', component: AdminAgentsView, meta: { admin: true } },
        { path: 'admin/performance', component: AdminPerformanceView, meta: { admin: true } },
        { path: 'admin/follow-up', component: AdminFollowUpSettingsView, meta: { admin: true } },
        { path: 'admin/customer-enrichment', component: AdminCustomerEnrichmentView, meta: { admin: true } },
        { path: 'admin/customer-intelligence', component: AdminCustomerIntelligenceView, meta: { admin: true } },
        { path: 'admin/feishu', component: AdminFeishuIntegrationView, meta: { admin: true } },
        { path: 'settings', component: SettingsView },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const user = await auth.ensure()
  if (to.meta.public) return user ? '/dashboard' : true
  if (!user && to.meta.feishuEntry) {
    const returnTo = to.path === '/feishu/crm' && typeof to.query.returnTo === 'string'
      ? to.query.returnTo : to.fullPath
    const query = new URLSearchParams({ purpose: 'LOGIN', returnTo })
    window.location.assign(`/api/v1/auth/feishu/start?${query.toString()}`)
    return false
  }
  if (!user) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return '/dashboard'
  if (to.meta.bulk && !auth.canBulkTransfer) return '/dashboard'
  if (to.meta.teamData && !auth.canViewTeamData) return '/dashboard'
  if (to.meta.writable && !auth.canWriteCustomers) return '/dashboard'
  if (to.meta.dailyReportContributor && !auth.canSubmitDailyReport) return '/dashboard'
  return true
})

export default router
