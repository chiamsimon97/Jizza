import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Pencil, Plus, Trash2, X } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import type { ServiceApi } from '@/types'
import { Empty, SectionHeader, Table, errorText, toMachineName } from '../shared'
import type { TabProps } from '../ServiceEditorPage'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'

type ParamIn = 'path' | 'query' | 'header' | 'body'
interface Param { name: string; in: ParamIn; type: string; required: boolean; description: string }
interface Header { name: string; value: string }

const METHOD_COLOURS: Record<string, string> = {
  GET: 'text-green-600 dark:text-green-400', POST: 'text-blue-600 dark:text-blue-400',
  PUT: 'text-amber-600 dark:text-amber-400', PATCH: 'text-amber-600 dark:text-amber-400',
  DELETE: 'text-red-600 dark:text-red-400',
}

/** Raw HTTP requests the service's actions and triggers are built on. */
export function ApisTab({ detail, refresh }: TabProps) {
  const [editing, setEditing] = useState<ServiceApi | 'new' | null>(null)
  const remove = useMutation({
    mutationFn: (apiId: string) => servicesApi.deleteApi(detail.service.id, apiId),
    onSuccess: refresh,
  })
  const usedBy = (apiId: string) =>
    [...detail.actions.filter(a => a.apiId === apiId).map(a => a.label),
     ...detail.triggers.filter(t => t.apiId === apiId).map(t => t.label)]

  return (
    <div>
      <SectionHeader title="APIs" description="Raw requests to the service. Paths are called on the base URL; use {param} for path inputs and {{var.name}} for variables."
        action={<Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" />New API</Button></Can>} />
      {detail.apis.length === 0 ? <Empty>No APIs yet. Add one for each request your actions and triggers need.</Empty> : (
        <Table head={['Name', 'Request', 'Kind', 'Used by', '']}>
          {detail.apis.map(a => (
            <tr key={a.id}>
              <td className="px-4 py-3">
                <p className="font-medium text-gray-900 dark:text-gray-100">{a.label}</p>
                <p className="font-mono text-xs text-gray-500">{a.name}</p>
              </td>
              <td className="px-4 py-3 font-mono text-xs">
                <span className={`font-semibold ${METHOD_COLOURS[a.httpMethod] ?? ''}`}>{a.httpMethod}</span> {a.pathTemplate}
              </td>
              <td className="px-4 py-3">
                <Badge>{a.kind}</Badge>
                {a.sideEffecting && <Badge variant="warning" className="ml-1">changes data</Badge>}
                {!a.enabled && <Badge className="ml-1">disabled</Badge>}
              </td>
              <td className="px-4 py-3 text-xs text-gray-500 dark:text-gray-400">{usedBy(a.id).join(', ') || '—'}</td>
              <td className="px-4 py-3 text-right whitespace-nowrap">
                <Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" variant="ghost" aria-label={`Edit ${a.label}`} onClick={() => setEditing(a)}><Pencil className="h-3.5 w-3.5" /></Button></Can>
                <Can permission={PERMISSIONS.CONNECTOR_DELETE} mode="disable"><Button size="sm" variant="ghost" aria-label={`Delete ${a.label}`} onClick={() => {
                  const users = usedBy(a.id)
                  if (window.confirm(users.length ? `Deleting ${a.label} also deletes ${users.join(', ')}. Continue?` : `Delete ${a.label}?`)) remove.mutate(a.id)
                }}><Trash2 className="h-3.5 w-3.5" /></Button></Can>
              </td>
            </tr>
          ))}
        </Table>
      )}
      {editing && <ApiForm serviceId={detail.service.id} api={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh() }} />}
    </div>
  )
}

