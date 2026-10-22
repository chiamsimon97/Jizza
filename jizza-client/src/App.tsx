import { Routes, Route, Navigate } from 'react-router-dom'
import { AppShell } from '@/components/layout/AppShell'
import { EnrollmentRoute, ProtectedRoute } from '@/components/layout/ProtectedRoute'
import { LoginPage } from '@/pages/LoginPage'
import { RegisterPage } from '@/pages/RegisterPage'
import { MfaPage } from '@/pages/MfaPage'
import { RecoverPage } from '@/pages/RecoverPage'
import { ChatPage } from '@/pages/ChatPage'
import { NotificationsPage } from '@/pages/NotificationsPage'
import { CosmosView } from '@/pages/memory/CosmosView'
import { AgentsPage } from '@/pages/AgentsPage'
import { NewAgentPage } from '@/pages/agents/NewAgentPage'
import { AgentDetailPage } from '@/pages/agents/AgentDetailPage'
import { OrganizationAgentTemplatesPage } from '@/pages/OrganizationAgentTemplatesPage'
import { TasksPage } from '@/pages/TasksPage'
import { ConnectorsPage } from '@/pages/connectors/ConnectorsPage'
import { ServiceEditorPage } from '@/pages/connectors/ServiceEditorPage'
import { WorkflowsPage } from '@/pages/WorkflowsPage'
import { ProceduresPage } from '@/pages/ProceduresPage'
import { GuardrailsPage } from '@/pages/GuardrailsPage'
import { OrganizationGuardrailsPage } from '@/pages/OrganizationGuardrailsPage'
import { ActivityPage } from '@/pages/ActivityPage'
import { UsagePage } from '@/pages/UsagePage'
import { RunHistoryPage } from '@/pages/RunHistoryPage'
import { SecurityPage } from '@/pages/SecurityPage'
import { SetupTwoStepPage } from '@/pages/SetupTwoStepPage'
import { MembersPage } from '@/pages/MembersPage'
import { InvitationsPage } from '@/pages/InvitationsPage'
import { RequirePermission } from '@/auth/RequirePermission'
import { PersonalOnly } from '@/auth/PersonalOnly'
import { PERMISSIONS } from '@/lib/permissions'

export default function App() {
  return (
    <Routes>
      {/* Public */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/mfa" element={<MfaPage />} />
      <Route path="/recover" element={<RecoverPage />} />

      <Route element={<EnrollmentRoute />}>
        <Route path="/setup-2fa" element={<SetupTwoStepPage />} />
      </Route>

      {/* Protected */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          {/* The Individual account's own modules; an Organization account goes to its home. */}
          <Route element={<PersonalOnly />}>
            <Route path="/chat" element={<ChatPage />} />
            {/* Conversations live in the chat workspace's left column now. */}
            <Route path="/conversations" element={<Navigate to="/chat" replace />} />
            <Route path="/agents" element={<AgentsPage />} />
            <Route path="/agents/new" element={<NewAgentPage />} />
            <Route path="/agents/:agentId" element={<AgentDetailPage />} />
            <Route path="/memory" element={<CosmosView />} />
            <Route path="/memory/:agentId" element={<CosmosView />} />
            <Route path="/tasks" element={<TasksPage />} />
            <Route path="/workflows" element={<WorkflowsPage />} />
            <Route path="/procedures" element={<ProceduresPage />} />
            <Route path="/guardrails" element={<GuardrailsPage />} />
            <Route path="/autonomy" element={<Navigate to="/guardrails" replace />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route path="/usage" element={<UsagePage />} />
            <Route path="/runs" element={<RunHistoryPage />} />
          </Route>

          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/invitations" element={<InvitationsPage />} />
          <Route element={<RequirePermission permission={PERMISSIONS.MEMBERS_VIEW} />}>
            <Route path="/members" element={<MembersPage />} />
          </Route>
          <Route element={<RequirePermission permission={PERMISSIONS.GUARDRAILS_VIEW} />}>
            <Route path="/organization/guardrails" element={<OrganizationGuardrailsPage />} />
          </Route>
          <Route element={<RequirePermission permission={PERMISSIONS.AGENT_TEMPLATE_MANAGE} />}>
            <Route path="/organization/agent-templates" element={<OrganizationAgentTemplatesPage />} />
          </Route>
          <Route element={<RequirePermission permission={PERMISSIONS.CONNECTOR_VIEW} />}>
            <Route path="/connectors" element={<ConnectorsPage />} />
            <Route path="/connectors/:serviceId/:tab?" element={<ServiceEditorPage />} />
          </Route>
          <Route path="/connections" element={<Navigate to="/connectors" replace />} />
          <Route path="/settings/security" element={<SecurityPage />} />
          <Route path="/settings" element={<Navigate to="/settings/security" replace />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  )
}
