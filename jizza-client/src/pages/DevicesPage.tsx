import { type FormEvent, type ReactNode, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound, MonitorSmartphone, Plus, Search, Terminal } from 'lucide-react'
import { devicesApi } from '@/api/devices'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Dialog } from '@/components/ui/Dialog'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState, LoadError } from '@/components/ui/EmptyState'
import { Switch } from '@/components/ui/Switch'
import { CreateAgentDialog } from '@/components/devices/CreateAgentDialog'
import { PLATFORM_LABEL, PlatformIcon, agentKind, hostOf, stamp } from '@/components/devices/platform'
import type { Device, DeviceCommand, DeviceEnrollmentToken } from '@/types'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

/** Keys kept outside secure hardware are easier to copy off the machine. */
const WEAK_KEY_STORAGE = new Set(['file'])

export function ago(iso: string | null, now = Date.now()): string {
  if (!iso) return 'never'
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return `${Math.floor(s / 86400)} d ago`
}

/** Matches the search box against name, host, tags and platform. */
export function matches(d: Device, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return [d.name, d.hostname ?? '', PLATFORM_LABEL[d.platform], agentKind(d), ...d.tags]
    .some(v => v.toLowerCase().includes(q))
}

/** Settings › Devices: the Jizza Agent apps on computers and phones, listed like installed agents. */
export function DevicesPage() {
  const canPair = usePermission(PERMISSIONS.DEVICE_PAIR)
  const canManage = usePermission(PERMISSIONS.DEVICE_MANAGE)
  const [creating, setCreating] = useState(false)
  const [query, setQuery] = useState('')
  const [viewing, setViewing] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null)
  const { data: devices, isLoading, isError, refetch } = useQuery({
    queryKey: ['devices'], queryFn: devicesApi.list, refetchInterval: 15_000,
  })
  const shown = (devices ?? []).filter(d => matches(d, query))
  const open = devices?.find(d => d.id === viewing) ?? null

  return (
    <div className="page">
      <PageHeader icon={MonitorSmartphone} title="Agents"
        description="Computers and phones running the Jizza Agent app. Workflows and Jizza can run steps on them, within your guardrails."
        actions={canPair && <Button size="sm" onClick={() => setCreating(true)}><Plus className="h-4 w-4" />Create agent</Button>} />
      <div className="grid max-w-6xl grid-cols-[minmax(0,1fr)] gap-4">
        {isLoading && <Card padding="lg"><Spinner /></Card>}
        {isError && <LoadError what="your agents" onRetry={() => refetch()} />}
        {devices && devices.length === 0 && (
          <EmptyState icon={MonitorSmartphone} title="No agents yet"
            description="Install the Jizza Agent app on a computer or phone, then connect it here with an installation key."
            action={canPair ? { label: 'Create agent', onClick: () => setCreating(true) } : undefined} />
        )}
        {devices && devices.length > 0 && (
          <Card padding="none">
            <div className="flex flex-wrap items-center gap-3 border-b border-gray-100 px-4 py-3 dark:border-gray-800">
              <div className="relative min-w-0 flex-1 sm:max-w-sm">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" aria-hidden="true" />
                <input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search agents here"
                  aria-label="Search agents"
                  className="w-full rounded-lg border border-gray-300 bg-white py-1.5 pl-9 pr-3 text-sm dark:border-gray-700 dark:bg-gray-900" />
              </div>
              {notice && (
                <p role={notice.error ? 'alert' : 'status'} className={`text-sm ${notice.error ? 'text-red-600' : 'text-gray-600 dark:text-gray-300'}`}>{notice.text}</p>
              )}
            </div>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-left text-sm">
                <thead className="text-xs font-medium text-gray-500">
                  <tr className="border-b border-gray-100 dark:border-gray-800">
                    <th className="px-4 py-2.5 font-medium">Agent name</th>
                    <th className="px-4 py-2.5 font-medium">Last updated on</th>
                    <th className="px-4 py-2.5 font-medium">Enable / Disable</th>
                    <th className="px-4 py-2.5 font-medium">Host</th>
                    <th className="px-4 py-2.5 font-medium">Status</th>
                    <th className="px-4 py-2.5 font-medium">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
                  {shown.map(d => <AgentRow key={d.id} device={d} onView={() => setViewing(d.id)} onNotice={setNotice} />)}
                  {shown.length === 0 && (
                    <tr><td colSpan={6} className="px-4 py-6 text-center text-sm text-gray-500">No agents match "{query}".</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </Card>
        )}
        {canManage && <EnrollmentTokensCard />}
      </div>
      <CreateAgentDialog open={creating} onClose={() => { setCreating(false); refetch() }} />
      {open && <AgentDetailsDialog device={open} onClose={() => setViewing(null)} />}
    </div>
  )
}

function StatusText({ device }: { device: Device }) {
  if (device.status === 'DISABLED') return <span className="text-amber-600 dark:text-amber-400">Disabled</span>
  if (!device.online) return <span className="text-gray-500" title={`Last seen ${ago(device.lastSeenAt)}`}>Offline</span>
  return <span className="text-green-600 dark:text-green-400">{device.state === 'busy' ? 'Busy' : 'Active'}</span>
}

function AgentRow({ device, onView, onNotice }: {
  device: Device; onView: () => void; onNotice: (n: { text: string; error?: boolean } | null) => void
}) {
  const qc = useQueryClient()
  const canUse = usePermission(PERMISSIONS.DEVICE_USE)
  const disabled = device.status === 'DISABLED'
  const toggle = useMutation({
    mutationFn: (on: boolean) => (on ? devicesApi.enable(device.id) : devicesApi.disable(device.id)),
    onSuccess: d => {
      onNotice({ text: d.status === 'DISABLED' ? `${d.name} is disabled. Its commands wait until it is enabled again.` : `${d.name} is enabled.` })
      qc.invalidateQueries({ queryKey: ['devices'] })
    },
    onError: e => onNotice({ text: errorText(e), error: true }),
  })
  const ping = useMutation({
    mutationFn: () => devicesApi.test(device.id),
    onSuccess: c => onNotice(c.status === 'SUCCEEDED'
      ? { text: `${device.name} answered in ${roundTrip(c)}.` }
      : { text: `${device.name}: ${explain(c)}`, error: c.status !== 'QUEUED' }),
    onError: e => onNotice({ text: errorText(e), error: true }),
  })

  return (
    <tr className="align-middle">
      <td className="px-4 py-3">
        <div className="font-medium text-gray-900 dark:text-gray-100">{device.name}</div>
        <span className="mt-1 inline-block rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">{agentKind(device)}</span>
      </td>
      <td className="whitespace-nowrap px-4 py-3 text-gray-700 dark:text-gray-300">{stamp(device.updatedAt ?? device.createdAt)}</td>
      <td className="px-4 py-3">
        <Switch checked={!disabled} label={`${disabled ? 'Enable' : 'Disable'} ${device.name}`}
          disabled={toggle.isPending} onChange={on => toggle.mutate(on)} />
      </td>
      <td className="px-4 py-3">
        <span className="flex items-center gap-2 text-gray-800 dark:text-gray-200" title={`${PLATFORM_LABEL[device.platform]}${device.osVersion ? ` ${device.osVersion}` : ''}`}>
          <PlatformIcon platform={device.platform} />
          <span className="truncate">{hostOf(device)}</span>
        </span>
      </td>
      <td className="px-4 py-3"><StatusText device={device} /></td>
      <td className="px-4 py-3">
        <div className="flex gap-2">
          {canUse && <Button size="sm" variant="secondary" loading={ping.isPending} disabled={disabled} onClick={() => ping.mutate()}>Ping</Button>}
          <Button size="sm" variant="ghost" onClick={onView}>View details</Button>
        </div>
      </td>
    </tr>
  )
}

function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(0,10rem)_minmax(0,1fr)] items-start gap-3 py-1.5 text-sm">
      <dt className="text-gray-500">{label}</dt>
      <dd className="min-w-0 break-words font-medium text-gray-900 dark:text-gray-100">{children}</dd>
    </div>
  )
}

