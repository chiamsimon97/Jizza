// ── Auth ──────────────────────────────────────────────────────────────────────
export interface LoginResult {
  mfaRequired: boolean
  mfaToken?: string
  sessionId?: string
  userId?: string
}

export interface MfaSetup {
  secret: string
  provisioningUri: string
}

export interface SessionDto {
  id: string
  device?: string
  issuedAt: string
  lastSeenAt: string
  expiresAt: string
  current: boolean
}

// ── Agents ────────────────────────────────────────────────────────────────────
export interface AgentDto {
  id: string
  key: string
  name: string
  domain?: string
  enabled: boolean
  isOrchestrator: boolean
}

// ── Chat ──────────────────────────────────────────────────────────────────────
export interface AssistantReply {
  conversationId?: string
  messageId?: string
  text: string
  contributors: Contribution[]
  actions?: UiAction[]
}

/** A step Jizza asks the app to take alongside its reply. */
export type UiAction =
  | { type: 'navigate'; payload: { page: string; reason?: string } }
  | { type: 'open_form'; payload: { form: JizzaForm; fields?: Record<string, unknown> } }
  | { type: 'ask_user'; payload: { question: string; options?: string[] } }
  | { type: 'set_theme'; payload: { theme: string } }

export type JizzaForm = 'new_workflow' | 'new_task'

export interface Contribution {
  agentId: string
  agentName: string
  summary: string
}

// ── Conversations ─────────────────────────────────────────────────────────────
export interface ConversationDto {
  id: string
  createdAt: string
  messages: MessageDto[]
}

export interface MessageDto {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

// ── Notifications ─────────────────────────────────────────────────────────────
export type NotificationKind =
  | 'INFO'
  | 'PERMISSION_REQUEST'
  | 'WORKFLOW_REVIEW'
  | 'INSTRUCTION_REQUIRED'

export type NotificationStatus = 'PENDING' | 'READ' | 'ANSWERED' | 'EXPIRED'

export interface NotificationDto {
  id: string
  kind: NotificationKind
  sourceAgentId?: string
  context?: Record<string, unknown>
  status: NotificationStatus
  expiresAt?: string
  escalationState?: string
  createdAt: string
}

// ── Tasks ─────────────────────────────────────────────────────────────────────
export interface TaskDto {
  id: string
  title: string
  agentId: string
  scheduleType: string
  cronOrRrule?: string
  runAt?: string
  nextRunAt?: string
  status: string
  repetitive: boolean
  boundWorkflowId?: string
  timezone?: string
}

// ── Connections ───────────────────────────────────────────────────────────────
export interface ConnectionDto {
  id: string
  serviceName: string
  authScheme: string
  status: string
  baseUrl?: string
  createdAt: string
}

export interface GeneratedToolDto {
  id: string
  connectionId: string
  name: string
  description?: string
  enabled: boolean
  method?: string
  path?: string
}

// ── Workflows ─────────────────────────────────────────────────────────────────
export interface WorkflowDto {
  id: string
  name: string
  description?: string
  graph: unknown
  origin: string
  status: string
  version: number
  owningAgentId?: string
  createdAt: string
}

export interface RunDto {
  id: string
  workflowId: string
  workflowVersion: number
  status: string
  currentNode?: string
  startedAt: string
  updatedAt: string
}

export interface SkillDto {
  id: string
  workflowId: string
  name: string
  description?: string
}

// ── Procedures ────────────────────────────────────────────────────────────────
export interface ProcedureHistoryEntry {
  version: number
  status: string
  createdAt: string
}

// ── Autonomy ──────────────────────────────────────────────────────────────────
export interface AutonomyRuleDto {
  id?: string
  agentId?: string
  toolRef?: string
  rule: 'ALLOW' | 'ASK' | 'DENY'
  limits?: Record<string, unknown>
  precedence: number
}

// ── Activity / Audit ──────────────────────────────────────────────────────────
export interface AuditEntryDto {
  id: string
  actorType: string
  actorId?: string
  actorName?: string
  action: string
  target?: string
  detail?: unknown
  requestId?: string
  hash: string
  createdAt: string
}

export interface ChainVerification {
  intact: boolean
  firstBrokenEntryId?: string
  entries: number
}

// ── Usage ─────────────────────────────────────────────────────────────────────
export interface UsageEntryDto {
  id: string
  model: string
  purpose?: string
  inputTokens: number
  outputTokens: number
  costUnits: number
  createdAt: string
}

export interface ModelBreakdown {
  model: string
  inputTokens: number
  outputTokens: number
  costUnits: number
}

export interface UsageSummary {
  windowStart: string
  windowEnd: string
  totalInputTokens: number
  totalOutputTokens: number
  totalCostUnits: number
  withinBudget: boolean
  budgetLimit?: number
  breakdown: ModelBreakdown[]
}

// ── Problem ───────────────────────────────────────────────────────────────────
export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail?: string
}
