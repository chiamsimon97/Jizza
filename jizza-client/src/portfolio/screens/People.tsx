import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, Trash2, UserRound } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Field, SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { Spinner } from '@/components/ui/Spinner'
import type { FinPerson, FinPersonInput, PersonKind, PersonType } from '@/types/finance'
import { financeKeys } from '../queryKeys'

export const KIND_LABELS: Record<PersonKind, string> = {
  self: 'You', spouse: 'Spouse', child: 'Child', parent: 'Parent', huf: 'HUF', other: 'Other',
}
const TYPE_LABELS: Record<PersonType, string> = { individual: 'Adult', minor: 'Minor', huf: 'HUF' }

/** Whole years from a date of birth to today. */
export function ageOn(dob: string | null, today = new Date()): number | null {
  if (!dob) return null
  const [y, m, d] = dob.split('-').map(Number)
  let age = today.getFullYear() - y
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) age--
  return age
}

/** An adult someone else's money is recorded for: their consent is noted here. */
export function isManagedAdult(p: FinPerson): boolean {
  const age = ageOn(p.dob)
  return p.kind !== 'self' && p.type === 'individual' && (age == null || age >= 18)
}

function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback
}

/**
 * People you manage (7.22): the user, and anyone without a login whose money they look after.
 * Family members with their own login join a Family instead.
 */
export function People() {
  const qc = useQueryClient()
  const { data: people, isLoading } = useQuery({ queryKey: financeKeys.people, queryFn: financeApi.people })
  const [editing, setEditing] = useState<FinPerson | 'new' | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)
  const archive = useMutation({
    mutationFn: (id: string) => financeApi.archivePerson(id),
    onSuccess: () => { setConfirming(null); qc.invalidateQueries({ queryKey: financeKeys.all }) },
  })

  if (isLoading || !people) return <div className="flex justify-center py-10"><Spinner /></div>
  const active = people.filter(p => p.status === 'active')
  const archived = people.filter(p => p.status !== 'active')

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">People you manage</h2>
          <p className="max-w-xl text-sm text-gray-500 dark:text-gray-400">
            Children, parents or an HUF whose money you look after and who have no login of their own. Family members with their own Jizza account share with you through a Family instead.
          </p>
        </div>
        <Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" /> Add a person</Button>
      </div>

      <ul className="divide-y divide-gray-200 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
        {active.map(p => {
          const age = ageOn(p.dob)
          return (
            <li key={p.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <span className="grid h-9 w-9 place-items-center rounded-full bg-brand-50 text-xs font-semibold text-brand-700 dark:bg-brand-900/30 dark:text-brand-200" aria-hidden>
                {p.kind === 'self' ? <UserRound className="h-4 w-4" /> : initials(p.displayName)}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{p.displayName}</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {KIND_LABELS[p.kind]}{p.kind !== 'self' ? ` · ${TYPE_LABELS[p.type]}` : ''}{age != null ? ` · ${age} yrs` : ''}
                </p>
              </div>
              <div className="flex flex-wrap gap-1">
                {p.earning && <Badge variant="info">Earning</Badge>}
                {p.dependent && <Badge>Dependent</Badge>}
              </div>
              <Button size="sm" variant="ghost" onClick={() => setEditing(p)} aria-label={`Edit ${p.displayName}`}><Pencil className="h-3.5 w-3.5" /></Button>
              {p.kind !== 'self' && (confirming === p.id
                ? <Button size="sm" variant="danger" loading={archive.isPending} onClick={() => archive.mutate(p.id)}>Remove {p.displayName}?</Button>
                : <Button size="sm" variant="ghost" onClick={() => setConfirming(p.id)} aria-label={`Remove ${p.displayName}`}><Trash2 className="h-3.5 w-3.5" /></Button>)}
            </li>
          )
        })}
      </ul>
      {active.length === 1 && (
        <p className="text-sm text-gray-500 dark:text-gray-400">Nobody else yet. Add a child, a parent or an HUF when you look after their money.</p>
      )}
      {archived.length > 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">Removed: {archived.map(p => p.displayName).join(', ')}. Their records are kept.</p>
      )}
      {archive.error && <p className="text-sm text-red-600">{errorText(archive.error, 'Could not remove this person.')}</p>}

      {editing && <PersonForm person={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function initials(name: string) {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]?.toUpperCase()).join('')
}

