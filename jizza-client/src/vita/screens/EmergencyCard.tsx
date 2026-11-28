import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ImageDown, Pencil, Printer, Share2, Trash2, UserPlus } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { TextField } from '@/portfolio/components/Fields'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { EmergencyContact, EmergencyContactInput } from '@/types/vita'
import { cardImage, EmergencyCardView } from '../components/EmergencyCardView'
import { ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { vitaKeys } from '../queryKeys'

/** Only the card is printed, on one page. */
const PRINT_CSS = `@media print {
  body * { visibility: hidden !important; }
  #vita-emergency-card, #vita-emergency-card * { visibility: visible !important; }
  #vita-emergency-card { position: absolute; left: 0; top: 0; width: 100%; color: #000 !important; background: #fff !important; }
  @page { margin: 12mm; }
}`

export const MAX_CONTACTS = 5

/** Emergency card (7.4): the card, Print, the lock-screen image, and the contacts on it. */
export function EmergencyCard({ personId, readOnly, onOpen }: { personId: string; readOnly: boolean; onOpen: (view: string) => void }) {
  const { data: card, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.card(personId), queryFn: () => vitaApi.emergencyCard(personId) })
  const [image, setImage] = useState<string | null>(null)

  return (
    <div className="flex flex-col gap-4">
      <style>{PRINT_CSS}</style>
      <ViewTitle title="Emergency card" sub="What a stranger or a hospital needs in the first minutes">
        <Button size="sm" variant="secondary" onClick={() => onOpen('profile')}><Pencil className="h-3.5 w-3.5" aria-hidden />Edit in Profile</Button>
      </ViewTitle>
      {isLoading && <SkeletonRows rows={6} />}
      {error && <LoadFailed text="Could not load the emergency card." onRetry={() => refetch()} />}
      {card && (
        <>
          <EmergencyCardView card={card} />
          <Panel title="Actions">
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" onClick={() => window.print()}><Printer className="h-3.5 w-3.5" aria-hidden />Print or save as PDF</Button>
              <Button size="sm" variant="secondary" onClick={() => setImage(cardImage(card))}><ImageDown className="h-3.5 w-3.5" aria-hidden />Save image for lock screen</Button>
              <Button size="sm" variant="secondary" disabled title="Comes with the Family update"><Share2 className="h-3.5 w-3.5" aria-hidden />Share with Family</Button>
            </div>
            <p className="text-xs text-gray-500 dark:text-gray-400">The lock-screen image can be read by anyone holding the phone. Sharing with your Family comes with the Family update.</p>
            {image && (
              <a href={image} download={`emergency-card-${card.name.replace(/\W+/g, '-').toLowerCase()}.png`}
                className="self-start text-sm font-medium text-brand-600 underline dark:text-brand-300">Download the image</a>
            )}
          </Panel>
        </>
      )}
      <Contacts personId={personId} readOnly={readOnly} />
    </div>
  )
}

function Contacts({ personId, readOnly }: { personId: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.contacts(personId), queryFn: () => vitaApi.contacts(personId) })
  const [editing, setEditing] = useState<EmergencyContact | 'new' | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeContact(personId, id), onSuccess: refresh })
  const full = (data?.length ?? 0) >= MAX_CONTACTS

  return (
    <Panel title="Emergency contacts" action={!readOnly && !editing && (
      <Button size="sm" variant="secondary" disabled={full} onClick={() => setEditing('new')}><UserPlus className="h-3.5 w-3.5" aria-hidden />Add contact</Button>
    )}>
      {isLoading && <SkeletonRows rows={2} />}
      {error && <LoadFailed text="Could not load the contacts." onRetry={() => refetch()} />}
      {data && !data.length && !editing && <p className="text-sm text-gray-500 dark:text-gray-400">No contacts yet. Add who a hospital should call; they show on the card.</p>}
      {data && data.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {data.map(c => (
            <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
              <div className="min-w-0">
                <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{c.name}{c.relation && <span className="font-normal text-gray-500 dark:text-gray-400"> · {c.relation}</span>}</p>
                <a href={`tel:${c.phone}`} className="font-mono text-sm text-gray-700 underline-offset-2 hover:underline dark:text-gray-300">{c.phone}</a>
              </div>
              {!readOnly && (
                <div className="flex gap-1">
                  <Button size="sm" variant="ghost" onClick={() => setEditing(c)} aria-label={`Edit ${c.name}`}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                  <Button size="sm" variant="ghost" onClick={() => remove.mutate(c.id)} aria-label={`Remove ${c.name}`}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {full && !readOnly && <p className="text-xs text-gray-500 dark:text-gray-400">A card holds at most {MAX_CONTACTS} contacts.</p>}
      {editing && <ContactForm personId={personId} contact={editing === 'new' ? null : editing} onDone={() => { setEditing(null); refresh() }} />}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not remove the contact.')}</p>}
    </Panel>
  )
}

export function ContactForm({ personId, contact, onDone }: { personId: string; contact: EmergencyContact | null; onDone: () => void }) {
  const [v, setV] = useState<EmergencyContactInput>({ name: contact?.name ?? '', relation: contact?.relation ?? '', phone: contact?.phone ?? '' })
  const save = useMutation({
    mutationFn: () => {
      const body = { name: v.name.trim(), relation: v.relation?.trim() || null, phone: v.phone.trim() }
      return contact ? vitaApi.updateContact(personId, contact.id, body) : vitaApi.addContact(personId, body)
    },
    onSuccess: onDone,
  })
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="Name" value={v.name} maxLength={80} onChange={name => setV({ ...v, name })} />
        <TextField label="Relation (optional)" value={v.relation} maxLength={40} placeholder="Son" onChange={relation => setV({ ...v, relation })} />
        <TextField label="Phone" value={v.phone} maxLength={30} placeholder="+91 …" onChange={phone => setV({ ...v, phone })} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending} disabled={!v.name.trim() || !v.phone.trim()}>Save contact</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the contact.')}</p>}
    </form>
  )
}