function AgentDetailsDialog({ device, onClose }: { device: Device; onClose: () => void }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(device.name)
  const [tags, setTags] = useState(device.tags.join(', '))
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const refresh = () => qc.invalidateQueries({ queryKey: ['devices'] })
  const run = (fn: () => Promise<unknown>, done?: string) => {
    setError(''); setMessage('')
    fn().then(() => { if (done) setMessage(done); refresh() }).catch(e => setError(errorText(e)))
  }

  function save(e: FormEvent) {
    e.preventDefault()
    run(() => devicesApi.update(device.id, {
      name: name.trim(), tags: tags.split(',').map(t => t.trim()).filter(Boolean),
    }).then(() => setEditing(false)))
  }

  function unregister() {
    if (!window.confirm(`Unregister ${device.name}? It is disconnected permanently and cannot sign in again.`)) return
    setError('')
    devicesApi.remove(device.id).then(() => { refresh(); onClose() }).catch(e => setError(errorText(e)))
  }

  return (
    <Dialog open title="Agent details" onClose={onClose} size="lg">
      <dl className="divide-y divide-gray-50 dark:divide-gray-800/60">
        <DetailRow label="Agent name">{device.name}</DetailRow>
        <DetailRow label="Host name">
          <span className="flex items-center gap-2"><PlatformIcon platform={device.platform} className="h-4 w-4" />{hostOf(device)}</span>
        </DetailRow>
        <DetailRow label="Operating system">{PLATFORM_LABEL[device.platform]}{device.osVersion ? ` ${device.osVersion}` : ''}</DetailRow>
        <DetailRow label="Created by">{device.createdByEmail ?? (device.ownerUserId === null ? 'Enrollment token' : '—')}</DetailRow>
        <DetailRow label="Created at">{stamp(device.createdAt)}</DetailRow>
        <DetailRow label="Last seen">{device.online ? 'Now' : ago(device.lastSeenAt)}</DetailRow>
        <DetailRow label="Auto-update">
          <Switch checked={device.autoUpdate} label="Auto-update"
            onChange={on => run(() => devicesApi.update(device.id, { autoUpdate: on }),
              on ? 'The agent app will update itself when a new version is out.' : 'The agent app will not update itself.')} />
        </DetailRow>
        <DetailRow label="Version">{device.appVersion ?? '—'}</DetailRow>
        {device.tags.length > 0 && (
          <DetailRow label="Tags"><span className="flex flex-wrap gap-1">{device.tags.map(t => <Badge key={t}>{t}</Badge>)}</span></DetailRow>
        )}
        <DetailRow label="Agent unregister">
          <span className="font-normal text-gray-700 dark:text-gray-300">
            Unregister will disconnect the agent permanently. To proceed,{' '}
            <button type="button" className="text-brand-600 underline hover:text-brand-700 dark:text-brand-400" onClick={unregister}>click here</button>
          </span>
        </DetailRow>
      </dl>
      {WEAK_KEY_STORAGE.has(device.keyStorage) && (
        <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">
          Its key is kept in a file rather than secure hardware, so it is easier to copy off the machine.
        </p>
      )}
      {message && <p className="mt-3 text-sm text-gray-600 dark:text-gray-300" role="status">{message}</p>}
      {error && <p className="mt-3 text-sm text-red-600" role="alert">{error}</p>}

      <details className="mt-4 rounded-lg border border-gray-100 p-3 dark:border-gray-800">
        <summary className="cursor-pointer text-sm font-medium text-gray-700 dark:text-gray-200">More settings, commands and log</summary>
        <div className="mt-3 flex flex-col gap-4">
          {editing ? (
            <form onSubmit={save} className="grid gap-2 sm:grid-cols-2">
              <Input id={`${device.id}-name`} label="Name" value={name} onChange={e => setName(e.target.value)} />
              <Input id={`${device.id}-tags`} label="Tags" hint="Comma separated, e.g. office, finance" value={tags} onChange={e => setTags(e.target.value)} />
              <div className="flex gap-2 sm:col-span-2">
                <Button size="sm" type="submit">Save</Button>
                <Button size="sm" variant="ghost" type="button" onClick={() => setEditing(false)}>Cancel</Button>
              </div>
            </form>
          ) : (
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>Rename or tag</Button>
              {device.shellEnabled
                ? <Button size="sm" variant="secondary" onClick={() => run(() => devicesApi.update(device.id, { shellEnabled: false }), 'Shell actions are off.')}><Terminal className="h-4 w-4" />Turn shell off</Button>
                : <Button size="sm" variant="secondary" onClick={() => run(() => devicesApi.enableShell(device.id), 'Shell actions are on for this device.')}><Terminal className="h-4 w-4" />Allow shell</Button>}
            </div>
          )}
          <div>
            <h3 className="text-xs font-medium uppercase tracking-wide text-gray-500">What it can do</h3>
            <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">
              {device.actions.length ? device.actions.map(a => a.replace(/@\d+$/, '')).join(', ') : 'Nothing yet: its app has not sent what it can do.'}
            </p>
          </div>
          <CommandsList deviceId={device.id} />
          <LogsList deviceId={device.id} />
        </div>
      </details>

      <div className="mt-5 flex justify-center border-t border-gray-100 pt-4 dark:border-gray-800">
        <Button onClick={onClose}>Done</Button>
      </div>
    </Dialog>
  )
}

