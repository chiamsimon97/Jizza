import {
  MessageSquare, Bell, Bot, Brain, ListTodo, Plug, Workflow, Braces, GitBranch, Shield, Activity, BarChart2,
  History, ShieldCheck, Users, Compass, Globe, ClipboardCheck, ClipboardList, UserCog, MonitorSmartphone, Video,
  Cpu, Hammer, Building2, Crown, SlidersHorizontal,
  type LucideIcon,
} from 'lucide-react'
import { PERMISSIONS } from '@/lib/permissions'
import type { AuthorizationSnapshot } from '@/types'

/** One page inside a menu entry, shown as a tab at the top of the page. */
export interface NavTab {
  to: string
  label: string
  icon: LucideIcon
  permission?: string
  /** Shown only to the platform's Super Admin. */
  superAdmin?: boolean
  /** Other paths that belong to this tab, such as an item's own page. */
  also?: string[]
  /** The page spans the full width (like Memory), so its tabs do too. */
  wide?: boolean
}

/**
 * One entry in the left menu. An entry with more than one tab groups similar pages: the menu shows it
 * once, and the pages switch with tabs inside it. Each tab keeps its own address, so old links still work.
 */
export interface NavHub {
  id: string
  label: string
  icon: LucideIcon
  tabs: NavTab[]
}

const hub = (id: string, label: string, icon: LucideIcon, tabs: NavTab[]): NavHub => ({ id, label, icon, tabs })
const single = (to: string, label: string, icon: LucideIcon, extra: Partial<NavTab> = {}) =>
  hub(to.slice(1), label, icon, [{ to, label, icon, ...extra }])

export const HUBS = {
  agents: hub('agents', 'Agents', Bot, [
    { to: '/agents', label: 'Agents', icon: Bot },
    { to: '/memory', label: 'Memory', icon: Brain, wide: true },
  ]),
  build: hub('build', 'Build', Hammer, [
    { to: '/workflows', label: 'Workflows', icon: Workflow },
    { to: '/recordings', label: 'Recordings', icon: Video },
    { to: '/forms', label: 'Forms', icon: ClipboardList },
    { to: '/functions', label: 'Functions', icon: Braces },
    { to: '/procedures', label: 'Procedures', icon: GitBranch },
  ]),
  activity: hub('activity', 'Activity', Activity, [
    { to: '/activity', label: 'Activity', icon: Activity },
    { to: '/runs', label: 'Run history', icon: History },
    { to: '/usage', label: 'Usage', icon: BarChart2 },
  ]),
  organization: hub('organization', 'Organization', Building2, [
    { to: '/members', label: 'Members', icon: Users, permission: PERMISSIONS.MEMBERS_VIEW },
    { to: '/organization/listing', label: 'Public listing', icon: Globe, permission: PERMISSIONS.LISTING_MANAGE },
    { to: '/organization/agent-templates', label: 'Agent templates', icon: Bot, permission: PERMISSIONS.AGENT_TEMPLATE_MANAGE },
  ]),
  platform: hub('platform', 'Platform', Crown, [
    { to: '/admin/listings', label: 'Listing reviews', icon: ClipboardCheck, superAdmin: true },
    { to: '/admin/accounts', label: 'Registered accounts', icon: UserCog, superAdmin: true },
  ]),
} satisfies Record<string, NavHub>

export type HubId = keyof typeof HUBS

/** The Individual account's menu: everyday work only. Configuration lives in Settings. */
const personalMenu: NavHub[] = [
  single('/chat', 'Chat', MessageSquare),
  single('/notifications', 'Notifications', Bell),
  single('/discover', 'Discover', Compass),
  HUBS.agents,
  single('/tasks', 'Tasks', ListTodo),
  HUBS.build,
  HUBS.activity,
]

/** An Organization account's menu. */
const organizationMenu: NavHub[] = [
  single('/notifications', 'Notifications', Bell),
  single('/discover', 'Discover', Compass),
  HUBS.organization,
]

/**
 * A Family account's menu: Members only. Nobody works inside a Family; each member keeps their own
 * Individual account, where Health sharing is done.
 */
