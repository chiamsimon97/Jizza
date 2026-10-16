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
import { TasksPage } from '@/pages/TasksPage'
import { ConnectorsPage } from '@/pages/connectors/ConnectorsPage'
import { ServiceEditorPage } from '@/pages/connectors/ServiceEditorPage'
import { WorkflowsPage } from '@/pages/WorkflowsPage'
import { ProceduresPage } from '@/pages/ProceduresPage'
import { AutonomyPage } from '@/pages/AutonomyPage'
import { ActivityPage } from '@/pages/ActivityPage'
import { UsagePage } from '@/pages/UsagePage'
import { SecurityPage } from '@/pages/SecurityPage'
import { SetupTwoStepPage } from '@/pages/SetupTwoStepPage'

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
          <Route path="/chat" element={<ChatPage />} />
          {/* Conversations live in the chat workspace's left column now. */}
          <Route path="/conversations" element={<Navigate to="/chat" replace />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/memory" element={<CosmosView />} />
          <Route path="/memory/:agentId" element={<CosmosView />} />
          <Route path="/tasks" element={<TasksPage />} />
          <Route path="/connectors" element={<ConnectorsPage />} />
          <Route path="/connectors/:serviceId/:tab?" element={<ServiceEditorPage />} />
          <Route path="/connections" element={<Navigate to="/connectors" replace />} />
          <Route path="/workflows" element={<WorkflowsPage />} />
          <Route path="/procedures" element={<ProceduresPage />} />
          <Route path="/autonomy" element={<AutonomyPage />} />
          <Route path="/activity" element={<ActivityPage />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/settings/security" element={<SecurityPage />} />
          <Route path="/settings" element={<Navigate to="/settings/security" replace />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  )
}
