import { Routes, Route, Navigate } from 'react-router-dom'
import { AppShell } from '@/components/layout/AppShell'
import { EnrollmentRoute, ProtectedRoute } from '@/components/layout/ProtectedRoute'
import { LoginPage } from '@/pages/LoginPage'
import { RegisterPage } from '@/pages/RegisterPage'
import { MfaPage } from '@/pages/MfaPage'
import { RecoverPage } from '@/pages/RecoverPage'
import { SmartSignInApprovePage } from '@/pages/SmartSignInApprovePage'
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
import { McpServerPage } from '@/pages/connectors/McpServers'
import { FunctionsPage } from '@/pages/FunctionsPage'
import { FormsPage } from '@/pages/FormsPage'
import { FormBuilderPage } from '@/pages/FormBuilderPage'
import { PublicFormPage } from '@/pages/PublicFormPage'
import { RecordingsPage } from '@/pages/RecordingsPage'
import { HelpPage } from '@/pages/HelpPage'
import { WorkflowsPage } from '@/pages/WorkflowsPage'
import { ProceduresPage } from '@/pages/ProceduresPage'
import { GuardrailsPage } from '@/pages/GuardrailsPage'
import { OrganizationGuardrailsPage } from '@/pages/OrganizationGuardrailsPage'
import { ActivityPage } from '@/pages/ActivityPage'
import { UsagePage } from '@/pages/UsagePage'
import { RunHistoryPage } from '@/pages/RunHistoryPage'
import { SecurityPage } from '@/pages/SecurityPage'
import { AiModelsPage } from '@/pages/AiModelsPage'
import { DevicesPage } from '@/pages/DevicesPage'
import { SetupTwoStepPage } from '@/pages/SetupTwoStepPage'
import { MembersPage } from '@/pages/MembersPage'
import { InvitationsPage } from '@/pages/InvitationsPage'
import { PublicListingPage } from '@/pages/PublicListingPage'
import { ListingReviewsPage } from '@/pages/ListingReviewsPage'
import { RegisteredAccountsPage } from '@/pages/RegisteredAccountsPage'
import { DiscoverPage, DiscoverProfilePage } from '@/pages/DiscoverPage'
import { RequirePermission } from '@/auth/RequirePermission'
import { PersonalOnly } from '@/auth/PersonalOnly'
import { PERMISSIONS } from '@/lib/permissions'
import { HubLayout } from '@/components/layout/HubLayout'
import { SettingsLayout } from '@/pages/settings/SettingsLayout'
import { GeneralSettingsPage } from '@/pages/settings/GeneralSettingsPage'

export default function App() {
  return (
    <Routes>
      {/* Public */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/mfa" element={<MfaPage />} />
      <Route path="/recover" element={<RecoverPage />} />
      {/* Opened by a phone that scanned the login page's QR; it checks the sign-in itself. */}
      <Route path="/smart-sign-in" element={<SmartSignInApprovePage />} />
      {/* A published form's link: anyone can fill it in, signed in or not. */}
      <Route path="/f/:token" element={<PublicFormPage />} />

      <Route element={<EnrollmentRoute />}>
        <Route path="/setup-2fa" element={<SetupTwoStepPage />} />
      </Route>

      {/* Protected */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          {/* The Individual account's own modules; an Organization account goes to its home. Similar
              pages share one menu entry (HubLayout shows their tabs); each keeps its own address. */}
          <Route element={<PersonalOnly />}>
            <Route path="/chat" element={<ChatPage />} />
            {/* Conversations live in the chat workspace's left column now. */}
            <Route path="/conversations" element={<Navigate to="/chat" replace />} />
            <Route element={<HubLayout hub="agents" />}>
              <Route path="/agents" element={<AgentsPage />} />
              <Route path="/memory" element={<CosmosView />} />
              <Route path="/memory/:agentId" element={<CosmosView />} />
            </Route>
            <Route path="/agents/new" element={<NewAgentPage />} />
            <Route path="/agents/:agentId" element={<AgentDetailPage />} />
            <Route path="/tasks" element={<TasksPage />} />
            <Route path="/build" element={<Navigate to="/workflows" replace />} />
            <Route element={<HubLayout hub="build" />}>
              <Route path="/workflows" element={<WorkflowsPage />} />
              <Route path="/recordings" element={<RecordingsPage />} />
              <Route path="/forms" element={<FormsPage />} />
              <Route path="/functions/:functionId?" element={<FunctionsPage />} />
              <Route path="/procedures" element={<ProceduresPage />} />
            </Route>
            <Route path="/forms/:formId" element={<FormBuilderPage />} />
            <Route path="/autonomy" element={<Navigate to="/guardrails" replace />} />
            <Route element={<HubLayout hub="activity" />}>
              <Route path="/activity" element={<ActivityPage />} />
              <Route path="/runs" element={<RunHistoryPage />} />
            </Route>
          </Route>

          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/help/:slug?" element={<HelpPage />} />
          <Route path="/invitations" element={<InvitationsPage />} />
          <Route path="/discover" element={<DiscoverPage />} />
          <Route path="/discover/:orgId" element={<DiscoverProfilePage />} />
          <Route element={<HubLayout hub="platform" />}>
            <Route path="/admin/listings" element={<ListingReviewsPage />} />
            <Route path="/admin/accounts" element={<RegisteredAccountsPage />} />
          </Route>
          <Route element={<HubLayout hub="organization" />}>
            <Route element={<RequirePermission permission={PERMISSIONS.LISTING_MANAGE} />}>
              <Route path="/organization/listing" element={<PublicListingPage />} />
            </Route>
            <Route element={<RequirePermission permission={PERMISSIONS.MEMBERS_VIEW} />}>
              <Route path="/members" element={<MembersPage />} />
            </Route>
            <Route element={<RequirePermission permission={PERMISSIONS.AGENT_TEMPLATE_MANAGE} />}>
              <Route path="/organization/agent-templates" element={<OrganizationAgentTemplatesPage />} />
            </Route>
          </Route>
          <Route element={<RequirePermission permission={PERMISSIONS.CONNECTOR_VIEW} />}>
            <Route path="/connectors/mcp/:mcpId" element={<McpServerPage />} />
            <Route path="/connectors/:serviceId/:tab?" element={<ServiceEditorPage />} />
          </Route>
          <Route path="/connections" element={<Navigate to="/connectors" replace />} />

          {/* Settings, opened from the account menu at the bottom of the left menu. */}
          <Route element={<SettingsLayout />}>
            <Route path="/settings/general" element={<GeneralSettingsPage />} />
            <Route path="/settings/security" element={<SecurityPage />} />
            <Route path="/settings/ai-models" element={<AiModelsPage />} />
            <Route element={<RequirePermission permission={PERMISSIONS.DEVICE_VIEW} />}>
              <Route path="/settings/devices" element={<DevicesPage />} />
            </Route>
            <Route element={<RequirePermission permission={PERMISSIONS.CONNECTOR_VIEW} />}>
              <Route path="/connectors" element={<ConnectorsPage />} />
            </Route>
            <Route element={<PersonalOnly />}>
              <Route path="/guardrails" element={<GuardrailsPage />} />
              <Route path="/usage" element={<UsagePage />} />
            </Route>
            <Route element={<RequirePermission permission={PERMISSIONS.GUARDRAILS_VIEW} />}>
              <Route path="/organization/guardrails" element={<OrganizationGuardrailsPage />} />
            </Route>
          </Route>
          <Route path="/settings" element={<Navigate to="/settings/general" replace />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  )
}
