/** The pages Jizza can open, keyed the way the backend's app_navigate tool names them. */
export const PAGES: Record<string, { path: string; label: string; aliases: string[] }> = {
  chat:          { path: '/chat',          label: 'Chat',          aliases: ['chat', 'home'] },
  conversations: { path: '/conversations', label: 'History',       aliases: ['history', 'conversations', 'past chats', 'chat history'] },
  notifications: { path: '/notifications', label: 'Notifications', aliases: ['notifications', 'inbox', 'alerts', 'approvals'] },
  agents:        { path: '/agents',        label: 'Agents',        aliases: ['agents', 'team', 'specialists'] },
  memory:        { path: '/memory',        label: 'Memory',        aliases: ['memory', 'memories', 'what you know', 'my files', 'documents'] },
  tasks:         { path: '/tasks',         label: 'Tasks',         aliases: ['tasks', 'schedule', 'scheduled tasks', 'jobs'] },
  connections:   { path: '/connectors',    label: 'Connectors',    aliases: ['connectors', 'connector', 'services', 'connections', 'integrations', 'apps'] },
  workflows:     { path: '/workflows',     label: 'Workflows',     aliases: ['workflows', 'workflow', 'automations'] },
  procedures:    { path: '/procedures',    label: 'Procedures',    aliases: ['procedures', 'reviews', 'proposals'] },
  autonomy:      { path: '/autonomy',      label: 'Autonomy',      aliases: ['autonomy', 'kill switch', 'rules', 'permissions'] },
  activity:      { path: '/activity',      label: 'Activity',      aliases: ['activity', 'activity log', 'logs', 'audit'] },
  usage:         { path: '/usage',         label: 'Usage',         aliases: ['usage', 'cost', 'costs', 'spend', 'billing', 'tokens'] },
}

/** Which page a form lives on, so opening a form also brings its page up. */
export const FORM_PAGE: Record<string, string> = {
  new_workflow: 'workflows',
  new_task: 'tasks',
}

export function pageForPath(pathname: string): string | undefined {
  return Object.entries(PAGES).find(([, p]) => pathname.startsWith(p.path))?.[0]
}
