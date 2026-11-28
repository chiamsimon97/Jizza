import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AlertTriangle, CheckCircle2, CirclePause, Inbox, KeyRound, LogIn, Mail, Pencil, RefreshCw, Smartphone, Trash2, Upload, XCircle } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { DeviceDataType, HealthConnection, MailCheckResult, MailServer, MailSource, MailSourceInput, MailStatus } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText, formatWhen, useVitaPeople } from '../data'
import { splitPeople } from '../people'
import { vitaKeys } from '../queryKeys'

export const MAIL_STATUS: Record<MailStatus, { words: string; tone: 'ok' | 'warn' | 'bad' | 'info' | 'neutral'; Icon: typeof CheckCircle2 }> = {
  to_review: { words: 'A report is waiting for your review', tone: 'warn', Icon: Inbox },
  nothing_new: { words: 'Nothing new', tone: 'ok', Icon: CheckCircle2 },
  needs_sign_in: { words: 'The mailbox needs signing in again', tone: 'bad', Icon: LogIn },
  needs_password: { words: 'The report needs its password', tone: 'warn', Icon: KeyRound },
  failed: { words: 'The last check failed', tone: 'bad', Icon: XCircle },
}

function StatusPill({ s }: { s: MailStatus | null }) {
  if (!s) return <Pill>Not checked yet</Pill>
  const m = MAIL_STATUS[s]
  return <Pill tone={m.tone}><m.Icon className="h-3 w-3" aria-hidden />{m.words}</Pill>
}

/**
 * Connect (7.17): Upload, and lab reports pulled from a connected mailbox: pick the mailbox, name the
 * sender, see the last check and its status. A report found is read into a draft and waits in
 * Documents for review, like an upload. Phones and watches connected through Health Connect or Apple Health
 * are listed with what they send, and can be stopped, with the offer to delete what they added.
 */
export function Connect({ personId, readOnly, onOpen, onReview }: {
  personId: string
  readOnly: boolean
  onOpen: (view: string) => void
  onReview: (documentId: string) => void
}) {
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Connect" sub="Where reports and readings come from" />
      <Panel title={<><Upload className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Upload</>}>
        <p className="text-sm text-gray-700 dark:text-gray-200">Upload a PDF or a photo of a report or a prescription. Vita reads it and shows what it found before anything is saved.</p>
        <div><Button size="sm" variant="secondary" onClick={() => onOpen('documents')}>Go to Documents</Button></div>
      </Panel>
      <Mailbox personId={personId} readOnly={readOnly} onReview={onReview} />
      <Devices personId={personId} onOpen={onOpen} />
    </div>
  )
}

