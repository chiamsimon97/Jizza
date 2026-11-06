import { Apple, Globe2, Laptop, Smartphone } from 'lucide-react'
import type { Device, DevicePlatform } from '@/types'

export const PLATFORM_LABEL: Record<DevicePlatform, string> = {
  WINDOWS: 'Windows', MACOS: 'macOS', LINUX: 'Linux', ANDROID: 'Android', IOS: 'iPhone',
  BROWSER: 'Browser', OTHER: 'Other',
}

type IconProps = { className?: string }

export function WindowsIcon({ className = 'h-5 w-5' }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path fill="#00a4ef" d="M2 4.3 10 3.2v7.7H2zm0 15.4 8 1.1v-7.6H2zm9 1.2 11 1.6V13.2H11zM11 3.1v7.8h11V1.5z" />
    </svg>
  )
}

export function LinuxIcon({ className = 'h-5 w-5' }: IconProps) {
  // A simple penguin: body, belly, eyes, beak and feet.
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <ellipse cx="12" cy="13" rx="6.5" ry="9" fill="#1f2937" />
      <ellipse cx="12" cy="15" rx="4.3" ry="6" fill="#f9fafb" />
      <circle cx="10.2" cy="8" r="1.1" fill="#f9fafb" /><circle cx="13.8" cy="8" r="1.1" fill="#f9fafb" />
      <circle cx="10.4" cy="8.2" r="0.5" fill="#111827" /><circle cx="13.6" cy="8.2" r="0.5" fill="#111827" />
      <path d="M10.6 10.2h2.8L12 12z" fill="#f59e0b" />
      <ellipse cx="8.6" cy="21.6" rx="2.4" ry="1.1" fill="#f59e0b" /><ellipse cx="15.4" cy="21.6" rx="2.4" ry="1.1" fill="#f59e0b" />
    </svg>
  )
}

export function PlatformIcon({ platform, className = 'h-5 w-5' }: { platform: DevicePlatform } & IconProps) {
  switch (platform) {
    case 'WINDOWS': return <WindowsIcon className={className} />
    case 'MACOS': return <Apple className={className} aria-hidden="true" />
    case 'LINUX': return <LinuxIcon className={className} />
    case 'ANDROID': case 'IOS': return <Smartphone className={className} aria-hidden="true" />
    case 'BROWSER': return <Globe2 className={className} aria-hidden="true" />
    default: return <Laptop className={className} aria-hidden="true" />
  }
}

/** What kind of agent it is, shown as a tag under its name. */
export function agentKind(d: Pick<Device, 'platform' | 'ownerUserId'>): string {
  if (d.ownerUserId === null) return 'Shared agent'
  if (d.platform === 'BROWSER') return 'Browser agent'
  return d.platform === 'ANDROID' || d.platform === 'IOS' ? 'Phone agent' : 'Desktop agent'
}

/** The machine's host name, or the closest thing an older app sent. */
export function hostOf(d: Pick<Device, 'hostname' | 'name'>): string {
  return d.hostname?.trim() || d.name
}

export type InstallOs = 'windows' | 'macos' | 'linux' | 'android' | 'browser'

/** The operating system this browser runs on, to open the right tab first. */
export function detectOs(nav: Pick<Navigator, 'userAgent'> & { userAgentData?: { platform?: string } } = navigator): InstallOs {
  const p = `${nav.userAgentData?.platform ?? ''} ${nav.userAgent}`
  if (/Android/i.test(p)) return 'android'
  if (/Mac|iPhone|iPad/i.test(p)) return 'macos'
  if (/Win/i.test(p)) return 'windows'
  if (/Linux|X11|CrOS/i.test(p)) return 'linux'
  return 'windows'
}

export type MacArch = 'arm64' | 'x64'

interface HighEntropyNavigator {
  userAgentData?: { getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string }> }
}

/**
 * Whether this Mac has Apple silicon or an Intel chip. Chrome and Edge say so when asked; Safari
 * and Firefox don't, so the graphics chip's name decides there. Null when neither tells.
 */
export async function detectMacArch(nav: HighEntropyNavigator = navigator as HighEntropyNavigator,
  renderer: () => string | null = webglRenderer): Promise<MacArch | null> {
  try {
    const v = await nav.userAgentData?.getHighEntropyValues?.(['architecture'])
    if (v?.architecture === 'arm') return 'arm64'
    if (v?.architecture === 'x86') return 'x64'
  } catch { /* fall through to the graphics chip */ }
  const r = renderer() ?? ''
  if (/Apple (M\d|GPU)/i.test(r)) return 'arm64'
  if (/Intel|AMD|Radeon/i.test(r)) return 'x64'
  return null
}

function webglRenderer(): string | null {
  try {
    const gl = document.createElement('canvas').getContext('webgl')
    if (!gl) return null
    const ext = gl.getExtension('WEBGL_debug_renderer_info')
    return String(gl.getParameter(ext ? ext.UNMASKED_RENDERER_WEBGL : gl.RENDERER))
  } catch {
    return null
  }
}

/** "Oct 28, 2026 12:03", in the person's own time zone. */
export function stamp(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
  return `${date} ${time}`
}
