import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, Plus, Trash2 } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { Input } from '@/components/ui/Input'
import { ModeSelect } from '@/components/guardrails/ModeSelect'
import { ACTION_LABELS, ACTION_TYPES } from '@/components/guardrails/labels'
import { agentsApi, orgAgentTemplatesApi } from '@/api/agents'
import { useAuthorization } from '@/auth/useAuthorization'
import { CREATED_PROFILE, Section, errorText } from '@/components/agents/agentParts'
import type { ActionType, AgentBlueprint, GuardrailMode, OrgAgentTemplate } from '@/types'

const KEY = ['agent-templates', 'organization'] as const

const blank = (i: number): AgentBlueprint => ({
  ref: `a${i}`, name: '', description: '', instructions: '', tools: [], modes: {}, locks: [], reportsTo: i > 1 ? 'a1' : null,
})

/**
 * Agents and teams the organization shares. Members add a copy to their own team; publishing a
 * new version offers them the update, and the copy's rules only ever get stricter on their own.
 */
export function OrganizationAgentTemplatesPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: KEY, queryFn: orgAgentTemplatesApi.list })
  const [editing, setEditing] = useState<OrgAgentTemplate | 'new' | null>(null)
  const [error, setError] = useState('')
  const refresh = () => qc.invalidateQueries({ queryKey: KEY })
  const act = (fn: () => Promise<unknown>) => fn().then(refresh).catch(e => setError(errorText(e, 'That did not work')))

  return (
    <div className="page">
      <PageHeader icon={Bot} title="Agent templates"
        description="Agents and teams your members can add to their own team. Your organization's locks on its connectors apply to every copy."
        actions={<Button onClick={() => setEditing('new')}><Plus className="h-4 w-4" /> New template</Button>} />
      {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
      {editing && <TemplateEditor template={editing === 'new' ? null : editing} onDone={() => { setEditing(null); void refresh() }} />}
      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {!isLoading && data?.length === 0 && !editing && (
        <EmptyState icon={Bot} title="No templates yet" description="Make one to give every member the same well-set-up agent." />
      )}
      <div className="flex flex-col gap-3">
        {data?.map(t => (
          <Section key={t.id} title={t.name} hint={`${t.description ?? ''} · ${t.agents.map(a => a.name).join(', ')} · version ${t.version}`}
            actions={<div className="flex flex-wrap items-center gap-1">
              <Badge variant={t.status === 'published' ? 'success' : 'default'}>{t.status}</Badge>
              <Button size="sm" variant="ghost" onClick={() => setEditing(t)}>Edit</Button>
              {t.status !== 'published'
                ? <Button size="sm" onClick={() => act(() => orgAgentTemplatesApi.publish(t.id))}>Publish</Button>
                : <Button size="sm" variant="secondary" onClick={() => act(() => orgAgentTemplatesApi.unpublish(t.id,
                    window.confirm('Also pause the copies members already have? Cancel keeps them working.')))}>Unpublish</Button>}
              {t.status !== 'published' && (
                <Button size="sm" variant="ghost" aria-label="Delete" onClick={() => act(() => orgAgentTemplatesApi.remove(t.id))}><Trash2 className="h-3.5 w-3.5" /></Button>
              )}
            </div>}>
            <span />
          </Section>
        ))}
      </div>
    </div>
  )
}

