import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Lock, Pencil, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import type { ServiceVariable } from '@/types'
import { Empty, SectionHeader, Table, errorText } from '../shared'
import type { TabProps } from '../ServiceEditorPage'

/** Service-wide values the APIs reference as {{var.name}}. */
export function VariablesTab({ detail, refresh }: TabProps) {
  const [editing, setEditing] = useState<ServiceVariable | 'new' | null>(null)
  const remove = useMutation({
    mutationFn: (id: string) => servicesApi.deleteVariable(detail.service.id, id),
    onSuccess: refresh,
  })

  return (
    <div>
      <SectionHeader title="Variables" description="Use them in the base URL, API paths and fixed headers as {{var.name}}. Secret values are encrypted and never shown again."
        action={<Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" />New variable</Button>} />
      {detail.variables.length === 0 ? <Empty>No variables yet. Typical ones are an organisation id, a region or a data centre.</Empty> : (
        <Table head={['Name', 'Value', 'Description', '']}>
          {detail.variables.map(v => (
            <tr key={v.id}>
              <td className="px-4 py-3 font-mono text-xs text-gray-900 dark:text-gray-100">{`{{var.${v.name}}}`}</td>
              <td className="px-4 py-3 font-mono text-xs">
                {v.secret ? <span className="inline-flex items-center gap-1 text-gray-500"><Lock className="h-3 w-3" />{v.valueSet ? '••••••••' : 'not set'}</span> : v.value}
              </td>
              <td className="px-4 py-3 text-xs text-gray-500 dark:text-gray-400">{v.description ?? ''}</td>
              <td className="px-4 py-3 text-right whitespace-nowrap">
                <Button size="sm" variant="ghost" aria-label={`Edit ${v.name}`} onClick={() => setEditing(v)}><Pencil className="h-3.5 w-3.5" /></Button>
                <Button size="sm" variant="ghost" aria-label={`Delete ${v.name}`} onClick={() => {
                  if (window.confirm(`Delete ${v.name}? APIs that use it will stop working.`)) remove.mutate(v.id)
                }}><Trash2 className="h-3.5 w-3.5" /></Button>
              </td>
            </tr>
          ))}
        </Table>
      )}
      {editing && <VariableForm serviceId={detail.service.id} variable={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh() }} />}
    </div>
  )
}

function VariableForm({ serviceId, variable, onClose, onSaved }: {
  serviceId: string; variable: ServiceVariable | null; onClose: () => void; onSaved: () => void
}) {
  const [name, setName] = useState(variable?.name ?? '')
  const [description, setDescription] = useState(variable?.description ?? '')
  const [value, setValue] = useState(variable?.secret ? '' : variable?.value ?? '')
  const [secret, setSecret] = useState(variable?.secret ?? false)
  const save = useMutation({
    mutationFn: () => servicesApi.saveVariable(serviceId, variable?.id, { name, description, value, secret }),
    onSuccess: onSaved,
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <SlideOver open title={variable ? `Edit ${variable.name}` : 'New variable'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit" form="variable-form" loading={save.isPending}>Save variable</Button></>}>
      <form id="variable-form" onSubmit={submit} className="flex flex-col gap-4">
        <Input label="Name" required value={name} className="font-mono" onChange={e => setName(e.target.value)} placeholder="org_id" />
        <Input label="Value" type={secret ? 'password' : 'text'} value={value} onChange={e => setValue(e.target.value)} autoComplete="off"
          placeholder={variable?.secret && variable.valueSet ? 'Stored; leave blank to keep' : ''} />
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={secret} onChange={e => setSecret(e.target.checked)} /> Secret (encrypted, never shown again)
        </label>
        <Field label="Description"><textarea className={fieldClass} rows={2} value={description} onChange={e => setDescription(e.target.value)} /></Field>
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </form>
    </SlideOver>
  )
}