function ApiForm({ serviceId, api, onClose, onSaved }: {
  serviceId: string; api: ServiceApi | null; onClose: () => void; onSaved: () => void
}) {
  const initial = fromApi(api)
  const [label, setLabel] = useState(api?.label ?? '')
  const [name, setName] = useState(api?.name ?? '')
  const [nameTouched, setNameTouched] = useState(!!api)
  const [description, setDescription] = useState(api?.description ?? '')
  const [kind, setKind] = useState(api?.kind ?? 'request')
  const [method, setMethod] = useState(api?.httpMethod ?? 'GET')
  const [path, setPath] = useState(api?.pathTemplate ?? '/')
  const [params, setParams] = useState<Param[]>(initial.params)
  const [headers, setHeaders] = useState<Header[]>(initial.headers)
  const [sideEffecting, setSideEffecting] = useState<boolean | null>(api ? api.sideEffecting : null)

  const save = useMutation({
    mutationFn: () => servicesApi.saveApi(serviceId, api?.id, {
      name, label, description, kind, httpMethod: method, pathTemplate: path,
      ...toSchema(params, headers),
      sideEffecting: sideEffecting ?? method !== 'GET',
    }),
    onSuccess: onSaved,
  })

  // Path placeholders become path inputs automatically, so the two never drift apart.
  function onPath(value: string) {
    setPath(value)
    const names = [...value.matchAll(/\{(?!\{)([A-Za-z0-9_]+)\}/g)].map(m => m[1])
    setParams(ps => {
      const kept = ps.filter(p => p.in !== 'path' || names.includes(p.name))
      for (const n of names) {
        if (!kept.some(p => p.in === 'path' && p.name === n)) kept.push({ name: n, in: 'path', type: 'string', required: true, description: '' })
      }
      return kept
    })
  }

  const update = (i: number, patch: Partial<Param>) => setParams(ps => ps.map((p, j) => j === i ? { ...p, ...patch } : p))

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <SlideOver open title={api ? `Edit ${api.label}` : 'New API'} onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button type="submit" form="api-form" loading={save.isPending}>Save API</Button>
      </>}>
      <form id="api-form" onSubmit={submit} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Input label="Display name" required value={label} onChange={e => {
            setLabel(e.target.value)
            if (!nameTouched) setName(toMachineName(e.target.value))
          }} placeholder="List bills" />
          <Input label="Machine name" required value={name} onChange={e => { setNameTouched(true); setName(e.target.value) }}
            className="font-mono" hint="lower_case_with_underscores" />
        </div>
        <Field label="Description" hint="Agents read this when the API backs an action.">
          <textarea className={fieldClass} rows={2} value={description} onChange={e => setDescription(e.target.value)} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-[120px_1fr]">
          <Field label="Method">
            <select className={fieldClass} value={method} onChange={e => setMethod(e.target.value)}>
              {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(m => <option key={m}>{m}</option>)}
            </select>
          </Field>
          <Input label="Path" required value={path} onChange={e => onPath(e.target.value)} className="font-mono" placeholder="/bills/{bill_id}" />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Kind" hint="Polling APIs list items for a trigger.">
            <select className={fieldClass} value={kind} onChange={e => setKind(e.target.value as ServiceApi['kind'])}>
              <option value="request">Request</option><option value="polling">Polling (list)</option><option value="webhook">Webhook payload</option>
            </select>
          </Field>
          <label className="flex items-center gap-2 self-end pb-2 text-sm text-gray-700 dark:text-gray-300">
            <input type="checkbox" checked={sideEffecting ?? method !== 'GET'} onChange={e => setSideEffecting(e.target.checked)} />
            Changes data (agents ask before running it)
          </label>
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Inputs</span>
            <Button type="button" size="sm" variant="ghost" onClick={() => setParams(ps => [...ps, { name: '', in: method === 'GET' ? 'query' : 'body', type: 'string', required: false, description: '' }])}>
              <Plus className="h-3.5 w-3.5" />Add input</Button>
          </div>
          {params.length === 0 && <p className="text-xs text-gray-500">No inputs.</p>}
          <div className="flex flex-col gap-2">
            {params.map((p, i) => (
              <div key={i} className="grid grid-cols-[1fr_96px_96px_auto_auto] items-center gap-2">
                <input className={`${fieldClass} font-mono`} placeholder="name" value={p.name} disabled={p.in === 'path'}
                  onChange={e => update(i, { name: e.target.value })} aria-label="Input name" />
                <select className={fieldClass} value={p.in} disabled={p.in === 'path'} onChange={e => update(i, { in: e.target.value as ParamIn })} aria-label="Sent in">
                  {p.in === 'path' && <option value="path">path</option>}
                  <option value="query">query</option><option value="header">header</option><option value="body">body</option>
                </select>
                <select className={fieldClass} value={p.type} onChange={e => update(i, { type: e.target.value })} aria-label="Type">
                  {['string', 'number', 'integer', 'boolean', 'object', 'array'].map(t => <option key={t}>{t}</option>)}
                </select>
                <label className="flex items-center gap-1 text-xs text-gray-600 dark:text-gray-400">
                  <input type="checkbox" checked={p.required} onChange={e => update(i, { required: e.target.checked })} />req.
                </label>
                <button type="button" aria-label="Remove input" disabled={p.in === 'path'} className="text-gray-400 hover:text-red-500 disabled:opacity-30"
                  onClick={() => setParams(ps => ps.filter((_, j) => j !== i))}><X className="h-4 w-4" /></button>
                <input className={`${fieldClass} col-span-5 text-xs`} placeholder="What this input means (agents read it)" value={p.description}
                  onChange={e => update(i, { description: e.target.value })} aria-label="Input description" />
              </div>
            ))}
          </div>
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Fixed headers</span>
            <Button type="button" size="sm" variant="ghost" onClick={() => setHeaders(hs => [...hs, { name: '', value: '' }])}>
              <Plus className="h-3.5 w-3.5" />Add header</Button>
          </div>
          {headers.map((h, i) => (
            <div key={i} className="mb-2 grid grid-cols-[1fr_1.4fr_auto] items-center gap-2">
              <input className={`${fieldClass} font-mono`} placeholder="X-Org-Id" value={h.name} aria-label="Header name"
                onChange={e => setHeaders(hs => hs.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} />
              <input className={`${fieldClass} font-mono`} placeholder="{{var.org_id}}" value={h.value} aria-label="Header value"
                onChange={e => setHeaders(hs => hs.map((x, j) => j === i ? { ...x, value: e.target.value } : x))} />
              <button type="button" aria-label="Remove header" className="text-gray-400 hover:text-red-500"
                onClick={() => setHeaders(hs => hs.filter((_, j) => j !== i))}><X className="h-4 w-4" /></button>
            </div>
          ))}
        </div>
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </form>
    </SlideOver>
  )
}