function TemplateEditor({ template, onDone }: { template: OrgAgentTemplate | null; onDone: () => void }) {
  const { data: auth } = useAuthorization()
  const { data: options } = useQuery({ queryKey: ['agent-tool-options'], queryFn: () => agentsApi.toolOptions() })
  const orgTools = (options ?? []).filter(o => o.workspaceId === auth?.activeWorkspace?.id)
  const [name, setName] = useState(template?.name ?? '')
  const [description, setDescription] = useState(template?.description ?? '')
  const [agents, setAgents] = useState<AgentBlueprint[]>(template?.agents.length ? template.agents : [blank(1)])
  const [error, setError] = useState('')
  const save = useMutation({
    mutationFn: () => template
      ? orgAgentTemplatesApi.update(template.id, { name, description, definition: { agents } })
      : orgAgentTemplatesApi.create({ name, description, definition: { agents } }),
    onSuccess: onDone,
    onError: (e: unknown) => setError(errorText(e, 'Could not save the template')),
  })
  const setAgent = (i: number, patch: Partial<AgentBlueprint>) => setAgents(agents.map((a, j) => j === i ? { ...a, ...patch } : a))

  return (
    <Section title={template ? `Edit ${template.name}` : 'New template'} hint="Saving a published template makes a new version; members choose when to take it.">
      <div className="flex flex-col gap-3">
        <Input label="Template name" value={name} maxLength={80} onChange={e => setName(e.target.value)} />
        <Field label="What it's for"><input className={fieldClass} value={description} maxLength={300} onChange={e => setDescription(e.target.value)} /></Field>
        {agents.map((a, i) => (
          <div key={a.ref ?? i} className="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
            <div className="grid gap-3 sm:grid-cols-2">
              <Input label={i === 0 ? 'Lead agent name' : 'Agent name'} value={a.name} maxLength={60} onChange={e => setAgent(i, { name: e.target.value })} />
              {i > 0 && (
                <Field label="Reports to">
                  <select className={fieldClass} value={a.reportsTo ?? ''} onChange={e => setAgent(i, { reportsTo: e.target.value || null })}>
                    {agents.slice(0, i).map(m => <option key={m.ref ?? m.name} value={m.ref ?? ''}>{m.name || 'Unnamed'}</option>)}
                  </select>
                </Field>
              )}
            </div>
            <Field label="When to use it"><input className={fieldClass} value={a.description ?? ''} maxLength={300} onChange={e => setAgent(i, { description: e.target.value })} /></Field>
            <Field label="Instructions" hint="Shown in full to members before they add it.">
              <textarea className={fieldClass} rows={5} maxLength={4000} value={a.instructions ?? ''} onChange={e => setAgent(i, { instructions: e.target.value })} />
            </Field>
            {orgTools.length > 0 && (
              <div className="mt-2">
                <p className="text-sm font-medium text-gray-700 dark:text-gray-300">Organization tools</p>
                <div className="flex flex-wrap gap-x-4">
                  {orgTools.map(o => {
                    const on = a.tools?.some(t => t.type === o.type && t.ref === o.ref)
                    return (
                      <label key={`${o.type}:${o.ref}`} className="inline-flex items-center gap-1.5 text-sm">
                        <input type="checkbox" checked={!!on} onChange={() => setAgent(i, {
                          tools: on ? a.tools!.filter(t => !(t.type === o.type && t.ref === o.ref)) : [...(a.tools ?? []), { type: o.type, ref: o.ref }],
                        })} />
                        {o.name}{o.actionType ? ` (${ACTION_LABELS[o.actionType].label})` : ''}
                      </label>
                    )
                  })}
                </div>
              </div>
            )}
            <div className="mt-2 flex flex-col gap-1">
              {ACTION_TYPES.filter(t => t !== 'READ' && t !== 'DRAFT').map(t => {
                const mode = (a.modes?.[t] ?? CREATED_PROFILE[t]) as GuardrailMode
                return (
                  <div key={t} className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="w-36 text-gray-700 dark:text-gray-300">{ACTION_LABELS[t].label}</span>
                    <ModeSelect label={ACTION_LABELS[t].label} value={mode} onChange={m => setAgent(i, { modes: { ...a.modes, [t]: m } })} />
                    <label className="inline-flex items-center gap-1 text-xs text-gray-500">
                      <input type="checkbox" checked={!!a.locks?.includes(t)} onChange={() => setAgent(i, {
                        locks: a.locks?.includes(t) ? a.locks.filter(l => l !== t) : [...(a.locks ?? []), t as ActionType],
                      })} /> Lock for its team
                    </label>
                  </div>
                )
              })}
            </div>
            {i > 0 && <Button className="mt-2" size="sm" variant="ghost" onClick={() => setAgents(agents.filter((_, j) => j !== i))}>Remove this agent</Button>}
          </div>
        ))}
        <div><Button size="sm" variant="secondary" onClick={() => setAgents([...agents, blank(agents.length + 1)])}><Plus className="h-3.5 w-3.5" /> Add a report</Button></div>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <div className="flex gap-2">
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!name.trim()}>Save</Button>
          <Button variant="ghost" onClick={onDone}>Cancel</Button>
        </div>
      </div>
    </Section>
  )
}
