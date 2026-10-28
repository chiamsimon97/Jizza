import { type FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import QRCode from 'qrcode'
import {
  Apple, Globe2, KeyRound, Laptop, Monitor, MonitorSmartphone, Plus, Smartphone, Terminal, Trash2, Zap,
} from 'lucide-react'
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
import type { Device, DeviceCommand, DeviceEnrollmentToken, DevicePairingCode, DevicePlatform } from '@/types'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

const PLATFORM_LABEL: Record<DevicePlatform, string> = {
  WINDOWS: 'Windows', MACOS: 'macOS', LINUX: 'Linux', ANDROID: 'Android', IOS: 'iPhone',
  BROWSER: 'Browser', OTHER: 'Other',
}

const PLATFORM_ICON: Record<DevicePlatform, typeof Laptop> = {
  WINDOWS: Monitor, MACOS: Apple, LINUX: Terminal, ANDROID: Smartphone, IOS: Smartphone,
  BROWSER: Globe2, OTHER: Laptop,
}

/** Keys kept outside secure hardware are easier to copy off the machine. */
const WEAK_KEY_STORAGE = new Set(['file', 'browser'])

export function ago(iso: string | null, now = Date.now()): string {
  if (!iso) return 'never'
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return `${Math.floor(s / 86400)} d ago`
}

/** Settings › Devices: computers and phones running the Jizza Agent app. */
export function DevicesPage() {
  const canPair = usePermission(PERMISSIONS.DEVICE_PAIR)
  const canManage = usePermission(PERMISSIONS.DEVICE_MANAGE)
  const [pairing, setPairing] = useState(false)
  const { data: devices, isLoading, isError, refetch } = useQuery({
    queryKey: ['devices'], queryFn: devicesApi.list, refetchInterval: 15_000,
  })

  return (
    <div className="page">
      <PageHeader icon={MonitorSmartphone} title="Devices"
        description="Computers and phones running the Jizza Agent app. Workflows and Jizza can run steps on them, within your guardrails."
        actions={canPair && <Button size="sm" onClick={() => setPairing(true)}><Plus className="h-4 w-4" />Pair a device</Button>} />
      <div className="grid max-w-3xl grid-cols-[minmax(0,1fr)] gap-4">
        {isLoading && <Card padding="lg"><Spinner /></Card>}
        {isError && <LoadError what="your devices" onRetry={() => refetch()} />}
        {devices && devices.length === 0 && (
          <EmptyState icon={MonitorSmartphone} title="No devices yet"
            description="Install the Jizza Agent app on a computer or phone, then pair it here with a code."
            action={canPair ? { label: 'Pair a device', onClick: () => setPairing(true) } : undefined} />
        )}
        {devices?.map(d => <DeviceCard key={d.id} device={d} />)}
        {canManage && <EnrollmentTokensCard />}
      </div>
      <PairDialog open={pairing} onClose={() => { setPairing(false); refetch() }} />
    </div>
  )
}

function PairDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [code, setCode] = useState<DevicePairingCode | null>(null)
  const [qr, setQr] = useState('')
  const [error, setError] = useState('')
  const [now, setNow] = useState(Date.now())
  const [kind, setKind] = useState<'device' | 'browser'>('device')

  useEffect(() => {
    if (!open) { setCode(null); setQr(''); setError(''); setKind('device'); return }
    let cancelled = false
    devicesApi.pair()
      .then(c => { if (!cancelled) setCode(c) })
      .catch(e => { if (!cancelled) setError(errorText(e)) })
    return () => { cancelled = true }
  }, [open])

  useEffect(() => {
    if (!code) return
    QRCode.toDataURL(code.qrPayload, { margin: 1, width: 200, errorCorrectionLevel: 'M' })
      .then(setQr).catch(() => setQr(''))
    const t = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(t)
  }, [code])

  const left = code ? Math.max(0, Math.round((new Date(code.expiresAt).getTime() - now) / 1000)) : 0
  return (
    <Dialog open={open} title="Pair a device" onClose={onClose}
      description={kind === 'browser'
        ? 'Load the browser agent, then use this installation key.'
        : 'Open the Jizza Agent app and scan this code, or type the six digits.'}>
      <div className="mb-4 flex gap-2" role="tablist" aria-label="Device type">
        <Button size="sm" variant={kind === 'device' ? 'primary' : 'secondary'}
          onClick={() => setKind('device')} role="tab" aria-selected={kind === 'device'}>
          Computer or phone
        </Button>
        <Button size="sm" variant={kind === 'browser' ? 'primary' : 'secondary'}
          onClick={() => setKind('browser')} role="tab" aria-selected={kind === 'browser'}>
          <Globe2 className="h-4 w-4" />Browser
        </Button>
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {!code && !error && <Spinner />}
      {code && (
        <div className="flex flex-col items-center gap-3">
          {kind === 'device' && qr && <img src={qr} alt="Pairing QR code" className="h-48 w-48 rounded-lg bg-white p-1" />}
          {kind === 'browser' && (
            <div className="w-full rounded-lg border border-gray-200 p-3 text-sm dark:border-gray-700">
              <p className="font-medium text-gray-900 dark:text-gray-100">Browser extension · Coming soon</p>
              <p className="mt-1 text-gray-500">
                Load unpacked for testing in Chrome, open the extension, and enter the installation key below.
              </p>
            </div>
          )}
          <p className="font-mono text-3xl tracking-[0.3em] text-gray-900 dark:text-gray-100" aria-label="Pairing code">{code.code}</p>
          <Button variant="secondary" size="sm"
            onClick={() => navigator.clipboard.writeText(code.qrPayload)}>Copy installation key</Button>
          <p className="text-xs text-gray-500">{left > 0 ? `Works once, for ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} more.` : 'This code has expired. Close and pair again.'}</p>
          <Button variant="secondary" size="sm" onClick={onClose}>Done</Button>
        </div>
      )}
    </Dialog>
  )
}

