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
export type AgentStatus = 'draft' | 'active' | 'archived'

export interface AgentAvatarDto {
  style?: string
  colour?: string
}

export interface AgentDto {
  id: string
  key: string
  name: string
  domain?: string
  enabled: boolean
  orchestrator: boolean
  /** Made by the user rather than given to every account. */
  custom?: boolean
  /** "When to use me": what its manager reads when choosing who to ask. */
  description?: string | null
  /** Its instructions. */
  systemPrompt?: string
  /** draft, active or archived; a paused agent is active with enabled false. */
  status?: AgentStatus
  modelTier?: 'standard' | 'deep'
  memoryDomains?: string[]
  avatar?: AgentAvatarDto | null
  monthlyCostLimit?: number | null
  /** Who it reports to; null only for Jizza. */
  parentAgentId?: string | null
  responsibilities?: string[]
  handsOffTo?: string | null
  memoryFromManager?: boolean
  originTemplateId?: string | null
  originTemplateVersion?: number | null
}

export type ToolKind = 'GENERATED_TOOL' | 'SERVICE_ACTION' | 'SKILL' | 'KNOWLEDGE'

export interface AgentToolOption {
  type: ToolKind
  ref: string
  name: string
  description?: string | null
  source?: string | null
  actionType?: ActionType | null
  workspaceId?: string
}

export interface AgentGrant { type: ToolKind; ref: string }

/** One agent as a template or a proposal describes it, before it exists. */
export interface AgentBlueprint {
  ref?: string | null
  name: string
  description?: string | null
  instructions?: string | null
  avatar?: AgentAvatarDto | null
  modelTier?: 'standard' | 'deep'
  reportsTo?: string | null
  responsibilities?: string[]
  handsOffTo?: string | null
  memoryDomains?: string[]
  tools?: AgentGrant[]
  toolHints?: string[]
  modes?: Partial<Record<ActionType, GuardrailMode>>
  locks?: ActionType[]
  /** On a proposal: where it would stop for the user, per kind of action. */
  stops?: Partial<Record<ActionType, GuardrailMode>>
}

export interface AgentGalleryItem {
  id: string
  /** agent, team, or team:<built-in key> for a team meant to go under that agent. */
  kind: string
  name: string
  description: string
  agents: AgentBlueprint[]
}

export interface OrgAgentTemplate {
  id: string
  workspaceId: string
  workspaceName?: string
  name: string
  description?: string | null
  status: 'draft' | 'published' | 'unpublished'
  version: number
  kind: 'agent' | 'team'
  agents: AgentBlueprint[]
}

export interface AgentTemplateUpdate {
  agentId: string
  agentName: string
  templateId: string
  templateName: string
  from: number
  to: number
}

export interface AgentRevisionDto {
  id: string
  agentId: string
  revision: number
  name: string
  description?: string | null
  systemPrompt: string
  modelTier: string
  changedByUserId?: string | null
  createdAt: string
}

export interface AgentDependent {
  kind: 'workflow' | 'task'
  id: string
  name: string
  status?: string
  ownedByAgent: boolean
}

export interface ArchiveResult {
  agent: AgentDto
  reassigned: number
  reportsMoved: number
  reportsPaused: number
  dependents: AgentDependent[]
}

export interface TeamNode {
  agent: AgentDto
  depth: number
  reports: TeamNode[]
}

export interface TeamStep {
  fromAgentId?: string
  fromName: string
  toAgentId?: string
  toName: string
  depth: number
  task?: string
  answer?: string | null
  failed: boolean
  tokens: number
}

export interface HeldCall {
  agentId: string
  agentName: string
  tool: string
  wouldDo: string
  reason: string
}

export interface AgentTrial {
  answer?: string | null
  error?: string | null
  toolsUsed: string[]
  wouldHaveDone: HeldCall[]
  team: TeamStep[]
  tokens: number
}

