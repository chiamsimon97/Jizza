import type { ReactNode } from 'react'
import { Badge } from '@/components/ui/Badge'
import type { AuthScheme, ServiceInfo } from '@/types'

export const AUTH_LABELS: Record<AuthScheme, string> = {
  API_KEY: 'API key',
  OAUTH2: 'OAuth 2.0',
  BASIC: 'Basic auth',
  CUSTOM: 'Custom (headers via variables)',
  NO_AUTH: 'No authentication',
}

export function ServiceStatusBadge({ service }: { service: ServiceInfo }) {
  return service.versionStatus === 'live'
    ? <Badge variant="success">Live v{service.liveVersion}</Badge>
    : <Badge variant="default">Draft</Badge>
}

/** Machine names: lower-case letters, digits and underscores, starting with a letter. */
export function toMachineName(label: string) {
  const name = label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')
  return (/^[a-z]/.test(name) ? name : `x_${name}`).slice(0, 48)
}

export function SectionHeader({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-end gap-3">
      <div className="min-w-0 flex-1">
        <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
        {description && <p className="text-sm text-gray-500 dark:text-gray-400">{description}</p>}
      </div>
      {action}
    </div>
  )
}

/** A compact table shell that scrolls sideways on small screens instead of squashing. */
export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-gray-200 dark:border-gray-800">
      <table className="w-full min-w-[480px] text-left text-sm">
        <thead className="bg-gray-50 text-xs uppercase tracking-wider text-gray-500 dark:bg-gray-900 dark:text-gray-400">
          <tr>{head.map(h => <th key={h} className="px-4 py-2.5 font-medium">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100 bg-white dark:divide-gray-800 dark:bg-gray-950">{children}</tbody>
      </table>
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="rounded-xl border border-dashed border-gray-200 p-6 text-center text-sm text-gray-500 dark:border-gray-800 dark:text-gray-400">{children}</p>
}

export function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-3 text-xs text-gray-800 dark:bg-gray-950 dark:text-gray-200">
      {typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
    </pre>
  )
}

export function errorText(e: unknown) {
  return e instanceof Error ? e.message : e ? String(e) : ''
}