function DeviceCard({ device }: { device: Device }) {
  const qc = useQueryClient()
  const canUse = usePermission(PERMISSIONS.DEVICE_USE)
  const [open, setOpen] = useState(false)
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
  const test = useMutation({
    mutationFn: () => devicesApi.test(device.id),
    onSuccess: c => { setError(''); setMessage(c.status === 'SUCCEEDED' ? `It answered in ${roundTrip(c)}.` : explain(c)) },
    onError: e => setError(errorText(e)),
  })
  const Icon = PLATFORM_ICON[device.platform] ?? Laptop
  const disabled = device.status === 'DISABLED'

  function save(e: FormEvent) {
    e.preventDefault()
    run(() => devicesApi.update(device.id, {
      name: name.trim(), tags: tags.split(',').map(t => t.trim()).filter(Boolean),
    }).then(() => setEditing(false)))
  }

  return (
    <Card padding="lg">
      <div className="flex flex-wrap items-start gap-3">
        <div className="grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-200">
          <Icon className="h-5 w-5" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-semibold text-gray-900 dark:text-gray-100">{device.name}</h2>
            {disabled ? <Badge variant="warning">Disabled</Badge>
              : device.online ? <Badge variant="success">{device.state === 'busy' ? 'Busy' : 'Online'}</Badge>
              : <Badge>Offline</Badge>}
            {device.ownerUserId === null && <Badge variant="info">Shared</Badge>}
            {device.shellEnabled && <Badge variant="danger">Shell on</Badge>}
          </div>
          <p className="mt-0.5 text-sm text-gray-500">
            {PLATFORM_LABEL[device.platform]}{device.osVersion ? ` ${device.osVersion}` : ''}
            {device.appVersion ? ` · app ${device.appVersion}` : ''} · last seen {device.online ? 'now' : ago(device.lastSeenAt)}
          </p>
          {device.tags.length > 0 && (
            <div className="mt-1 flex flex-wrap gap-1">{device.tags.map(t => <Badge key={t}>{t}</Badge>)}</div>
          )}
          {WEAK_KEY_STORAGE.has(device.keyStorage) && (
            <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
              Its key is kept outside secure hardware, so it is easier to copy off the machine.
            </p>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {canUse && <Button size="sm" variant="secondary" loading={test.isPending} disabled={disabled} onClick={() => test.mutate()}><Zap className="h-4 w-4" />Test</Button>}
          <Button size="sm" variant="ghost" onClick={() => setOpen(o => !o)}>{open ? 'Hide' : 'Details'}</Button>
        </div>
      </div>
      {message && <p className="mt-3 text-sm text-gray-600 dark:text-gray-300" role="status">{message}</p>}
      {error && <p className="mt-3 text-sm text-red-600" role="alert">{error}</p>}

      {open && (
        <div className="mt-4 flex flex-col gap-4 border-t border-gray-100 pt-4 dark:border-gray-800">
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
              {disabled
                ? <Button size="sm" variant="secondary" onClick={() => run(() => devicesApi.enable(device.id), 'Enabled.')}>Enable</Button>
                : <Button size="sm" variant="secondary" onClick={() => run(() => devicesApi.disable(device.id), 'Disabled. Its commands wait until it is enabled again.')}>Disable</Button>}
              <Button size="sm" variant="danger" onClick={() => {
                if (window.confirm(`Remove ${device.name}? It will be signed out and cannot sign in again.`)) run(() => devicesApi.remove(device.id))
              }}><Trash2 className="h-4 w-4" />Remove</Button>
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
      )}
    </Card>
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