const familyMenu: NavHub[] = [
  single('/members', 'Members', Users, { permission: PERMISSIONS.MEMBERS_VIEW }),
]

/** A group of Settings pages, shown as a heading in the Settings page's own list. */
export interface SettingsGroup { title: string; items: NavTab[] }

const personalSettings: SettingsGroup[] = [
  {
    title: 'Account',
    items: [
      { to: '/settings/general', label: 'General', icon: SlidersHorizontal },
      { to: '/settings/security', label: 'Security', icon: ShieldCheck },
      { to: '/settings/ai-models', label: 'AI models', icon: Cpu },
      { to: '/settings/devices', label: 'Devices', icon: MonitorSmartphone, permission: PERMISSIONS.DEVICE_VIEW },
    ],
  },
  {
    title: 'Automation',
    items: [
      { to: '/connectors', label: 'Connectors', icon: Plug, permission: PERMISSIONS.CONNECTOR_VIEW },
      { to: '/guardrails', label: 'Guardrails', icon: Shield },
    ],
  },
]

const organizationSettings: SettingsGroup[] = [
  {
    title: 'Account',
    items: [
      { to: '/settings/general', label: 'General', icon: SlidersHorizontal },
      { to: '/settings/security', label: 'Security', icon: ShieldCheck },
      { to: '/settings/ai-models', label: 'AI models', icon: Cpu },
      { to: '/settings/devices', label: 'Devices', icon: MonitorSmartphone, permission: PERMISSIONS.DEVICE_VIEW },
    ],
  },
  {
    title: 'Organization',
    items: [
      { to: '/connectors', label: 'Connectors', icon: Plug, permission: PERMISSIONS.CONNECTOR_VIEW },
      { to: '/organization/guardrails', label: 'Guardrails', icon: Shield, permission: PERMISSIONS.GUARDRAILS_VIEW },
    ],
  },
]

const familySettings: SettingsGroup[] = [
  {
    title: 'Account',
    items: [
      { to: '/settings/general', label: 'General', icon: SlidersHorizontal },
      { to: '/settings/security', label: 'Security', icon: ShieldCheck },
    ],
  },
]

function forType<T>(snapshot: AuthorizationSnapshot | undefined, personal: T, organization: T, family: T): T {
  const type = snapshot?.activeWorkspace.type
  return type === 'ORGANIZATION' ? organization : type === 'FAMILY' ? family : personal
}

/** Whether the snapshot lets the user open this tab. */
export function canOpen(tab: NavTab, snapshot: AuthorizationSnapshot | undefined): boolean {
  if (!snapshot) return !tab.permission && !tab.superAdmin
  if (tab.permission && !snapshot.permissions.includes(tab.permission)) return false
  if (tab.superAdmin && snapshot.platformRole !== 'SUPER_ADMIN') return false
  return true
}

/** The hub with only the tabs this user can open, or null when there are none. */
export function visibleHub(h: NavHub, snapshot: AuthorizationSnapshot | undefined): NavHub | null {
  const tabs = h.tabs.filter(t => canOpen(t, snapshot))
  return tabs.length ? { ...h, tabs } : null
}

/** Whether a path belongs to a tab: its own address, or a page below it. */
export function pathMatches(tab: NavTab, pathname: string): boolean {
  return [tab.to, ...(tab.also ?? [])].some(p => pathname === p || pathname.startsWith(`${p}/`))
}

/** The left menu for the active account, with what the role can't open left out. */
export function menuFor(snapshot: AuthorizationSnapshot | undefined): { main: NavHub[]; platform: NavHub | null } {
  const base = forType(snapshot, personalMenu, organizationMenu, familyMenu)
  return {
    main: base.map(h => visibleHub(h, snapshot)).filter((h): h is NavHub => h !== null),
    platform: visibleHub(HUBS.platform, snapshot),
  }
}

/** The Settings page's list for the active account. */
export function settingsFor(snapshot: AuthorizationSnapshot | undefined): SettingsGroup[] {
  const base = forType(snapshot, personalSettings, organizationSettings, familySettings)
  return base
    .map(g => ({ ...g, items: g.items.filter(i => canOpen(i, snapshot)) }))
    .filter(g => g.items.length > 0)
}