function PersonForm({ person, onClose }: { person: FinPerson | null; onClose: () => void }) {
  const qc = useQueryClient()
  const self = person?.kind === 'self'
  const [form, setForm] = useState<FinPersonInput>({
    displayName: person?.displayName ?? '',
    kind: person?.kind ?? 'child',
    type: person?.type ?? 'minor',
    dob: person?.dob ?? '',
    isEarning: person?.earning ?? false,
    isDependent: person?.dependent ?? (person ? false : true),
  })
  const set = (patch: Partial<FinPersonInput>) => setForm(f => ({ ...f, ...patch }))
  const save = useMutation({
    mutationFn: () => {
      const body = { ...form, dob: form.dob || null }
      return person ? financeApi.updatePerson(person.id, body) : financeApi.addPerson(body)
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: financeKeys.all }); onClose() },
  })

  return (
    <SlideOver open title={person ? `Edit ${person.displayName}` : 'Add a person you manage'}
      description={self ? 'Your own entry.' : 'Someone without a login whose money you look after.'}
      onClose={onClose}
      footer={<>
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} onClick={() => save.mutate()}>Save</Button>
      </>}>
      <div className="flex flex-col gap-4">
        <Input label="Name" value={form.displayName} maxLength={80} onChange={e => set({ displayName: e.target.value })} />
        {!self && (
          <>
            <Field label="Relationship">
              <select className={fieldClass} value={form.kind} onChange={e => {
                const kind = e.target.value as PersonKind
                set({ kind, type: kind === 'huf' ? 'huf' : kind === 'child' ? 'minor' : 'individual' })
              }}>
                {(['child', 'parent', 'spouse', 'huf', 'other'] as PersonKind[]).map(k => <option key={k} value={k}>{KIND_LABELS[k]}</option>)}
              </select>
            </Field>
            {form.kind !== 'huf' && (
              <Field label="Type" hint="A minor is under 18; an adult needs a consent note.">
                <select className={fieldClass} value={form.type} onChange={e => set({ type: e.target.value as PersonType })}>
                  <option value="minor">Minor</option>
                  <option value="individual">Adult</option>
                </select>
              </Field>
            )}
          </>
        )}
        <Input label="Date of birth" type="date" value={form.dob ?? ''} onChange={e => set({ dob: e.target.value })}
          hint={form.type === 'minor' ? 'Needed for a minor: Kai reminds you before they turn 18.' : 'Optional; used for age-based defaults.'} />
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={!!form.isEarning} onChange={e => set({ isEarning: e.target.checked })} /> Earns an income
        </label>
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={!!form.isDependent} onChange={e => set({ isDependent: e.target.checked })} /> Depends on the family's income
        </label>
        {save.error && <p className="text-sm text-red-600">{errorText(save.error, 'Could not save.')}</p>}
        {person && isManagedAdult(person) && <ConsentNotes person={person} />}
      </div>
    </SlideOver>
  )
}

/** Consent notes for a managed adult: when and how they agreed, and withdrawing it. */
export function ConsentNotes({ person }: { person: FinPerson }) {
  const qc = useQueryClient()
  const { data: notes } = useQuery({ queryKey: financeKeys.consents(person.id), queryFn: () => financeApi.consents(person.id) })
  const [scope, setScope] = useState<'view' | 'manage'>('manage')
  const [channel, setChannel] = useState('')
  const refresh = () => qc.invalidateQueries({ queryKey: financeKeys.consents(person.id) })
  const record = useMutation({
    mutationFn: () => financeApi.recordConsent(person.id, { scope, channel: channel.trim() || undefined }),
    onSuccess: () => { setChannel(''); refresh() },
  })
  const withdraw = useMutation({ mutationFn: (id: string) => financeApi.revokeConsent(id), onSuccess: refresh })
  const open = notes?.filter(n => !n.withdrawnAt) ?? []

  return (
    <section className="mt-2 flex flex-col gap-3 border-t border-gray-200 pt-4 dark:border-gray-800">
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Consent</h3>
      <p className="text-xs text-gray-500 dark:text-gray-400">
        {person.displayName} is an adult. Note when and how they agreed to you recording their money. Withdrawing it removes their records after 30 days.
      </p>
      <ul className="flex flex-col gap-2">
        {notes?.map(n => (
          <li key={n.id} className="flex flex-wrap items-center gap-2 text-sm">
            <Badge variant={n.withdrawnAt ? 'default' : 'success'}>{n.scope === 'manage' ? 'Manage' : 'View'}</Badge>
            <span className="text-gray-700 dark:text-gray-300">
              Agreed {new Date(n.grantedAt).toLocaleDateString('en-IN')}{n.channel ? ` · ${n.channel}` : ''}
            </span>
            {n.withdrawnAt
              ? <span className="text-xs text-gray-500">Withdrawn; records removed after {n.purgeAfter}</span>
              : <Button size="sm" variant="ghost" loading={withdraw.isPending && withdraw.variables === n.id} onClick={() => withdraw.mutate(n.id)}>Withdraw</Button>}
          </li>
        ))}
      </ul>
      {open.length === 0 && (
        <div className="flex flex-wrap items-end gap-2">
          <Field label="They agreed to">
            <select className={fieldClass} value={scope} onChange={e => setScope(e.target.value as 'view' | 'manage')}>
              <option value="manage">You managing their money</option>
              <option value="view">You viewing their money</option>
            </select>
          </Field>
          <div className="min-w-[10rem] flex-1">
            <Input label="How" placeholder="In person, by message…" maxLength={40} value={channel} onChange={e => setChannel(e.target.value)} />
          </div>
          <Button size="sm" loading={record.isPending} onClick={() => record.mutate()}>Record consent</Button>
        </div>
      )}
      {(record.error || withdraw.error) && <p className="text-sm text-red-600">{errorText(record.error ?? withdraw.error, 'Could not save the consent note.')}</p>}
    </section>
  )
}