function Mailbox({ personId, readOnly, onReview }: { personId: string; readOnly: boolean; onReview: (documentId: string) => void }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.mailSources, queryFn: vitaApi.mailSources })
  const [editing, setEditing] = useState<MailSource | 'new' | null>(null)
  const [results, setResults] = useState<Record<string, MailCheckResult>>({})
  const refresh = () => { qc.invalidateQueries({ queryKey: vitaKeys.mailSources }); qc.invalidateQueries({ queryKey: vitaKeys.all }) }
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeMailSource(id), onSuccess: refresh })
  const toggle = useMutation({ mutationFn: (s: MailSource) => vitaApi.updateMailSource(s.id, { enabled: !s.enabled }), onSuccess: refresh })
  const check = useMutation({
    mutationFn: (id: string) => vitaApi.checkMailSource(id),
    onSuccess: (out, id) => { setResults(r => ({ ...r, [id]: out })); refresh() },
  })
  const servers = data?.servers ?? []
  const sources = data?.sources ?? []
  return (
    <Panel title={<><Mail className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Mailbox</>}
      action={!readOnly && servers.length > 0 && !editing && <Button size="sm" variant="secondary" onClick={() => setEditing('new')}>Add a sender</Button>}>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Vita looks only at mail from the sender you name, reads a report it finds, and keeps it in Documents for your review.</p>
      {isLoading && <SkeletonRows rows={2} />}
      {error && <LoadFailed text="Could not load the mailbox sources." onRetry={() => refetch()} />}
      {data && !servers.length && (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          No mailbox is connected. Connect Gmail or Outlook under <Link to="/connectors" className="text-brand-700 underline dark:text-brand-300">Connectors</Link>, then come back to name the lab that sends the reports.
        </p>
      )}
      {editing && <SourceForm start={editing === 'new' ? null : editing} servers={servers} personId={personId} onDone={() => { setEditing(null); refresh() }} />}
      {data && servers.length > 0 && !sources.length && !editing && <p className="text-sm text-gray-500 dark:text-gray-400">No senders yet. Add the lab that emails the reports.</p>}
      {sources.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {sources.map(s => {
            const r = results[s.id]
            return (
              <li key={s.id} className={`flex flex-col gap-1.5 py-2.5 ${s.enabled ? '' : 'opacity-60'}`}>
                <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
                  <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                      {s.sender}{!s.enabled && <Pill>Paused</Pill>}{s.hasPassword && <Pill><KeyRound className="h-3 w-3" aria-hidden />Password kept</Pill>}
                    </p>
                    <p className="text-xs text-gray-600 dark:text-gray-300">
                      {[s.serverName ?? 'Mailbox', `for ${s.personName ?? 'a person'}`, s.subject ? `subject has “${s.subject}”` : null, s.lastCheckedAt ? `last checked ${formatWhen(s.lastCheckedAt)}` : 'never checked'].filter(Boolean).join(' · ')}
                    </p>
                    <span className="flex flex-wrap items-center gap-2">
                      <StatusPill s={s.signedIn ? s.lastStatus : 'needs_sign_in'} />
                      {(s.lastStatus === 'to_review' || s.lastStatus === 'needs_password') && s.lastDocumentId && <Button size="sm" variant="ghost" onClick={() => onReview(s.lastDocumentId as string)}>Review it</Button>}
                    </span>
                  </div>
                  {!readOnly && (
                    <span className="flex flex-wrap gap-1">
                      <Button size="sm" variant="secondary" disabled={!s.enabled || !s.signedIn} loading={check.isPending && check.variables === s.id} onClick={() => check.mutate(s.id)}>
                        <RefreshCw className="h-3.5 w-3.5" aria-hidden />Check now
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => toggle.mutate(s)}>{s.enabled ? 'Pause' : 'Resume'}</Button>
                      <Button size="sm" variant="ghost" aria-label={`Change ${s.sender}`} onClick={() => setEditing(s)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                      <Button size="sm" variant="ghost" aria-label={`Remove ${s.sender}`} onClick={() => remove.mutate(s.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                    </span>
                  )}
                </div>
                {!s.signedIn && <p className="flex items-center gap-1 text-xs text-red-700 dark:text-red-300"><AlertTriangle className="h-3 w-3" aria-hidden />Sign in to {s.serverName ?? 'the mailbox'} again under <Link to="/connectors" className="underline">Connectors</Link>.</p>}
                {r && (
                  <p role="status" className="flex flex-wrap items-center gap-2 text-xs text-gray-700 dark:text-gray-200">
                    {r.message}
                    {r.documentId && <Button size="sm" onClick={() => onReview(r.documentId as string)}>Review what Vita read</Button>}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {check.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(check.error, 'Could not check the mailbox.')}</p>}
      {(remove.error || toggle.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error ?? toggle.error, 'Could not change the source.')}</p>}
    </Panel>
  )
}

function SourceForm({ start, servers, personId, onDone }: { start: MailSource | null; servers: MailServer[]; personId: string; onDone: () => void }) {
  const people = useVitaPeople()
  const open = splitPeople(people.data ?? []).open
  const [v, setV] = useState({
    serverId: start?.serverId ?? servers.find(s => s.signedIn)?.serverId ?? servers[0]?.serverId ?? '',
    personId: start?.personId ?? personId, sender: start?.sender ?? '', subject: start?.subject ?? '', password: '', locked: false,
  })
  const body = (): MailSourceInput => {
    const b: MailSourceInput = { personId: v.personId, sender: v.sender.trim(), subject: v.subject.trim() || null }
    if (!start) b.serverId = v.serverId
    if (v.password) b.password = v.password
    return b
  }
  const save = useMutation({ mutationFn: () => (start ? vitaApi.updateMailSource(start.id, body()) : vitaApi.addMailSource(body())), onSuccess: onDone })
  return (
    <form aria-label={start ? 'Change the sender' : 'Add a sender'} className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-2">
        {!start && (
          <SelectField label="Mailbox" value={v.serverId} onChange={serverId => setV({ ...v, serverId })}
            options={servers.map(s => ({ value: s.serverId, label: `${s.name}${s.signedIn ? '' : ' (needs signing in)'}` }))} />
        )}
        <SelectField label="Reports are for" value={v.personId} onChange={p => setV({ ...v, personId: p })} options={open.map(p => ({ value: p.id, label: p.displayName }))} />
        <TextField label="Sender (address or name)" value={v.sender} maxLength={200} placeholder="reports@lab.example" onChange={sender => setV({ ...v, sender })} />
        <TextField label="Subject contains (optional)" value={v.subject} maxLength={200} onChange={subject => setV({ ...v, subject })} />
      </div>
      <CheckField label={start?.hasPassword ? 'Change the PDF password' : 'The lab locks its PDFs with a password'} checked={v.locked} onChange={locked => setV({ ...v, locked, password: '' })} />
      {v.locked && (
        <TextField label="PDF password" type="password" value={v.password} maxLength={64} onChange={password => setV({ ...v, password })}
          hint="Kept encrypted and never shown again. Often the date of birth." />
      )}
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!v.sender.trim() || !v.personId || (!start && !v.serverId)} loading={save.isPending}>Save</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the sender.')}</p>}
    </form>
  )
}

export const DATA_TYPE_WORDS: Record<DeviceDataType, string> = {
  steps: 'Steps', sleep: 'Sleep', workout: 'Workouts', resting_heart_rate: 'Resting heart rate', bp: 'Blood pressure',
  glucose: 'Sugar', weight: 'Weight', spo2: 'Oxygen (SpO2)', temperature: 'Temperature',
}
const SOURCE_NAMES: Record<string, string> = { health_connect: 'Health Connect', apple_health: 'Apple Health' }
const readings = (n: number) => `${n.toLocaleString('en-IN')} ${n === 1 ? 'reading' : 'readings'}`

/**
 * Phones and watches (7.17, 5.11): each connection with its phone, status, last sync and the data
 * types it sends, each with what it added. Turning a type off or removing the connection stops
 * sync at once; then the screen offers to delete what it added, which is asked separately.
 */
function Devices({ personId, onOpen }: { personId: string; onOpen: (view: string) => void }) {
  const list = useQuery({ queryKey: vitaKeys.connectors(personId), queryFn: () => vitaApi.connectors(personId) })
  const live = (list.data ?? []).filter(c => !c.revokedAt)
  const ended = (list.data ?? []).filter(c => c.revokedAt && c.rowsWritten > 0)
  return (
    <Panel title={<><Smartphone className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Phones and watches</>}>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">
        Connect in the Jizza app on the phone: Health Connect on Android, Apple Health on an iPhone. The app only reads, and sends only the data you turn on.
      </p>
      {list.isLoading && <SkeletonRows rows={2} />}
      {list.error && <LoadFailed text="Could not load the connections." onRetry={() => list.refetch()} />}
      {list.data && !live.length && !ended.length && <p className="text-sm text-gray-600 dark:text-gray-300">No phone or watch is connected for this person.</p>}
      {live.map(c => <Connection key={c.id} personId={personId} c={c} />)}
      {ended.map(c => <Connection key={c.id} personId={personId} c={c} />)}
      {live.length > 0 && <div><Button size="sm" variant="ghost" onClick={() => onOpen('activity')}>See activity and sleep</Button></div>}
    </Panel>
  )
}

function Connection({ personId, c }: { personId: string; c: HealthConnection }) {
  const qc = useQueryClient()
  const [offer, setOffer] = useState<{ types: DeviceDataType[] | null; rows: number } | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const refresh = () => { qc.invalidateQueries({ queryKey: vitaKeys.connectors(personId) }); qc.invalidateQueries({ queryKey: ['vita', personId, 'measurements'] }) }
  const count = (types: DeviceDataType[] | null) => (types ?? c.dataTypes).reduce((a, t) => a + (c.rowsByType[t] ?? 0), 0)
  const stop = useMutation({
    mutationFn: (types: DeviceDataType[] | null) => vitaApi.revokeConnector(personId, c.id, { types: types ?? undefined }),
    onSuccess: (r, types) => {
      const rows = types ? types.reduce((a, t) => a + (r.rowsByType[t] ?? 0), 0) : r.rowsWritten
      setDone(types ? `${types.map(t => DATA_TYPE_WORDS[t]).join(', ')} stopped.` : 'Connection removed. Nothing more is sent through it.')
      setOffer(rows > 0 ? { types, rows } : null)
      refresh()
    },
  })
  const purge = useMutation({
    mutationFn: (types: DeviceDataType[] | null) => vitaApi.revokeConnector(personId, c.id, { types: types ?? undefined, deleteData: true }),
    onSuccess: r => { setOffer(null); setDone(`Deleted ${readings(r.rowsDeleted ?? 0)}.`); refresh() },
  })
  const revoked = !!c.revokedAt
  const name = c.deviceName ?? (c.deviceId ? 'A phone' : 'Added on the web')
  const types = revoked ? (Object.keys(c.rowsByType) as DeviceDataType[]) : c.dataTypes
  return (
    <section aria-label={`${name}, ${SOURCE_NAMES[c.source] ?? c.source}`} className="flex flex-col gap-2 rounded-xl border border-gray-200 p-3 dark:border-gray-700">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{name} <span className="font-normal text-gray-500 dark:text-gray-400">· {SOURCE_NAMES[c.source] ?? c.source}</span></p>
        {revoked
          ? <Pill><CirclePause className="h-3 w-3" aria-hidden />Stopped {formatWhen(c.revokedAt)}</Pill>
          : <Pill tone="ok"><CheckCircle2 className="h-3 w-3" aria-hidden />Connected</Pill>}
      </div>
      <p className="text-xs text-gray-500 dark:text-gray-400">
        Connected {formatWhen(c.grantedAt)} · {c.lastSyncAt ? `last sync ${formatWhen(c.lastSyncAt)}` : 'not synced yet'}{c.shared ? ' · made by someone you share with' : ''}
      </p>
      <ul className="flex flex-col divide-y divide-gray-100 text-sm dark:divide-gray-800">
        {types.map(t => (
          <li key={t} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
            <span className="text-gray-800 dark:text-gray-100">{DATA_TYPE_WORDS[t] ?? t}</span>
            <span className="flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              {readings(c.rowsByType[t] ?? 0)} added
              {!revoked && c.dataTypes.length > 1 && (
                <Button size="sm" variant="ghost" disabled={stop.isPending} onClick={() => { setDone(null); stop.mutate([t]) }}>Turn off</Button>
              )}
            </span>
          </li>
        ))}
      </ul>
      {!offer && (
        <div className="flex flex-wrap gap-2">
          {!revoked && <Button size="sm" variant="secondary" loading={stop.isPending} onClick={() => { setDone(null); stop.mutate(null) }}><Trash2 className="h-3.5 w-3.5" aria-hidden />Remove</Button>}
          {revoked && c.rowsWritten > 0 && <Button size="sm" variant="secondary" onClick={() => { setDone(null); setOffer({ types: null, rows: count(null) }) }}>Delete what it added</Button>}
        </div>
      )}
      {done && <p role="status" className="text-sm text-gray-700 dark:text-gray-200">{done}</p>}
      {offer && (
        <div className="flex flex-col gap-2 rounded-lg bg-gray-50 p-3 dark:bg-gray-800/60">
          <p className="text-sm text-gray-800 dark:text-gray-100">
            Delete what it added? {readings(offer.rows)}{offer.types ? ` of ${offer.types.map(t => DATA_TYPE_WORDS[t].toLowerCase()).join(', ')}` : ''} from this phone stay in the record until you delete them. This cannot be undone.
          </p>
          <div className="flex gap-2">
            <Button size="sm" variant="danger" loading={purge.isPending} onClick={() => purge.mutate(offer.types)}>Delete {readings(offer.rows)}</Button>
            <Button size="sm" variant="ghost" onClick={() => setOffer(null)}>Keep them</Button>
          </div>
        </div>
      )}
      {(stop.error || purge.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(stop.error ?? purge.error, 'That did not work.')}</p>}
    </section>
  )
}
