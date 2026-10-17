/**
 * Permission names, mirroring the server's `Permissions` constants. Names only: what is granted
 * always comes from the server's snapshot (`GET /api/me/context`), never from a role name.
 */
export const PERMISSIONS = {
  CONNECTOR_VIEW: 'connector_view',
  CONNECTOR_CREATE: 'connector_create',
  CONNECTOR_MODIFY: 'connector_modify',
  CONNECTOR_DELETE: 'connector_delete',
  MEMBERS_VIEW: 'members_view',
  MEMBERS_INVITE: 'members_invite',
  MEMBERS_MODIFY: 'members_modify',
  MEMBERS_REMOVE: 'members_remove',
  GUARDRAILS_VIEW: 'guardrails_view',
  GUARDRAILS_LOCK: 'guardrails_lock',
  CONNECTOR_USE: 'connector_use',
  AGENT_VIEW: 'agent_view',
  AGENT_CREATE: 'agent_create',
  AGENT_MODIFY: 'agent_modify',
  AGENT_ARCHIVE: 'agent_archive',
  AGENT_TEMPLATE_VIEW: 'agent_template_view',
  AGENT_TEMPLATE_MANAGE: 'agent_template_manage',
  AGENT_TEMPLATE_INSTALL: 'agent_template_install',
} as const

export type PermissionName = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

/** Which area each permission belongs to, matching `module` in the server's permission file. */
export const PERMISSION_MODULE: Record<PermissionName, string> = {
  connector_view: 'connector',
  connector_create: 'connector',
  connector_modify: 'connector',
  connector_delete: 'connector',
  members_view: 'members',
  members_invite: 'members',
  members_modify: 'members',
  members_remove: 'members',
  guardrails_view: 'guardrails',
  guardrails_lock: 'guardrails',
  connector_use: 'connector',
  agent_view: 'agents',
  agent_create: 'agents',
  agent_modify: 'agents',
  agent_archive: 'agents',
  agent_template_view: 'agent_templates',
  agent_template_manage: 'agent_templates',
  agent_template_install: 'agent_templates',
}

export const ROLE_LABELS = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  DEVELOPER: 'Developer',
  USER: 'User',
} as const
