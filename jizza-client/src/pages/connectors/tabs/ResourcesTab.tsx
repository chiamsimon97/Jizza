import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Pencil, Plus, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import type { ResourceField, ServiceResource } from '@/types'
import { Empty, SectionHeader, Table, errorText, toMachineName } from '../shared'
import type { TabProps } from '../ServiceEditorPage'

/** The entities the service deals in, such as Invoice or Contact, and their fields. */
export function ResourcesTab({ detail, refresh }: TabProps) {
  const [editing, setEditing] = useState<ServiceResource | 'new' | null>(null)
  const remove = useMutation({
    mutationFn: (id: string) => servicesApi.deleteResource(detail.service.id, id),
    onSuccess: refresh,
  })

  return (
    <div>
      <SectionHeader title="Resources" description="What the service's data looks like. Actions and triggers say which resource they work on."
        action={<Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" />New resource</Button>} />
      {detail.resources.length === 0 ? <Empty>No resources yet.</Empty> : (
        <Table head={['Resource', 'Fields', 'Id field', '']}>
          {detail.resources.map(r => (
            <tr key={r.id}>
              <td className="px-4 py-3">
                <p className="font-medium text-gray-900 dark:text-gray-100">{r.label}</p>
                <p className="font-mono text-xs text-gray-500">{r.name}</p>
              </td>
              <td className="px-4 py-3 text-xs text-gray-600 dark:text-gray-300">{r.fields.map(f => f.key).join(', ') || '—'}</td>
              <td className="px-4 py-3 font-mono text-xs">{r.idField ?? '—'}</td>
              <td className="px-4 py-3 text-right whitespace-nowrap">
                <Button size="sm" variant="ghost" aria-label={`Edit ${r.label}`} onClick={() => setEditing(r)}><Pencil className="h-3.5 w-3.5" /></Button>
                <Button size="sm" variant="ghost" aria-label={`Delete ${r.label}`} onClick={() => {
                  if (window.confirm(`Delete ${r.label}?`)) remove.mutate(r.id)
                }}><Trash2 className="h-3.5 w-3.5" /></Button>
              </td>
            </tr>
          ))}
        </Table>
      )}
      {editing && <ResourceForm serviceId={detail.service.id} resource={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh() }} />}
    </div>
  )
}

function ResourceForm({ serviceId, resource, onClose, onSaved }: {
  serviceId: string; resource: ServiceResource | null; onClose: () => void; onSaved: () => void
}) {
  const [label, setLabel] = useState(resource?.label ?? '')
  const [name, setName] = useState(resource?.name ?? '')
  const [touched, setTouched] = useState(!!resource)
  const [description, setDescription] = useState(resource?.description ?? '')
  const [idField, setIdField] = useState(resource?.idField ?? 'id')
  const [fields, setFields] = useState<ResourceField[]>(resource?.fields ?? [{ key: 'id', label: 'ID', type: 'string', required: true }])
  const save = useMutation({
    mutationFn: () => servicesApi.saveResource(serviceId, resource?.id, {
      name, label, description, idField, fields: fields.filter(f => f.key),
    }),
    onSuccess: onSaved,
  })
  const update = (i: number, patch: Partial<ResourceField>) => setFields(fs => fs.map((f, j) => j === i ? { ...f, ...patch } : f))

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <SlideOver open title={resource ? `Edit ${resource.label}` : 'New resource'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit" form="resource-form" loading={save.isPending}>Save resource</Button></>}>
      <form id="resource-form" onSubmit={submit} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Input label="Display name" required value={label} placeholder="Invoice"
            onChange={e => { setLabel(e.target.value); if (!touched) setName(toMachineName(e.target.value)) }} />
          <Input label="Machine name" required value={name} className="font-mono" onChange={e => { setTouched(true); setName(e.target.value) }} />
        </div>
        <Field label="Description"><textarea className={fieldClass} rows={2} value={description} onChange={e => setDescription(e.target.value)} /></Field>
        <Input label="Id field" value={idField} onChange={e => setIdField(e.target.value)} className="font-mono" hint="The field that uniquely identifies a record" />
        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Fields</span>
            <Button type="button" size="sm" variant="ghost" onClick={() => setFields(fs => [...fs, { key: '', label: '', type: 'string', required: false }])}>
              <Plus className="h-3.5 w-3.5" />Add field</Button>
          </div>
          {fields.map((f, i) => (
            <div key={i} className="mb-2 grid grid-cols-[1fr_1fr_96px_auto_auto] items-center gap-2">
              <input className={`${fieldClass} font-mono`} placeholder="key" value={f.key} aria-label="Field key" onChange={e => update(i, { key: e.target.value })} />
              <input className={fieldClass} placeholder="Label" value={f.label ?? ''} aria-label="Field label" onChange={e => update(i, { label: e.target.value })} />
              <select className={fieldClass} value={f.type ?? 'string'} aria-label="Field type" onChange={e => update(i, { type: e.target.value })}>
                {['string', 'number', 'integer', 'boolean', 'date', 'datetime', 'object', 'array'].map(t => <option key={t}>{t}</option>)}
              </select>
              <label className="flex items-center gap-1 text-xs text-gray-600 dark:text-gray-400">
                <input type="checkbox" checked={!!f.required} onChange={e => update(i, { required: e.target.checked })} />req.
              </label>
              <button type="button" aria-label="Remove field" className="text-gray-400 hover:text-red-500" onClick={() => setFields(fs => fs.filter((_, j) => j !== i))}><X className="h-4 w-4" /></button>
            </div>
          ))}
        </div>
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </form>
    </SlideOver>
  )
}
