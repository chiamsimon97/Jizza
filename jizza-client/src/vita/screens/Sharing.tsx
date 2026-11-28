import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { House, Printer, ShieldCheck, Users } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { useAuthorization } from '@/auth/useAuthorization'
import { CreateFamily } from '@/components/layout/AccountSwitcher'
import { Button } from '@/components/ui/Button'
import { formatDay } from '@/portfolio/components/AsOf'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { AccessEntry, HealthConsent, ShareAccess, ShareLink, SharePreset, ShareSection, SharesView } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { vitaKeys } from '../queryKeys'
import { PRESET_LABELS, SECTION_LABELS, accessWords } from '../share'
import { PRINT_CSS } from './VisitPrep'

type Cell = ShareAccess | 'none'
type Matrix = Partial<Record<ShareSection, Cell>>

const ENDED_WORDS: Record<string, string> = {
  revoked: 'sharing stopped', record_closed: 'record closed', left_family: 'left the Family', removed: 'removed from the Family',
  account_deleted: 'account deleted', consent_withdrawn: 'consent withdrawn',
}
const endedWords = (reason: string | null) => (reason ? ENDED_WORDS[reason] ?? reason.replace(/_/g, ' ') : 'ended')

/**
 * Sharing (7.16): headed by the Family and its members. For one person and one member pick a preset
 * or customise the matrix; a helper needs a consent note first; the access list shows who can see
 * what and since when, ended shares too, and prints.
 */
export function Sharing({ personId, personName, kind, closed }: { personId: string; personName: string; kind: string; closed: boolean }) {
  const shares = useQuery({ queryKey: vitaKeys.shares(personId), queryFn: () => vitaApi.shares(personId) })
  const [creating, setCreating] = useState(false)
  const { data: snapshot } = useAuthorization()
  const family = snapshot?.workspaces.find(w => w.type === 'FAMILY' && w.role === 'OWNER') ?? snapshot?.workspaces.find(w => w.type === 'FAMILY')
  const isSelf = kind === 'self'
  const whose = isSelf ? 'your' : `${personName}’s`

  if (shares.isLoading) return <SkeletonRows rows={4} />
  if (shares.error || !shares.data) return <LoadFailed text="Could not load sharing." onRetry={() => shares.refetch()} />
  const view = shares.data

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Sharing" sub={`Who in your Family sees which part of ${whose} record`} />

      {!view.hasFamily ? (
        <Panel title={<><House className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Create your Family</>}>
          <p className="text-sm text-gray-600 dark:text-gray-300">
            Sharing works inside a Family: a small account that only lists its members. Create it, invite your family from its Members page,
            then come back here to choose what each person sees. Everyone keeps their own account.
          </p>
          <div><Button size="sm" onClick={() => setCreating(true)}>Create your Family</Button></div>
          <CreateFamily open={creating} onClose={() => setCreating(false)} />
        </Panel>
      ) : (
        <>
          <Panel title={<><Users className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />{family?.name ?? 'Your Family'}</>}>
            {view.members.length ? (
              <ul className="flex flex-wrap gap-1.5" aria-label="Family members">
                {view.members.map(m => <li key={m.userId}><Pill tone="neutral">{m.email ?? 'A member'}</Pill></li>)}
              </ul>
            ) : (
              <p className="text-sm text-gray-500 dark:text-gray-400">No one else is in the Family yet. Invite people from the Family’s Members page, then share here.</p>
            )}
          </Panel>

          {closed ? (
            <p role="status" className="rounded-lg bg-gray-100 px-3 py-2 text-sm text-gray-700 dark:bg-gray-800 dark:text-gray-200">
              This record is closed, so every share of it has ended. It can still be exported from Profile.
            </p>
          ) : (
            <ShareList personId={personId} personName={personName} isSelf={isSelf} view={view} />
          )}

          {!isSelf && <ConsentNotes personId={personId} personName={personName} readOnly={closed} />}
        </>
      )}

      <AccessListPanel personId={personId} personName={personName} />
      <p className="text-xs text-gray-500 dark:text-gray-400">{view.note}</p>
    </div>
  )
}

