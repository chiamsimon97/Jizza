import type { AuthorizationSnapshot } from '@/types'

/** The pages Jizza can open, keyed the way the backend's app_navigate tool names them. */
export const PAGES: Record<string, { path: string; label: string; aliases: string[] }> = {
  chat:          { path: '/chat',          label: 'Chat',          aliases: ['chat', 'home'] },
  conversations: { path: '/chat',          label: 'History',       aliases: ['history', 'conversations', 'past chats', 'chat history'] },
  notifications: { path: '/notifications', label: 'Notifications', aliases: ['notifications', 'inbox', 'alerts', 'approvals'] },
  agents:        { path: '/agents',        label: 'Agents',        aliases: ['agents', 'team', 'specialists'] },
  memory:        { path: '/memory',        label: 'Memory',        aliases: ['memory', 'memories', 'what you know', 'my files', 'documents'] },
  tasks:         { path: '/tasks',         label: 'Tasks',         aliases: ['tasks', 'schedule', 'scheduled tasks', 'jobs'] },
  connections:   { path: '/connectors',    label: 'Connectors',    aliases: ['connectors', 'connector', 'services', 'connections', 'integrations', 'apps'] },
  workflows:     { path: '/workflows',     label: 'Workflows',     aliases: ['workflows', 'workflow', 'automations'] },
  procedures:    { path: '/procedures',    label: 'Procedures',    aliases: ['procedures', 'reviews', 'proposals'] },
  guardrails:    { path: '/guardrails',    label: 'Guardrails',    aliases: ['guardrails', 'autonomy', 'kill switch', 'rules', 'permissions', 'approvals'] },
  activity:      { path: '/activity',      label: 'Activity',      aliases: ['activity', 'activity log', 'logs', 'audit'] },
  runs:          { path: '/runs',          label: 'Run history',   aliases: ['run history', 'runs', 'workflow runs', 'executions', 'task history', 'run log'] },
  recordings:    { path: '/recordings',    label: 'Recordings',    aliases: ['recordings', 'recording', 'recorded tasks', 'web recordings'] },
  functions:     { path: '/functions',     label: 'Functions',     aliases: ['functions', 'function', 'scripts', 'script functions'] },
  devices:       { path: '/settings/devices', label: 'Devices',     aliases: ['devices', 'device', 'agents on my computer', 'paired devices', 'browsers', 'phones'] },
  help:          { path: '/help',          label: 'Help',          aliases: ['help', 'guides', 'docs', 'how to'] },
  usage:         { path: '/usage',         label: 'Usage',         aliases: ['usage', 'cost', 'costs', 'spend', 'billing', 'tokens'] },
  members:       { path: '/members',       label: 'Members',       aliases: ['members', 'member', 'people', 'organization members', 'membership requests'] },
}

/** Pages that need a permission before they can be opened. */
const PAGE_PERMISSION: Record<string, string> = {
  connections: 'connector_view',
  members: 'members_view',
}

/** The only pages an Organization account has; the rest belong to the Individual account. */
const ORGANIZATION_PAGES = new Set(['members', 'connections', 'notifications'])

/**
 * Whether Jizza may open this page for the active account and role. Without a snapshot yet, only
 * personal pages that need no permission are allowed; the server still checks every request.
 */
export function canOpenPage(key: string, snapshot: AuthorizationSnapshot | undefined): boolean {
  if (!PAGES[key]) return false
  const isOrg = snapshot?.activeWorkspace.type === 'ORGANIZATION'
  if (isOrg ? !ORGANIZATION_PAGES.has(key) : key === 'members') return false
  const needed = PAGE_PERMISSION[key]
  return !needed || (snapshot?.permissions.includes(needed) ?? false)
}

/** {@link PAGES} narrowed to what the active account and role can open. */
export function pagesFor(snapshot: AuthorizationSnapshot | undefined): Record<string, (typeof PAGES)[string]> {
  return Object.fromEntries(Object.entries(PAGES).filter(([key]) => canOpenPage(key, snapshot)))
}

/** Which page a form lives on, so opening a form also brings its page up. */
export const FORM_PAGE: Record<string, string> = {
  new_workflow: 'workflows',
  new_task: 'tasks',
}

export function pageForPath(pathname: string): string | undefined {
  return Object.entries(PAGES).find(([, p]) => pathname.startsWith(p.path))?.[0]
}
