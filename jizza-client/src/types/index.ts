// ── Auth ──────────────────────────────────────────────────────────────────────
export interface LoginResult {
  mfaRequired: boolean
  mfaToken?: string
  userId?: string
  /** Signed in, but two-step sign-in has to be set up before anything else works. */
  enrollmentRequired?: boolean
}

export interface SecurityStatus {
  userId: string
  email: string
  mfaEnabled: boolean
  mfaEnabledAt?: string
  /** This Jizza requires two-step sign-in for every account. */
  mfaRequired: boolean
  /** This session can only reach the two-step setup until it is finished. */
  enrollmentRequired: boolean
  recoveryCodesRemaining: number
}

export interface MfaSetup {
  secret: string
  provisioningUri: string
}

export interface SessionDto {
  id: string
  device?: string
  ip?: string
  issuedAt: string
  idleExpiresAt: string
  absoluteExpiresAt: string
  revoked: boolean
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

// ── Agent memory ──────────────────────────────────────────────────────────────
export interface MemoryNote {
  id: string
  title: string
  content?: string
  classification?: 'NONE' | 'PII' | 'HEALTH' | 'FINANCIAL'
  /** Kept from every agent; title and content are hidden until the user reveals it. */
  restricted?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface MemoryFile {
  id: string
  agentId: string
  filename: string
  contentType: string
  sizeBytes: number
  /** How much text the agent can search in this file; 0 means it holds the file but can't read it. */
  readableChars: number
  createdAt: string
}

// ── Memory Cosmos ─────────────────────────────────────────────────────────────
export type BodyType = 'SUN' | 'AGENT' | 'SUBJECT' | 'NOTES' | 'FILE' | 'CONNECTOR' | 'WORKFLOW' | 'SKILL'
export type MemoryCategory =
  'PROFILE' | 'PREFERENCE' | 'RELATIONSHIP' | 'FINANCE' | 'HEALTH' | 'PROFESSIONAL' | 'PROJECT' | 'TASK' | 'GENERAL' | 'INSTRUCTION'
export type MemoryStatus = 'ACTIVE' | 'PENDING' | 'SUPERSEDED' | 'RETIRED'
export type Classification = 'NONE' | 'PII' | 'HEALTH' | 'FINANCIAL'
export type ItemKind = 'fact' | 'note'
/** The Cosmos speaks in lower case: a fact's category, or "note" for an agent's note. */
export type NodeCategory = Lowercase<MemoryCategory> | 'note'
export type NodeStatus = 'active' | 'pending' | 'superseded' | 'retired'
export type NodeClassification = 'none' | 'pii' | 'health' | 'financial'

export interface CelestialBody {
  bodyId: string
  type: BodyType
  parentBodyId: string | null
  label: string
  agentKey: string
  tier: number
  memoryCount: number
  sharedCount: number
  lastActivityAt: string | null
  meta: Record<string, unknown> | null
}

export interface BodyLink { fromBodyId: string; toBodyId: string; kind: string }

export interface CosmosSnapshot { bodies: CelestialBody[]; links: BodyLink[]; generatedAt: string }

export interface MemoryNode {
  itemKind: ItemKind
  id: string
  parentBodyId: string
  /** Null when the item is restricted and not yet revealed. */
  label: string | null
  agentKey: string
  category: NodeCategory | null
  classification: NodeClassification | null
  restricted: boolean
  redacted: boolean
  status: NodeStatus | null
  confirmed: boolean
  accessCount: number
  lastAccessedAt: string | null
  createdAt: string
  tetherBodyIds: string[]
}

export interface ItemPage { items: MemoryNode[]; nextCursor: string | null }

export interface MemorySearchHit { item: MemoryNode; bodyPath: string[]; snippet: string | null; score: number }

export interface PassportStamp {
  action: string
  actorType: string | null
  actorId: string | null
  actorLabel: string | null
  contextKind: string | null
  contextRef: string | null
  at: string
  note: string | null
}

export interface Passport {
  item: MemoryNode
  title: string | null
  content: string | null
  visibility: { everyAgent: boolean; domains: string[]; agents: { id: string; key: string; name: string }[] }
  origin: { kind: string; label: string | null; ref: string | null; conversationId: string | null; fileId: string | null; agentId: string | null }
  stamps: PassportStamp[]
  nextCursor: string | null
}

export interface MemorySearchFilters {
  agentIds?: string[]
  categories?: NodeCategory[]
  classification?: NodeClassification
  status?: NodeStatus
  from?: string
  to?: string
}

export interface MemoryRealtimeEvent {
  kind: 'MEMORY_CREATED' | 'MEMORY_UPDATED' | 'MEMORY_FORGOTTEN' | 'VISIBILITY_CHANGED' | 'BODY_CHANGED'
  itemKind?: ItemKind
  itemId?: string
}

export interface AgentMemory {
  agent: { id: string; key: string; name: string; domain?: string }
  notes: MemoryNote[]
  files: MemoryFile[]
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
  | { type: 'propose_workflow'; payload: WorkflowPlan }

/** A workflow Jizza planned with the specialists, waiting for the user's approval before it is saved. */
export interface WorkflowPlan {
  planId: string
  name: string
  description?: string
  steps: WorkflowPlanStep[]
  /** Defaults Jizza picked that the user didn't state. */
  assumptions?: string[]
}

export interface WorkflowPlanStep {
  kind: 'agent' | 'ask'
  instruction: string
  why?: string
  agentId?: string
  agentKey?: string
  agentName?: string
  /** The step changes something outside Jizza (sends, creates, buys). */
  changesThings?: boolean
  /** What the agent's current rules would do when this step changes something. */
  approval?: 'auto' | 'ask' | 'never'
}

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

/** A conversation as the conversation list shows it. */
export interface ConversationSummary {
  id: string
  title?: string
  preview?: string
  createdAt: string
  lastMessageAt?: string
  pinned: boolean
  archived: boolean
  messageCount: number
}

export interface MessageDto {
  id: string
  role: 'user' | 'assistant' | 'event'
  content: string
  createdAt: string
  status?: 'streaming' | 'complete' | 'failed' | 'cancelled'
  jobId?: string
  /** What the reply looked like live: who helped and what it did on screen. */
  metadata?: { contributors?: Contribution[]; actions?: UiAction[]; jobId?: string; notificationId?: string; parentJobId?: string; approved?: boolean }
}

// ── Jobs (the Activity panel) ─────────────────────────────────────────────────
export type JobStatus = 'running' | 'awaiting_user' | 'completed' | 'failed' | 'cancelled'

export type JobEventType =
  | 'job.started' | 'job.completed' | 'job.failed' | 'job.cancelled'
  | 'delta' | 'segment.preamble'
  | 'agent.started' | 'agent.message'
  | 'tool.call' | 'tool.api'
  | 'memory.used' | 'memory.saved' | 'output'
  | 'approval.required' | 'ui.action' | 'conversation.titled'

export interface JobEvent {
  seq: number
  type: JobEventType
  agentId?: string
  agentName?: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  payload: Record<string, any>
  at: string
}

export interface JobDto {
  id: string
  conversationId?: string
  origin: string
  title?: string
  summary?: string
  status: JobStatus
  userMessageId?: string
  assistantMessageId?: string
  startedAt: string
  finishedAt?: string
  events: JobEvent[]
  partialText?: string
}

/** What sending a message returns: the job to follow. */
export interface TurnRef {
  jobId: string
  conversationId?: string
  userMessageId?: string
  assistantMessageId?: string
  incognito: boolean
}

/** One event as it arrives on /user/queue/chat. */
export interface ChatSocketEvent extends JobEvent {
  jobId: string
  conversationId?: string
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

// ── Connector services ────────────────────────────────────────────────────────
export type AuthScheme = 'OAUTH2' | 'API_KEY' | 'BASIC' | 'CUSTOM' | 'NO_AUTH'

export interface ServiceInfo {
  id: string
  name: string
  slug: string
  description?: string
  category?: string
  baseUrl?: string
  authScheme: AuthScheme
  connectionStatus: 'NEW' | 'CONNECTED' | 'NEEDS_REAUTH' | 'ERROR'
  credentialPresent: boolean
  versionStatus: 'draft' | 'live'
  liveVersion: number
  publishedAt?: string
  apiCount: number
  actionCount: number
  triggerCount: number
  createdAt: string
  updatedAt: string
}

export interface ServiceApi {
  id: string
  serviceId: string
  name: string
  label: string
  description?: string
  kind: 'request' | 'polling' | 'webhook'
  httpMethod: string
  pathTemplate: string
  inputSchema?: Record<string, unknown>
  paramMapping?: Record<string, unknown>
  sideEffecting: boolean
  enabled: boolean
}

export interface ResourceField { key: string; label?: string; type?: string; required?: boolean }

export interface ServiceResource {
  id: string
  serviceId: string
  name: string
  label: string
  description?: string
  fields: ResourceField[]
  idField?: string
}

export interface ServiceAction {
  id: string
  serviceId: string
  name: string
  label: string
  description?: string
  apiId: string
  resourceId?: string
  enabled: boolean
  sideEffecting: boolean
  toolName: string
  agentIds: string[]
}

export interface ServiceTrigger {
  id: string
  serviceId: string
  name: string
  label: string
  description?: string
  apiId: string
  resourceId?: string
  kind: 'polling' | 'webhook'
  pollIntervalMinutes: number
  itemsPath?: string
  idField: string
  enabled: boolean
  lastPolledAt?: string
}

export interface ServiceVariable {
  id: string
  serviceId: string
  name: string
  description?: string
  value?: string
  secret: boolean
  valueSet: boolean
}

export interface ServiceRun {
  id: string
  kind: 'action' | 'trigger' | 'test'
  refName?: string
  source: string
  status: 'ok' | 'error'
  durationMs?: number
  input?: unknown
  output?: unknown
  error?: string
  createdAt: string
}

export interface ServiceDetail {
  service: ServiceInfo
  apis: ServiceApi[]
  resources: ServiceResource[]
  actions: ServiceAction[]
  triggers: ServiceTrigger[]
  variables: ServiceVariable[]
  /** Where the OAuth provider sends the user back; register it with the provider. */
  oauthRedirectUri: string
}

export interface TriggerSample { ok: boolean; error?: string; totalItems: number; newItems: number; items: unknown[] }

export interface ToolRunResult { ok: boolean; output?: unknown; error?: string }

export interface ConnectionDetail {
  id: string
  serviceName: string
  authScheme: AuthScheme
  clientId?: string
  clientSecretSet: boolean
  authorizationUrl?: string
  accessTokenUrl?: string
  refreshTokenUrl?: string
  scopes?: string
  scopeSeparator?: string
  pkce: boolean
  tokenPlacement?: 'HEADER' | 'QUERY' | 'BODY'
  grantType?: string
  tokenExpiresAt?: string
  tokenPresent: boolean
  extraFields?: { apiKey?: { name?: string; prefix?: string; in?: 'header' | 'query' } } | null
  status: 'NEW' | 'CONNECTED' | 'NEEDS_REAUTH' | 'ERROR'
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

export type RunStatus = 'RUNNING' | 'PAUSED_WAITING_INSTRUCTION' | 'DONE' | 'FAILED' | 'CANCELLED'

export interface RunStepDto {
  id: string
  nodeId: string
  status: 'DONE' | 'FAILED' | 'SKIPPED'
  output?: unknown
  attempts: number
  finishedAt?: string
}

export interface RunDto {
  id: string
  workflowId: string
  version: number
  status: RunStatus
  currentNode?: string
  createdAt: string
  updatedAt: string
  steps?: RunStepDto[]
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
  /** On `permission-denied`: the permission that was missing, and its area. */
  permission?: string
  module?: string
}

// ── Accounts, members and access ──────────────────────────────────────────────
export type WorkspaceType = 'PERSONAL' | 'ORGANIZATION'
export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'USER'

export interface WorkspaceSummary {
  id: string
  type: WorkspaceType
  name: string
  role: WorkspaceRole
}

/** Who the signed-in person is in the active account, and what they may do there. */
export interface AuthorizationSnapshot {
  userId: string
  email: string
  platformRole: 'NONE' | 'SUPER_ADMIN'
  activeWorkspace: WorkspaceSummary
  /** The caller's role in the active account. */
  role: WorkspaceRole | null
  /** Granted permission names in the active account. */
  permissions: string[]
  /** Roles the caller may invite or assign in the active account. */
  assignableRoles: WorkspaceRole[]
  /** Personal first, then organizations by name. */
  workspaces: WorkspaceSummary[]
  pendingInvitationCount: number
  permissionsVersion: string
}

export interface PermissionDefinition {
  name: string
  label: string
  description: string
  category: 'read' | 'read_and_write'
  module: string
  dependencies: string[]
}

export interface Member {
  id: string
  userId: string
  email: string
  role: WorkspaceRole
  status: 'ACTIVE' | 'SUSPENDED'
  joinedAt: string
  you: boolean
}

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'REVOKED' | 'EXPIRED'

/** A membership request as the organization sees it. */
export interface Invitation {
  id: string
  email: string
  role: WorkspaceRole
  status: InvitationStatus
  invitedByEmail: string
  createdAt: string
  expiresAt: string
  respondedAt: string | null
}

/** A membership request as the invited person sees it. */
export interface ReceivedInvitation {
  id: string
  workspaceId: string
  workspaceName: string
  role: WorkspaceRole
  invitedByEmail: string
  createdAt: string
  expiresAt: string
}