export interface ModeLine {
  actionType: ActionType
  rule: GuardrailMode
  ceiling?: GuardrailMode | null
  ceilingFrom?: string | null
}

/** What the agent form sends to create an agent. */
export interface AgentCreate {
  name: string
  description?: string
  instructions: string
  modelTier?: 'standard' | 'deep'
  memoryDomains?: string[]
  avatar?: AgentAvatarDto
  monthlyCostLimit?: number | null
  parentAgentId?: string | null
  responsibilities?: string[]
  handsOffTo?: string
  memoryFromManager?: boolean
  status?: 'active' | 'draft'
  tools?: AgentGrant[]
  modes?: Partial<Record<ActionType, GuardrailMode>>
  locks?: ActionType[]
}

export interface AgentEdit {
  name?: string
  enabled?: boolean
  description?: string
  instructions?: string
  modelTier?: 'standard' | 'deep'
  memoryDomains?: string[]
  avatar?: AgentAvatarDto
  monthlyCostLimit?: number | null
  clearMonthlyCostLimit?: boolean
  responsibilities?: string[]
  handsOffTo?: string
  memoryFromManager?: boolean
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
  | { type: 'ask_user'; payload: { question: string; options?: string[]; multiple?: boolean } }
  | { type: 'set_theme'; payload: { theme: string } }
  | { type: 'propose_workflow'; payload: WorkflowPlan }
  | { type: 'propose_agent'; payload: AgentProposal }
  /** Settings for the Builder step Jizza was opened on; they land in its panel unsaved. */
  | { type: 'configure_step'; payload: { stepId: string; label?: string; config: Record<string, unknown>; summary?: string } }
  /** Jizza ran one of the user's saved workflows instead of building a new one. */
  | { type: 'reused_workflow'; payload: { workflowId: string; name: string; runId: string; status: string } }

/** A new agent, or a team, Jizza drafted; it exists only once the user creates it. */
export interface AgentProposal {
  proposalId: string
  parentAgentId: string
  parentAgentName: string
  summary?: string
  agents: AgentBlueprint[]
}

/** A workflow Jizza planned with the specialists, waiting for the user's approval before it is saved. */
export interface WorkflowPlan {
  planId: string
  name: string
  description?: string
  steps: WorkflowPlanStep[]
  /** Defaults Jizza picked that the user didn't state. */
  assumptions?: string[]
  /** Set when Jizza offers to keep a job it just did, so it can reuse it next time. */
  reuse?: ReuseProfile
}

/** What Jizza keeps about a saved workflow to recognise and reuse it. */
export interface ReuseProfile {
  /** When this workflow is the right one. */
  summary?: string
  examples?: string[]
  inputs?: { name: string; description?: string; required?: boolean }[]
  guardrails?: ReuseGuardrails
  learnedFrom?: { conversationId?: string; savedAt?: string }
}

export interface ReuseGuardrails {
  /** Jizza asks before each run it starts from a chat. */
  confirmBeforeRun: boolean
  /** A scheduled task doing this job may run it on its own. */
  allowScheduled: boolean
  maxRunsPerDay?: number
  rules?: string[]
}

/** A workflow in the library, as the server describes it. */
export interface LibraryEntry {
  workflowId: string
  name: string
  description?: string
  status: string
  version: number
  profile?: ReuseProfile
}

export interface WorkflowPlanStep {
  /** tool: makes a service call the chat made, exactly; agent: a specialist decides; ask: asks you. */
  kind: 'tool' | 'agent' | 'ask'
  instruction: string
  /** For a tool step: the service action, and what it is called with. */
  toolId?: string
  toolName?: string
  toolLabel?: string
  arguments?: Record<string, unknown>
  /** For a tool step: the inputs it reads, which each run is given. */
  inputs?: string[]
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

/** UNREAD and READ are still open; the rest record how a request ended. */
export type NotificationStatus = 'UNREAD' | 'READ' | 'APPROVED' | 'DENIED' | 'SUBMITTED' | 'EXPIRED'

export type NotificationPriority = 'URGENT' | 'NORMAL' | 'LOW'

export interface NotificationDto {
  id: string
  kind: NotificationKind
  sourceAgentId?: string | null
  sourceAgentName?: string | null
  body?: string | null
  /** URGENT reaches the user straight away; LOW is never chased. */
  priority?: NotificationPriority
  context?: Record<string, unknown> | null
  taskId?: string | null
  workflowId?: string | null
  runId?: string | null
  status: NotificationStatus
  /** What the user wrote back to a question. */
  submittedInstruction?: string | null
  /** Why the user declined or asked for changes. */
  responseNote?: string | null
  expiresAt?: string | null
  escalationState?: string | null
  createdAt: string
  answeredAt?: string | null
  archivedAt?: string | null
  snoozedUntil?: string | null
}

/** The counts behind the sidebar badge and the inbox header. */
export interface NotificationSummary {
  needsYou: number
  urgent: number
  unread: number
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
  /** Its agent writes down the steps it takes as a procedure for you to review. */
  repetitive: boolean
  /** The approved procedure it follows step by step, when it has one. */
  boundWorkflowId?: string
  boundVersion?: number
  agentName?: string
  timezone?: string
  lastRunAt?: string
  lastResult?: string
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
  /** The repeating task an agent learned it from. */
  sourceTaskId?: string
  createdAt: string
  /** Kept in the workflow library for Jizza to reuse; absent when it is not. */
  reuse?: ReuseProfile
}

export type RunStatus = 'RUNNING' | 'PAUSED_WAITING_INSTRUCTION' | 'WAITING' | 'DONE' | 'FAILED' | 'CANCELLED'

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
/** A workflow an agent wrote down from a repeating task, with what the review screen needs. */
export interface ProcedureSummary {
  workflow: WorkflowDto
  disabled: boolean
  reviewFeedback?: string | null
  /** The task it was learned from. */
  sourceTaskTitle?: string | null
  /** The version that task follows now; absent when the task follows none of it. */
  boundVersion?: number | null
  agentName?: string | null
}

// ── Guardrails ────────────────────────────────────────────────────────────────
/** Auto, Ask me, Let Jizza decide, Never. */
export type GuardrailMode = 'ALLOW' | 'ASK' | 'JIZZA' | 'DENY'

export type ActionType = 'READ' | 'DRAFT' | 'CREATE' | 'UPDATE' | 'DELETE' | 'SEND' | 'MONEY'

/** What a looser rule needs before it applies; anything unmet means the user is asked. */
export interface GuardrailLimits {
  maxValue?: number
  field?: string
  condition?: string
  conditionLabel?: string
  budget?: { max: number; per: string; field?: string }
  maxCalls?: { count: number; per: string }
  activeHours?: { days?: string[]; from: string; to: string; zone: string }
  [key: string]: unknown
}

export interface AutonomyRuleDto {
  id?: string
  agentId?: string | null
  toolRef?: string
  rule: GuardrailMode
  limits?: GuardrailLimits | null
  precedence: number
  workflowId?: string | null
  workflowVersion?: number | null
  nodeId?: string | null
  locked?: boolean
  workspaceId?: string | null
}

export interface GuardrailDecision {
  rule: GuardrailMode
  reason: string
  matchedPolicy?: string
  policyId?: string | null
  actionType?: ActionType | null
  budgeted?: boolean
}

export interface GuardrailCell {
  rule: GuardrailMode
  policyId?: string | null
  matched?: string
  locked: boolean
}

export interface AgentGuardrails {
  id: string
  key: string
  name: string
  orchestrator: boolean
  enabled: boolean
  modes: Record<ActionType, GuardrailCell>
  custom?: boolean
  parentAgentId?: string | null
  status?: AgentStatus
}

export interface QuietHours {
  start?: string | null
  end?: string | null
  timeZone?: string | null
}

export interface GuardrailsOverview {
  rules: AutonomyRuleDto[]
  agents: AgentGuardrails[]
  defaultRule: string
  paused: boolean
  quietHours?: QuietHours | null
}

export interface GuardrailStep {
  nodeId: string
  label?: string
  type: string
  agentId?: string | null
  agentName?: string | null
  toolName?: string | null
  actionType?: ActionType | null
  decision?: GuardrailDecision | null
}

export interface WorkflowGuardrails {
  workflowId: string
  name: string
  version: number
  owningAgentId?: string | null
  steps: GuardrailStep[]
  rules: AutonomyRuleDto[]
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

export type ActivitySeverity = 'INFO' | 'NOTICE' | 'WARNING' | 'CRITICAL'
export type ActorType = 'USER' | 'AGENT' | 'SYSTEM'

/** One audit entry as the activity explorer files it. */
export interface ActivityItem {
  id: string
  createdAt: string
  action: string
  label: string
  category: string
  group: string
  severity: ActivitySeverity
  actorType: ActorType
  actorId?: string
  actorName?: string
  target?: string
  detail?: unknown
  requestId?: string
  hash: string
}

export interface ActivityGroupCount { key: string; label: string; count: number }

export interface ActivityCategoryCount {
  key: string
  label: string
  icon: string
  count: number
  groups: ActivityGroupCount[]
}

export interface ActivityBucket { start: string; total: number; byCategory: Record<string, number> }

export interface ActivityPage {
  items: ActivityItem[]
  total: number
  categories: ActivityCategoryCount[]
  severities: Partial<Record<ActivitySeverity, number>>
  actors: Partial<Record<ActorType, number>>
  bucketUnit: 'hour' | 'day'
  buckets: ActivityBucket[]
  truncated: boolean
}

export interface ActivityCatalog {
  categories: ActivityCategoryCount[]
  severities: ActivitySeverity[]
  actors: ActorType[]
}

// ── Run history ───────────────────────────────────────────────────────────────
export type TriggerKind = 'manual' | 'webhook' | 'schedule' | 'agent' | 'event' | 'subworkflow' | 'other'

export interface RunSummary {
  id: string
  workflowId: string
  workflowName: string
  version: number
  triggerSource?: string
  triggerKind: TriggerKind
  status: RunStatus
  startedAt: string
  endedAt?: string
  durationMs: number
  stepCount: number
  failedSteps: number
  error?: string
  failedNode?: string
  parentRunId?: string
}

export interface RunHistoryPage {
  items: RunSummary[]
  total: number
  stats: {
    total: number
    byStatus: Partial<Record<RunStatus, number>>
    avgDurationMs: number
    p95DurationMs: number
    successRate: number
  }
  bucketUnit: 'hour' | 'day'
  buckets: { start: string; done: number; failed: number; other: number }[]
  workflows: { id: string; name: string; runs: number; failed: number }[]
  triggers: Partial<Record<TriggerKind, number>>
  truncated: boolean
}

export interface TraceStep {
  id: string
  nodeId: string
  label: string
  type?: string
  status: 'DONE' | 'FAILED' | 'SKIPPED'
  input?: unknown
  output?: unknown
  error?: string
  attempts: number
  startedAt?: string
  finishedAt?: string
  durationMs?: number
}

export interface RunTrace {
  run: RunSummary
  input?: unknown
  result?: unknown
  nodes: { id: string; type: string; label: string }[]
  edges: { from: string; to: string; branch?: string }[]
  steps: TraceStep[]
  waitingOn?: string
  children: RunSummary[]
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

export type UsageDimension = 'MODEL' | 'AREA' | 'PURPOSE' | 'DAY'

export interface UsageTotals { calls: number; inputTokens: number; outputTokens: number; costUnits: number }

export interface UsageGroup { key: string; label: string; totals: UsageTotals; children: UsageGroup[] }

export interface UsageExplorePage {
  totals: UsageTotals
  groups: UsageGroup[]
  bucketUnit: 'hour' | 'day'
  buckets: { start: string; calls: number; tokens: number; costUnits: number }[]
  entries: UsageEntryDto[]
  models: string[]
  areas: string[]
  truncated: boolean
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
  /** Which of `workspaces` are listed publicly on Discover. */
  publicWorkspaceIds?: string[]
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

/** Everyone's role in the platform's Public organization, for the Super Admin. */
export interface PublicRoles {
  workspaceId: string
  workspaceName: string
  members: Member[]
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

// ── MCP servers (connectors that speak the Model Context Protocol) ─────────────

export type McpAuthType = 'NONE' | 'BEARER' | 'HEADER' | 'OAUTH'

export interface McpTool {
  /** The server's own name for the tool. */
  name: string
  /** What agents see: mcp_<server>__<tool>. */
  toolName: string
  title?: string | null
  description?: string | null
  inputSchema?: Record<string, unknown> | null
  enabled: boolean
  /** What it does: yours, or Jizza's suggestion from its name. Null is judged as moving money. */
  actionType?: ActionType | null
  /** True while actionType is Jizza's suggestion and you haven't changed it. */
  suggested?: boolean
  /** The shape of what it returns, as JSON Schema: the server's own, or learnt from a run. */
  outputSchema?: Record<string, unknown> | null
  /** Where outputSchema came from. */
  outputSource?: 'server' | 'learnt' | null
  /** What the server claims (read-only, destructive); a hint only. */
  serverHint?: string | null
}

export interface McpServer {
  id: string
  name: string
  slug: string
  url: string
  authType: McpAuthType
  headerName?: string | null
  secretPresent: boolean
  signedIn: boolean
  enabled: boolean
  agentKeys: string[]
  redactArguments: boolean
  status: 'new' | 'connected' | 'needs_auth' | 'error'
  lastError?: string | null
  serverName?: string | null
  serverVersion?: string | null
  tools: McpTool[]
  toolsRefreshedAt?: string | null
  createdAt?: string | null
}

export interface McpServerInput {
  name?: string
  url?: string
  authType?: McpAuthType
  headerName?: string
  secret?: string
  enabled?: boolean
  agentKeys?: string[]
  redactArguments?: boolean
  oauthClientId?: string
  oauthClientSecret?: string
  oauthScopes?: string
}

// ── Public organizations ─────────────────────────────────────────────────────

/** Only APPROVED shows on Discover (and PENDING, while an edit to a listed profile is in review). */
export type ListingStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED'
export type ListingAction = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'UNLISTED' | 'SUSPENDED' | 'REINSTATED'
export type JoinRequestStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED'

export interface ListingProfile {
  displayName: string
  description: string
  category: string
  logoUrl: string | null
  website: string | null
}

export interface ListingReview {
  id: string
  action: ListingAction
  actorEmail: string | null
  note: string | null
  createdAt: string
}

/** An organization's listing, as its Owner, Admin or a Super Admin sees it. */
export interface Listing {
  workspaceId: string
  workspaceName: string
  status: ListingStatus
  /** The profile being edited. */
  draft: ListingProfile | null
  /** The version on Discover, or null when it has none. */
  approved: ListingProfile | null
  visible: boolean
  changesSinceApproval: boolean
  submittedAt: string | null
  listedAt: string | null
  history: ListingReview[]
  /** Owner's view only. */
  submissionsLeftToday: number | null
}

export interface DirectoryEntry {
  id: string
  profile: ListingProfile
  listedAt: string | null
}

export interface DirectoryPage {
  items: DirectoryEntry[]
  total: number
  page: number
  pageSize: number
}

export interface JoinRequest {
  id: string
  workspaceId: string
  workspaceName: string
  email: string
  message: string | null
  status: JoinRequestStatus
  createdAt: string
  decidedAt: string | null
}

export interface DirectoryProfile extends DirectoryEntry {
  member: boolean
  pendingRequest: JoinRequest | null
}
