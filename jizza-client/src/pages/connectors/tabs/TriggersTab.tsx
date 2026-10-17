import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { FlaskConical, Pencil, Plus, Trash2 } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import type { ServiceTrigger } from '@/types'
import { Empty, JsonBlock, SectionHeader, Table, errorText, toMachineName } from '../shared'
import type { TabProps } from '../ServiceEditorPage'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'

/** Events in the service, found by polling a list API for items not seen before. */
export function TriggersTab({ detail, refresh }: TabProps) {
  const [editing, setEditing] = useState<ServiceTrigger | 'new' | null>(null)
  const remove = useMutation({
    mutationFn: (id: string) => servicesApi.deleteTrigger(detail.service.id, id),
    onSuccess: refresh,
  })
  const test = useMutation({ mutationFn: (id: string) => servicesApi.testTrigger(detail.service.id, id) })
  const [tested, setTested] = useState<string | null>(null)
  const apiOf = (id: string) => detail.apis.find(a => a.id === id)

  return (
    <div>
      <SectionHeader title="Triggers" description="Jizza checks each trigger's API on its interval while the service is Live, and records new items in History. The first check only takes a baseline."
        action={<Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" disabled={detail.apis.length === 0} onClick={() => setEditing('new')}><Plus className="h-4 w-4" />New trigger</Button></Can>} />
      {detail.apis.length === 0 && <Empty>Add a polling API first, one that lists items such as recent bills.</Empty>}
      {detail.apis.length > 0 && detail.triggers.length === 0 && <Empty>No triggers yet.</Empty>}
      {detail.triggers.length > 0 && (
        <Table head={['Trigger', 'API', 'Every', 'Last checked', '']}>
          {detail.triggers.map(t => (
            <tr key={t.id}>
              <td className="px-4 py-3">
                <p className="font-medium text-gray-900 dark:text-gray-100">{t.label} {!t.enabled && <Badge className="ml-1">disabled</Badge>}</p>
                <p className="font-mono text-xs text-gray-500">{t.name}</p>
              </td>
              <td className="px-4 py-3 font-mono text-xs">{apiOf(t.apiId)?.label ?? '—'}</td>
              <td className="px-4 py-3 text-xs">{t.pollIntervalMinutes} min</td>
              <td className="px-4 py-3 text-xs text-gray-500">{t.lastPolledAt ? new Date(t.lastPolledAt).toLocaleString() : 'Not yet'}</td>
              <td className="px-4 py-3 text-right whitespace-nowrap">
                <Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" variant="ghost" aria-label={`Test ${t.label}`} loading={test.isPending && tested === t.id}
                  onClick={() => { setTested(t.id); test.mutate(t.id, { onSettled: refresh }) }}><FlaskConical className="h-3.5 w-3.5" /></Button></Can>
                <Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" variant="ghost" aria-label={`Edit ${t.label}`} onClick={() => setEditing(t)}><Pencil className="h-3.5 w-3.5" /></Button></Can>
                <Can permission={PERMISSIONS.CONNECTOR_DELETE} mode="disable"><Button size="sm" variant="ghost" aria-label={`Delete ${t.label}`} onClick={() => {
                  if (window.confirm(`Delete ${t.label}?`)) remove.mutate(t.id)
                }}><Trash2 className="h-3.5 w-3.5" /></Button></Can>
              </td>
            </tr>
          ))}
        </Table>
      )}

      {tested && (test.data || test.error) && (
        <div className="mt-4 rounded-xl border border-gray-200 p-4 dark:border-gray-800">
          <p className="mb-2 text-sm font-medium text-gray-900 dark:text-gray-100">
            Test of {detail.triggers.find(t => t.id === tested)?.label}
          </p>
          {test.error && <p className="text-sm text-red-500">{errorText(test.error)}</p>}
          {test.data && !test.data.ok && <p className="text-sm text-red-500">{test.data.error}</p>}
          {test.data?.ok && <>
            <p className="mb-2 text-sm text-gray-600 dark:text-gray-300">Found {test.data.totalItems} items, {test.data.newItems} not seen before. A sample:</p>
            <JsonBlock value={test.data.items} />
          </>}
        </div>
      )}

      {editing && <TriggerForm detail={detail} trigger={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh() }} />}
    </div>
  )
}

function TriggerForm({ detail, trigger, onClose, onSaved }: {
  detail: TabProps['detail']; trigger: ServiceTrigger | null; onClose: () => void; onSaved: () => void
}) {
  const getApis = detail.apis.filter(a => a.httpMethod === 'GET')
  const [label, setLabel] = useState(trigger?.label ?? '')
  const [name, setName] = useState(trigger?.name ?? '')
  const [touched, setTouched] = useState(!!trigger)
  const [description, setDescription] = useState(trigger?.description ?? '')
  const [apiId, setApiId] = useState(trigger?.apiId ?? (getApis.find(a => a.kind === 'polling') ?? getApis[0])?.id ?? '')
  const [resourceId, setResourceId] = useState(trigger?.resourceId ?? '')
  const [interval, setInterval] = useState(String(trigger?.pollIntervalMinutes ?? 15))
  const [itemsPath, setItemsPath] = useState(trigger?.itemsPath ?? '')
  const [idField, setIdField] = useState(trigger?.idField ?? 'id')
  const [enabled, setEnabled] = useState(trigger?.enabled ?? true)
  const save = useMutation({
    mutationFn: () => servicesApi.saveTrigger(detail.service.id, trigger?.id, {
      name, label, description, apiId, resourceId: resourceId || null,
      pollIntervalMinutes: Number(interval), itemsPath, idField, enabled,
    }),
    onSuccess: onSaved,
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <SlideOver open title={trigger ? `Edit ${trigger.label}` : 'New trigger'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit" form="trigger-form" loading={save.isPending}>Save trigger</Button></>}>
      <form id="trigger-form" onSubmit={submit} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Input label="Display name" required value={label} placeholder="Bill created"
            onChange={e => { setLabel(e.target.value); if (!touched) setName(toMachineName(e.target.value)) }} />
          <Input label="Machine name" required value={name} className="font-mono" onChange={e => { setTouched(true); setName(e.target.value) }} />
        </div>
        <Field label="Description"><textarea className={fieldClass} rows={2} value={description} onChange={e => setDescription(e.target.value)} /></Field>
        <Field label="API to poll" hint="A GET request that lists recent items. It is called without inputs.">
          <select className={fieldClass} required value={apiId} onChange={e => setApiId(e.target.value)}>
            {getApis.length === 0 && <option value="">No GET APIs yet</option>}
            {getApis.map(a => <option key={a.id} value={a.id}>{a.label} ({a.pathTemplate})</option>)}
          </select>
        </Field>
        <Field label="Resource">
          <select className={fieldClass} value={resourceId} onChange={e => setResourceId(e.target.value)}>
            <option value="">None</option>
            {detail.resources.map(r => <option key={r.id} value={r.id}>{r.label}</option>)}
          </select>
        </Field>
        <div className="grid gap-4 sm:grid-cols-3">
          <Input label="Check every (minutes)" type="number" min={1} max={1440} value={interval} onChange={e => setInterval(e.target.value)} />
          <Input label="Items path" value={itemsPath} className="font-mono" onChange={e => setItemsPath(e.target.value)} placeholder="data.bills" hint="Blank: the list in the response" />
          <Input label="Id field" value={idField} className="font-mono" onChange={e => setIdField(e.target.value)} hint="Unique per item" />
        </div>
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> Enabled
        </label>
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </form>
    </SlideOver>
  )
}