function ShareList({ personId, personName, isSelf, view }: { personId: string; personName: string; isSelf: boolean; view: SharesView }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState<ShareLink | 'new' | null>(null)
  const links = view.links.filter(l => l.personId === personId)
  const refresh = () => {
    qc.invalidateQueries({ queryKey: vitaKeys.shares(personId) })
    qc.invalidateQueries({ queryKey: vitaKeys.accessList(personId) })
  }
  const stop = useMutation({ mutationFn: (l: ShareLink) => vitaApi.endShares(personId, l.granteeUserId), onSuccess: refresh })
  const free = view.members.filter(m => !links.some(l => l.granteeUserId === m.userId))

  return (
    <Panel title={<><ShieldCheck className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Who sees {isSelf ? 'your' : `${personName}’s`} record</>}
      action={editing == null && free.length > 0 && <Button size="sm" onClick={() => setEditing('new')}>Share with a member</Button>}>
      {!links.length && editing == null && (
        <p className="text-sm text-gray-500 dark:text-gray-400">Not shared with anyone. {free.length ? 'Pick a member and what they may see.' : ''}</p>
      )}
      {links.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {links.map(l => (
            <li key={l.granteeUserId} className="flex flex-wrap items-center gap-2 py-2">
              <span className="min-w-0 flex-1">
                <span className="flex flex-wrap items-center gap-1.5 text-sm font-medium text-gray-900 dark:text-gray-100">
                  {l.granteeEmail ?? 'A member'}
                  {l.asSelf && <Pill tone="info">This is them</Pill>}
                </span>
                <span className="block text-xs text-gray-500 dark:text-gray-400">Can {accessWords(l.sections)}.</span>
              </span>
              <Button size="sm" variant="secondary" onClick={() => setEditing(l)}>Change</Button>
              <Button size="sm" variant="ghost" loading={stop.isPending && stop.variables?.granteeUserId === l.granteeUserId}
                onClick={() => { if (window.confirm(`Stop sharing ${personName}’s record with ${l.granteeEmail ?? 'this member'}? ${view.note}`)) stop.mutate(l) }}>
                Stop sharing
              </Button>
            </li>
          ))}
        </ul>
      )}
      {stop.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(stop.error, 'Could not stop sharing.')}</p>}
      {editing != null && (
        <ShareEditor key={editing === 'new' ? 'new' : editing.granteeUserId} personId={personId} personName={personName} isSelf={isSelf} view={view}
          link={editing === 'new' ? null : editing} members={editing === 'new' ? free : view.members.filter(m => m.userId === editing.granteeUserId)}
          onDone={() => { setEditing(null); refresh() }} onCancel={() => setEditing(null)} />
      )}
    </Panel>
  )
}

function matrixOf(sections: Partial<Record<ShareSection, ShareAccess>>, keys: ShareSection[]): Matrix {
  return Object.fromEntries(keys.map(k => [k, sections[k] ?? 'none'])) as Matrix
}

/** The best preset for a link that already exists, or null when its sections match none. */
function presetOf(link: ShareLink, view: SharesView): SharePreset | null {
  if (link.asSelf) return 'self'
  const same = (a: Partial<Record<ShareSection, ShareAccess>>) =>
    view.sectionKeys.every(k => (a[k] ?? 'none') === (link.sections[k] ?? 'none'))
  return view.presets.find(p => p.key !== 'self' && same(p.sections))?.key ?? null
}

function ShareEditor({ personId, personName, isSelf, view, link, members, onDone, onCancel }: {
  personId: string; personName: string; isSelf: boolean; view: SharesView; link: ShareLink | null
  members: SharesView['members']; onDone: () => void; onCancel: () => void
}) {
  const qc = useQueryClient()
  const presets = view.presets.filter(p => !(isSelf && p.key === 'self'))
  const initialPreset = link ? presetOf(link, view) : 'caregiver'
  const [member, setMember] = useState(link?.granteeUserId ?? members[0]?.userId ?? '')
  const [preset, setPreset] = useState<SharePreset | null>(initialPreset)
  const [custom, setCustom] = useState(link != null && initialPreset == null)
  const [matrix, setMatrix] = useState<Matrix>(() => matrixOf(link ? link.sections : view.presets.find(p => p.key === initialPreset)?.sections ?? {}, view.sectionKeys))
  const consents = useQuery({ queryKey: vitaKeys.consents(personId), queryFn: () => vitaApi.consents(personId), enabled: !isSelf })
  const asSelf = preset === 'self'
  const needsConsent = !isSelf && !asSelf && consents.data != null && !consents.data.some(c => c.open)
  const anyEdit = Object.values(matrix).includes('edit')

  const pick = (key: SharePreset) => {
    setPreset(key)
    setMatrix(matrixOf(view.presets.find(p => p.key === key)?.sections ?? {}, view.sectionKeys))
  }
  const save = useMutation({
    mutationFn: () => vitaApi.share({
      personId, granteeUserId: member, asSelf: asSelf || undefined,
      ...(custom || !preset ? { preset: asSelf ? 'self' : null, sections: matrix } : { preset }),
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.consents(personId) }); onDone() },
  })

  return (
    <form className="mt-2 flex flex-col gap-3 rounded-xl border border-gray-200 p-3 dark:border-gray-700"
      onSubmit={e => { e.preventDefault(); save.mutate() }}>
      {link ? (
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">What {link.granteeEmail ?? 'this member'} may see</p>
      ) : (
        <SelectField label="Member" value={member} onChange={setMember}
          options={members.map(m => ({ value: m.userId, label: m.email ?? 'A member' }))} />
      )}
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-xs font-medium text-gray-600 dark:text-gray-300">What they may see</legend>
        <div className="grid gap-2 sm:grid-cols-2">
          {presets.map(p => (
            <label key={p.key} className={`flex cursor-pointer gap-2 rounded-lg border p-2.5 text-sm ${preset === p.key && !custom
              ? 'border-[rgb(var(--agent-health))] bg-rose-50 dark:bg-rose-950/30' : 'border-gray-200 dark:border-gray-700'}`}>
              <input type="radio" name="preset" className="mt-0.5" checked={preset === p.key && !custom} onChange={() => { setCustom(false); pick(p.key) }} />
              <span>
                <span className="block font-medium text-gray-900 dark:text-gray-100">{PRESET_LABELS[p.key]?.label ?? p.key}</span>
                <span className="block text-xs text-gray-500 dark:text-gray-400">{PRESET_LABELS[p.key]?.about}</span>
              </span>
            </label>
          ))}
        </div>
        <label className="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-gray-200">
          <input type="checkbox" checked={custom} onChange={e => setCustom(e.target.checked)} />Customise section by section
        </label>
      </fieldset>
      {custom && <MatrixTable keys={view.sectionKeys} matrix={matrix} onChange={setMatrix} />}
      {asSelf && <p className="text-xs text-gray-500 dark:text-gray-400">They sign in with their own login and see this record as their own. No consent note is needed.</p>}
      {needsConsent && (
        <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
          Record a consent note for {personName} first, below: when they agreed and how. Then share.
        </p>
      )}
      {anyEdit && <p className="text-xs text-gray-500 dark:text-gray-400">Giving Edit asks for your password again.</p>}
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the share.')}</p>}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" type="submit" loading={save.isPending} disabled={!member || needsConsent || !Object.values(matrix).some(v => v !== 'none')}>
          {link ? 'Save' : 'Share'}
        </Button>
        <Button size="sm" variant="ghost" type="button" onClick={onCancel}>Cancel</Button>
      </div>
    </form>
  )
}

