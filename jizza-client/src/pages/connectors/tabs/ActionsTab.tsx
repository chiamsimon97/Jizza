import { useState, type FormEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { FlaskConical, Pencil, Plus, Trash2, Users } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { agentsApi } from '@/api/agents'
import { servicesApi } from '@/api/services'
import type { ServiceAction, ServiceApi } from '@/types'
import { Empty, JsonBlock, SectionHeader, Table, errorText, toMachineName } from '../shared'
import type { TabProps } from '../ServiceEditorPage'

/** What agents can do with the service. Each action runs one API and is handed to chosen agents. */
export function ActionsTab({ detail, refresh }: TabProps) {
  const [editing, setEditing] = useState<ServiceAction | 'new' | null>(null)
  const [assigning, setAssigning] = useState<ServiceAction | null>(null)
  const [testing, setTesting] = useState<ServiceAction | null>(null)
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const remove = useMutation({
    mutationFn: (id: string) => servicesApi.deleteAction(detail.service.id, id),
    onSuccess: refresh,
  })
  const apiOf = (id: string) => detail.apis.find(a => a.id === id)
  const agentNames = (ids: string[]) => (agents ?? []).filter(a => ids.includes(a.id)).map(a => a.name)

  return (
    <div>
      <SectionHeader title="Actions" description="Helper actions your agents can run. Agents only see them when the service is Live and the action is given to them."
        action={<Button size="sm" disabled={detail.apis.length === 0} onClick={() => setEditing('new')}><Plus className="h-4 w-4" />New action</Button>} />
      {detail.apis.length === 0 && <Empty>Add an API first; every action runs one.</Empty>}
      {detail.apis.length > 0 && detail.actions.length === 0 && <Empty>No actions yet.</Empty>}
      {detail.actions.length > 0 && (
        <Table head={['Action', 'API', 'Agents', '']}>
          {detail.actions.map(a => (
            <tr key={a.id}>
              <td className="px-4 py-3">
                <p className="font-medium text-gray-900 dark:text-gray-100">
                  {a.label} {!a.enabled && <Badge className="ml-1">disabled</Badge>}
                  {a.sideEffecting && <Badge variant="warning" className="ml-1">changes data</Badge>}
                </p>
                <p className="font-mono text-xs text-gray-500" title="The tool name agents see">{a.toolName}</p>
              </td>
              <td className="px-4 py-3 font-mono text-xs">{apiOf(a.apiId) ? `${apiOf(a.apiId)!.httpMethod} ${apiOf(a.apiId)!.pathTemplate}` : '—'}</td>
              <td className="px-4 py-3 text-xs text-gray-600 dark:text-gray-300">{agentNames(a.agentIds).join(', ') || 'None yet'}</td>
              <td className="px-4 py-3 text-right whitespace-nowrap">
                <Button size="sm" variant="ghost" aria-label={`Choose agents for ${a.label}`} onClick={() => setAssigning(a)}><Users className="h-3.5 w-3.5" /></Button>
                <Button size="sm" variant="ghost" aria-label={`Test ${a.label}`} onClick={() => setTesting(a)}><FlaskConical className="h-3.5 w-3.5" /></Button>
                <Button size="sm" variant="ghost" aria-label={`Edit ${a.label}`} onClick={() => setEditing(a)}><Pencil className="h-3.5 w-3.5" /></Button>
                <Button size="sm" variant="ghost" aria-label={`Delete ${a.label}`} onClick={() => {
                  if (window.confirm(`Delete ${a.label}? Agents that have it lose it.`)) remove.mutate(a.id)
                }}><Trash2 className="h-3.5 w-3.5" /></Button>
              </td>
            </tr>
          ))}
        </Table>
      )}
      {remove.error && <p className="mt-2 text-sm text-red-500">{errorText(remove.error)}</p>}

      {editing && <ActionForm detail={detail} action={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh() }} />}
      {assigning && <AssignAgents serviceId={detail.service.id} action={assigning} live={detail.service.versionStatus === 'live'}
        onClose={() => setAssigning(null)} onSaved={() => { setAssigning(null); refresh() }} />}
      {testing && <TestAction serviceId={detail.service.id} action={testing} api={apiOf(testing.apiId)}
        onClose={() => { setTesting(null); refresh() }} />}
    </div>
  )
}