function roundTrip(c: DeviceCommand): string {
  if (!c.finishedAt) return 'a moment'
  const ms = new Date(c.finishedAt).getTime() - new Date(c.createdAt).getTime()
  return ms < 1000 ? `${Math.max(1, ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}

export function explain(c: DeviceCommand): string {
  switch (c.status) {
    case 'SUCCEEDED': return 'Done'
    case 'FAILED': return `It could not do it${c.error ? `: ${c.error}` : ''}`
    case 'REJECTED': return `It refused${c.error ? `: ${c.error}` : ''}`
    case 'EXPIRED': return c.errorCode === 'device_offline' ? 'It was not reachable in time' : 'It did not finish in time'
    case 'CANCELLED': return 'Cancelled'
    case 'QUEUED': return 'It is not connected right now; the command waits until it is.'
    default: return 'Waiting for it to finish'
  }
}

const STATUS_VARIANT: Record<string, 'default' | 'success' | 'warning' | 'danger' | 'info'> = {
  SUCCEEDED: 'success', FAILED: 'danger', REJECTED: 'danger', EXPIRED: 'warning', CANCELLED: 'default',
  QUEUED: 'info', DISPATCHED: 'info', ACCEPTED: 'info', RUNNING: 'info',
}

function CommandsList({ deviceId }: { deviceId: string }) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['devices', deviceId, 'commands'], queryFn: () => devicesApi.commands(deviceId), refetchInterval: 5000 })
  return (
    <div>
      <h3 className="text-xs font-medium uppercase tracking-wide text-gray-500">Recent commands</h3>
      {!data?.length && <p className="mt-1 text-sm text-gray-500">None yet.</p>}
      <ul className="mt-1 divide-y divide-gray-100 dark:divide-gray-800">
        {data?.map(c => (
          <li key={c.id} className="flex flex-wrap items-center gap-2 py-1.5 text-sm">
            <Badge variant={STATUS_VARIANT[c.status]}>{c.status.toLowerCase()}</Badge>
            <span className="font-mono text-xs">{c.action}</span>
            <span className="text-xs text-gray-500">{ago(c.createdAt)}</span>
            {(c.error || c.progress) && <span className="min-w-0 flex-1 truncate text-xs text-gray-500">{c.error ?? c.progress}</span>}
            {!['SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(c.status) && (
              <Button size="sm" variant="ghost" onClick={() => devicesApi.cancelCommand(deviceId, c.id)
                .then(() => qc.invalidateQueries({ queryKey: ['devices', deviceId, 'commands'] }))}>Cancel</Button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

function LogsList({ deviceId }: { deviceId: string }) {
  const { data } = useQuery({ queryKey: ['devices', deviceId, 'logs'], queryFn: () => devicesApi.logs(deviceId) })
  if (!data?.length) return null
  return (
    <details>
      <summary className="cursor-pointer text-xs font-medium uppercase tracking-wide text-gray-500">App log ({data.length})</summary>
      <pre className="mt-2 max-h-60 overflow-auto rounded-lg bg-gray-50 p-2 text-[11px] leading-relaxed dark:bg-gray-900">
        {data.map(l => `${l.ts}  ${l.level.toUpperCase().padEnd(5)}  ${l.message}`).join('\n')}
      </pre>
    </details>
  )
}

function EnrollmentTokensCard() {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['devices', 'enrollment-tokens'], queryFn: devicesApi.enrollmentTokens })
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [uses, setUses] = useState('10')
  const [days, setDays] = useState('7')
  const [tags, setTags] = useState('')
  const [made, setMade] = useState<DeviceEnrollmentToken | null>(null)
  const [error, setError] = useState('')
  const refresh = () => qc.invalidateQueries({ queryKey: ['devices', 'enrollment-tokens'] })

  async function create(e: FormEvent) {
    e.preventDefault()
    setError('')
    try {
      setMade(await devicesApi.createEnrollmentToken({
        name: name.trim(), uses: Number(uses), validDays: Number(days),
        tags: tags.split(',').map(t => t.trim()).filter(Boolean),
      }))
      setCreating(false); setName(''); refresh()
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <Card padding="lg">
      <div className="flex flex-wrap items-center gap-2">
        <KeyRound className="h-4 w-4 text-gray-500" />
        <h2 className="flex-1 font-semibold text-gray-900 dark:text-gray-100">Enrollment tokens</h2>
        {!creating && <Button size="sm" variant="secondary" onClick={() => setCreating(true)}>New token</Button>}
      </div>
      <p className="mt-1 text-sm text-gray-500">For machines nobody sits at, such as servers or shared office PCs. They join as shared devices of this account.</p>
      {made?.token && (
        <div className="mt-3 rounded-lg bg-amber-50 p-3 text-sm dark:bg-amber-950/40">
          <p className="font-medium">Copy this token now. It is not shown again.</p>
          <code className="mt-1 block break-all font-mono text-xs">{made.token}</code>
        </div>
      )}
      {creating && (
        <form onSubmit={create} className="mt-3 grid gap-2 sm:grid-cols-2">
          <Input label="Name" value={name} onChange={e => setName(e.target.value)} placeholder="Build servers" />
          <Input label="Tags" value={tags} onChange={e => setTags(e.target.value)} placeholder="ci, linux" />
          <Input label="Machines it can enroll" type="number" min={1} max={1000} value={uses} onChange={e => setUses(e.target.value)} />
          <Input label="Valid for (days)" type="number" min={1} max={90} value={days} onChange={e => setDays(e.target.value)} />
          {error && <p className="text-sm text-red-600 sm:col-span-2">{error}</p>}
          <div className="flex gap-2 sm:col-span-2">
            <Button size="sm" type="submit" disabled={!name.trim()}>Create</Button>
            <Button size="sm" variant="ghost" type="button" onClick={() => setCreating(false)}>Cancel</Button>
          </div>
        </form>
      )}
      <ul className="mt-3 divide-y divide-gray-100 dark:divide-gray-800">
        {data?.map(t => (
          <li key={t.id} className="flex flex-wrap items-center gap-2 py-1.5 text-sm">
            <span className="font-medium">{t.name}</span>
            <span className="text-xs text-gray-500">{t.revoked ? 'revoked' : `${t.usesLeft} left · until ${new Date(t.expiresAt).toLocaleDateString()}`}</span>
            <span className="flex-1" />
            {!t.revoked && <Button size="sm" variant="ghost" onClick={() => devicesApi.revokeEnrollmentToken(t.id).then(refresh)}>Revoke</Button>}
          </li>
        ))}
      </ul>
    </Card>
  )
}
