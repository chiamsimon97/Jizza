/** Every page a signed-in Individual account can open, with a heading that proves it rendered. */
export const PAGES: { path: string; name: string; expect: RegExp }[] = [
  { path: '/chat', name: 'Chat', expect: /jizza|chat|message/i },
  { path: '/agents', name: 'Agents', expect: /agents?/i },
  { path: '/agents/new', name: 'New agent', expect: /agent/i },
  { path: '/memory', name: 'Memory', expect: /memory|sky|cosmos/i },
  { path: '/tasks', name: 'Tasks', expect: /tasks?/i },
  { path: '/workflows', name: 'Workflows', expect: /workflows?/i },
  { path: '/procedures', name: 'Procedures', expect: /procedures?/i },
  { path: '/guardrails', name: 'Guardrails', expect: /guardrails?/i },
  { path: '/activity', name: 'Activity', expect: /activity/i },
  { path: '/usage', name: 'Usage', expect: /usage/i },
  { path: '/runs', name: 'Run history', expect: /runs?|history/i },
  { path: '/notifications', name: 'Notifications', expect: /notifications|inbox/i },
  { path: '/invitations', name: 'Invitations', expect: /invitations?|requests?/i },
  { path: '/connectors', name: 'Connectors', expect: /connectors?/i },
  { path: '/settings/security', name: 'Security', expect: /security/i },
]