function ActionForm({ detail, action, onClose, onSaved }: {
  detail: TabProps['detail']; action: ServiceAction | null; onClose: () => void; onSaved: () => void
}) {
  const [label, setLabel] = useState(action?.label ?? '')
  const [name, setName] = useState(action?.name ?? '')
  const [touched, setTouched] = useState(!!action)
  const [description, setDescription] = useState(action?.description ?? '')
  const [apiId, setApiId] = useState(action?.apiId ?? detail.apis.find(a => a.kind === 'request')?.id ?? detail.apis[0]?.id ?? '')
  const [resourceId, setResourceId] = useState(action?.resourceId ?? '')
  const [enabled, setEnabled] = useState(action?.enabled ?? true)
  const save = useMutation({
    mutationFn: () => servicesApi.saveAction(detail.service.id, action?.id, {
      name, label, description, apiId, resourceId: resourceId || null, enabled,
    }),
    onSuccess: onSaved,
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <SlideOver open title={action ? `Edit ${action.label}` : 'New action'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit" form="action-form" loading={save.isPending}>Save action</Button></>}>
      <form id="action-form" onSubmit={submit} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Input label="Display name" required value={label} placeholder="Create invoice"
            onChange={e => { setLabel(e.target.value); if (!touched) setName(toMachineName(e.target.value)) }} />
          <Input label="Machine name" required value={name} className="font-mono" onChange={e => { setTouched(true); setName(e.target.value) }}
            hint={`Agents see it as ${detail.service.slug}__${name || '…'}`} />
        </div>
        <Field label="Description" hint="Tell the agent when to use this and what it does. This is what the model reads.">
          <textarea className={fieldClass} rows={3} value={description} onChange={e => setDescription(e.target.value)} />
        </Field>
        <Field label="API" hint="The action's inputs are this API's inputs.">
          <select className={fieldClass} required value={apiId} onChange={e => setApiId(e.target.value)}>
            {detail.apis.map(a => <option key={a.id} value={a.id}>{a.label} ({a.httpMethod} {a.pathTemplate})</option>)}
          </select>
        </Field>
        <Field label="Resource">
          <select className={fieldClass} value={resourceId} onChange={e => setResourceId(e.target.value)}>
            <option value="">None</option>
            {detail.resources.map(r => <option key={r.id} value={r.id}>{r.label}</option>)}
          </select>
        </Field>
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> Enabled
        </label>
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </form>
    </SlideOver>
  )
}

function AssignAgents({ serviceId, action, live, onClose, onSaved }: {
  serviceId: string; action: ServiceAction; live: boolean; onClose: () => void; onSaved: () => void
}) {
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const [chosen, setChosen] = useState<string[]>(action.agentIds)
  const save = useMutation({
    mutationFn: () => servicesApi.assignAgents(serviceId, action.id, chosen),
    onSuccess: onSaved,
  })
  const toggle = (id: string) => setChosen(c => c.includes(id) ? c.filter(x => x !== id) : [...c, id])

  return (
    <SlideOver open title={`Who can use "${action.label}"?`} onClose={onClose}
      description={live ? 'Chosen agents can run it right away.' : 'Chosen agents can run it once the service is published.'}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
      <div className="flex flex-col gap-2">
        {(agents ?? []).map(a => (
          <label key={a.id} className="flex cursor-pointer items-center gap-3 rounded-lg border border-gray-200 p-3 dark:border-gray-800">
            <input type="checkbox" checked={chosen.includes(a.id)} onChange={() => toggle(a.id)} />
            <AgentLogo agentKey={a.key} state={a.enabled ? 'idle' : 'off'} size={32} />
            <span className="flex-1">
              <span className="block font-medium text-gray-900 dark:text-gray-100">{a.name}</span>
              <span className="block text-xs text-gray-500">{a.domain ?? a.key}</span>
            </span>
          </label>
        ))}
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </div>
    </SlideOver>
  )
}

function TestAction({ serviceId, action, api, onClose }: {
  serviceId: string; action: ServiceAction; api?: ServiceApi; onClose: () => void
}) {
  const props = ((api?.inputSchema as { properties?: Record<string, unknown> })?.properties) ?? {}
  const [input, setInput] = useState(JSON.stringify(Object.fromEntries(Object.keys(props).map(k => [k, ''])), null, 2))
  const [parseError, setParseError] = useState('')
  const run = useMutation({ mutationFn: (body: unknown) => servicesApi.testAction(serviceId, action.id, body) })

  function go() {
    try {
      const body = input.trim() ? JSON.parse(input) : {}
      setParseError('')
      if (action.sideEffecting && !window.confirm(`${action.label} changes data in the real service. Run it?`)) return
      run.mutate(body)
    } catch {
      setParseError('The input is not valid JSON')
    }
  }

  return (
    <SlideOver open title={`Test "${action.label}"`} description="Runs once against the real service and is recorded in History." onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Close</Button><Button loading={run.isPending} onClick={go}>Run</Button></>}>
      <div className="flex flex-col gap-4">
        <Field label="Input (JSON)">
          <textarea className={`${fieldClass} font-mono text-xs`} rows={8} value={input} onChange={e => setInput(e.target.value)} />
        </Field>
        {parseError && <p className="text-sm text-red-500">{parseError}</p>}
        {run.error && <p className="text-sm text-red-500">{errorText(run.error)}</p>}
        {run.data && (
          <div>
            <Badge variant={run.data.ok ? 'success' : 'danger'}>{run.data.ok ? 'Succeeded' : 'Failed'}</Badge>
            <div className="mt-2"><JsonBlock value={run.data.ok ? run.data.output : run.data.error} /></div>
          </div>
        )}
      </div>
    </SlideOver>
  )
}