/** The full matrix: rows are sections, cells None, View or Edit. */
function MatrixTable({ keys, matrix, onChange }: { keys: ShareSection[]; matrix: Matrix; onChange: (m: Matrix) => void }) {
  const cells: Cell[] = ['none', 'view', 'edit']
  const words: Record<Cell, string> = { none: 'None', view: 'View', edit: 'Edit' }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">What this member may do in each section</caption>
        <thead className="text-xs text-gray-500 dark:text-gray-400">
          <tr><th className="py-1 pr-3 font-medium">Section</th>{cells.map(c => <th key={c} className="px-2 py-1 text-center font-medium">{words[c]}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
          {keys.map(k => (
            <tr key={k}>
              <th scope="row" className="py-1.5 pr-3 font-normal text-gray-800 dark:text-gray-200">{SECTION_LABELS[k] ?? k}</th>
              {cells.map(c => (
                <td key={c} className="px-2 py-1.5 text-center">
                  <input type="radio" name={`m-${k}`} aria-label={`${SECTION_LABELS[k] ?? k}: ${words[c]}`} checked={(matrix[k] ?? 'none') === c}
                    onChange={() => onChange({ ...matrix, [k]: c })} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Consent notes (5.10): recorded before any helper sees the record; withdrawing ends every helper's share. */
function ConsentNotes({ personId, personName, readOnly }: { personId: string; personName: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const notes = useQuery({ queryKey: vitaKeys.consents(personId), queryFn: () => vitaApi.consents(personId) })
  const [scope, setScope] = useState<'view' | 'manage'>('view')
  const [channel, setChannel] = useState('')
  const refresh = () => {
    qc.invalidateQueries({ queryKey: vitaKeys.consents(personId) })
    qc.invalidateQueries({ queryKey: vitaKeys.shares(personId) })
    qc.invalidateQueries({ queryKey: vitaKeys.accessList(personId) })
  }
  const add = useMutation({
    mutationFn: () => vitaApi.recordConsent(personId, { scope, channel: channel.trim() || null }),
    onSuccess: () => { setChannel(''); refresh() },
  })
  const withdraw = useMutation({ mutationFn: (c: HealthConsent) => vitaApi.withdrawConsent(personId, c.id), onSuccess: refresh })

  return (
    <Panel title="Consent notes">
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">
        Before anyone other than {personName} sees this record, note when {personName} agreed and how. Withdrawing the last open note ends every helper’s share.
      </p>
      {notes.isLoading && <SkeletonRows rows={1} />}
      {notes.error && <LoadFailed text="Could not load the consent notes." onRetry={() => notes.refetch()} />}
      {notes.data && !notes.data.length && <p className="text-sm text-gray-500 dark:text-gray-400">No consent note yet.</p>}
      {notes.data && notes.data.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {notes.data.map(c => (
            <li key={c.id} className="flex flex-wrap items-center gap-2 text-sm">
              <Pill tone={c.open ? 'ok' : 'neutral'}>{c.open ? 'Open' : 'Withdrawn'}</Pill>
              <span className="text-gray-800 dark:text-gray-200">
                Agreed to {c.scope === 'manage' ? 'having it managed' : 'sharing'} on {formatDay(c.grantedAt.slice(0, 10))}{c.channel ? `, ${c.channel}` : ''}
                {c.withdrawnAt ? `. Withdrawn on ${formatDay(c.withdrawnAt.slice(0, 10))}` : ''}
              </span>
              {c.open && !readOnly && (
                <Button size="sm" variant="ghost" loading={withdraw.isPending && withdraw.variables?.id === c.id}
                  onClick={() => { if (window.confirm(`Withdraw this note? If no other note is open, every helper’s share of ${personName}’s record ends now.`)) withdraw.mutate(c) }}>
                  Withdraw
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {!readOnly && (
        <form className="flex flex-col gap-2 sm:flex-row sm:items-end" onSubmit={e => { e.preventDefault(); add.mutate() }}>
          <SelectField label="They agreed to" value={scope} onChange={setScope}
            options={[{ value: 'view', label: 'Sharing their record' }, { value: 'manage', label: 'Having it managed' }]} />
          <TextField label="How (optional)" value={channel} maxLength={40} placeholder="In person" onChange={setChannel} />
          <Button size="sm" type="submit" loading={add.isPending}>Record the note</Button>
        </form>
      )}
      {(add.error || withdraw.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(add.error ?? withdraw.error, 'Could not save the note.')}</p>}
    </Panel>
  )
}

/** Who can see what of the record and since when, ended shares too. Prints on its own. */
function AccessListPanel({ personId, personName }: { personId: string; personName: string }) {
  const list = useQuery({ queryKey: vitaKeys.accessList(personId), queryFn: () => vitaApi.accessList(personId) })
  const [showEnded, setShowEnded] = useState(false)
  const entries = list.data?.entries ?? []
  const active = entries.filter(e => e.active)
  const ended = entries.filter(e => !e.active)
  const rows = showEnded ? entries : active

  return (
    <Panel title="Access list" action={entries.length > 0 && <Button size="sm" variant="secondary" onClick={() => window.print()}><Printer className="h-3.5 w-3.5" aria-hidden />Print</Button>}>
      <style>{PRINT_CSS}</style>
      <div id="vita-sheet" className="flex flex-col gap-2">
        <p className="hidden text-base font-semibold print:block">Access list for {personName}</p>
        {list.isLoading && <SkeletonRows rows={2} />}
        {list.error && <LoadFailed text="Could not load the access list." onRetry={() => list.refetch()} />}
        {list.data && !entries.length && <p className="text-sm text-gray-500 dark:text-gray-400">Only you can see this record.</p>}
        {rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs text-gray-500 dark:text-gray-400">
                <tr><th className="py-1 pr-3 font-medium">Who</th><th className="py-1 pr-3 font-medium">Section</th><th className="py-1 pr-3 font-medium">Access</th><th className="py-1 font-medium">Since</th></tr>
              </thead>
              <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
                {rows.map(e => <AccessRow key={e.id} e={e} />)}
              </tbody>
            </table>
          </div>
        )}
        {ended.length > 0 && (
          <div className="no-print">
            <Button size="sm" variant="ghost" onClick={() => setShowEnded(!showEnded)}>
              {showEnded ? 'Hide ended shares' : `Show ${ended.length} ended ${ended.length === 1 ? 'share' : 'shares'}`}
            </Button>
          </div>
        )}
        {list.data && <p className="text-xs text-gray-500 dark:text-gray-400">{list.data.note}</p>}
      </div>
    </Panel>
  )
}

function AccessRow({ e }: { e: AccessEntry }) {
  return (
    <tr className={e.active ? '' : 'text-gray-500 dark:text-gray-400'}>
      <td className="py-1.5 pr-3 align-top">{e.granteeEmail ?? 'A member'}{e.asSelf && <span className="block text-xs text-gray-500 dark:text-gray-400">This is them</span>}</td>
      <td className="py-1.5 pr-3 align-top">{SECTION_LABELS[e.section] ?? e.section}</td>
      <td className="py-1.5 pr-3 align-top">{e.access === 'edit' ? 'Edit' : 'View'}</td>
      <td className="py-1.5 align-top">
        {formatDay(e.grantedAt.slice(0, 10))}
        {!e.active && <span className="block text-xs">Ended {e.endedAt ? formatDay(e.endedAt.slice(0, 10)) : ''}: {endedWords(e.endedReason)}</span>}
      </td>
    </tr>
  )
}