type Schema = { type?: string; description?: string; properties?: Record<string, Schema>; required?: string[] }

/**
 * Turns the input list into the JSON schema agents see and the mapping the executor follows. Body
 * inputs are gathered under one "body" object, unless the only body input is itself an object, in
 * which case it is the body.
 */
function toSchema(params: Param[], headers: Header[]) {
  const properties: Record<string, Schema> = {}
  const required: string[] = []
  const mapping: Record<string, unknown> = { path: [], query: [], header: [] }
  const body = params.filter(p => p.in === 'body' && p.name)
  for (const p of params.filter(p => p.in !== 'body' && p.name)) {
    properties[p.name] = { type: p.type, ...(p.description ? { description: p.description } : {}) }
    if (p.required) required.push(p.name);
    (mapping[p.in] as string[]).push(p.name)
  }
  if (body.length === 1 && body[0].type === 'object') {
    properties[body[0].name] = { type: 'object', ...(body[0].description ? { description: body[0].description } : {}) }
    if (body[0].required) required.push(body[0].name)
    mapping.bodyProperty = body[0].name
  } else if (body.length > 0) {
    properties.body = {
      type: 'object', description: 'The request body',
      properties: Object.fromEntries(body.map(p => [p.name, { type: p.type, ...(p.description ? { description: p.description } : {}) }])),
      required: body.filter(p => p.required).map(p => p.name),
    }
    if (body.some(p => p.required)) required.push('body')
    mapping.bodyProperty = 'body'
  }
  const fixed = headers.filter(h => h.name)
  if (fixed.length) mapping.headers = Object.fromEntries(fixed.map(h => [h.name, h.value]))
  return { inputSchema: { type: 'object', properties, required }, paramMapping: mapping }
}

/** The reverse of {@link toSchema}, so APIs imported from an API document can be edited too. */
function fromApi(api: ServiceApi | null): { params: Param[]; headers: Header[] } {
  if (!api) return { params: [], headers: [] }
  const schema = (api.inputSchema ?? {}) as Schema
  const mapping = (api.paramMapping ?? {}) as { path?: string[]; query?: string[]; header?: string[]; bodyProperty?: string; headers?: Record<string, string> }
  const required = new Set(schema.required ?? [])
  const params: Param[] = []
  for (const [name, prop] of Object.entries(schema.properties ?? {})) {
    if (name === mapping.bodyProperty) {
      if (prop.properties && Object.keys(prop.properties).length > 0) {
        const bodyRequired = new Set(prop.required ?? [])
        for (const [n, p] of Object.entries(prop.properties)) {
          params.push({ name: n, in: 'body', type: p.type ?? 'string', required: bodyRequired.has(n), description: p.description ?? '' })
        }
      } else {
        params.push({ name, in: 'body', type: 'object', required: required.has(name), description: prop.description ?? '' })
      }
      continue
    }
    const where: ParamIn = mapping.path?.includes(name) ? 'path' : mapping.header?.includes(name) ? 'header' : 'query'
    params.push({ name, in: where, type: prop.type ?? 'string', required: required.has(name), description: prop.description ?? '' })
  }
  const headers = Object.entries(mapping.headers ?? {}).map(([name, value]) => ({ name, value }))
  return { params, headers }
}
