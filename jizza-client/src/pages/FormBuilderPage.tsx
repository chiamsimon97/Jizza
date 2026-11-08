import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Check, ClipboardList, Copy, Eye, Globe, Inbox, PenSquare, Save } from 'lucide-react'
import { formsApi, publicFormUrl, type FormDto } from '@/api/forms'
import { workflowsApi } from '@/api/workflows'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Switch } from '@/components/ui/Switch'
import { FieldPalette, type PaletteTab } from '@/components/formbuilder/FieldPalette'
import { FormCanvas } from '@/components/formbuilder/FormCanvas'
import { FieldSettings, FormSettingsPanel } from '@/components/formbuilder/FieldSettings'
import { FieldBlock } from '@/components/formbuilder/FieldInput'
import { FormSubmissions } from '@/components/formbuilder/FormSubmissions'
import {
  acceptAll, cleanFields, findField, insertAt, moveField, moveWithin, newField, newId, pendingCount,
  problemsOf, removeField, uniqueName, updateField, type FieldType, type FormField, type FormSettings,
} from '@/components/formbuilder/formModel'
import { layoutFor, stepsOf, type GraphStep } from '@/components/formbuilder/stepFields'
import type { WorkflowDto } from '@/types'

type View = 'build' | 'preview' | 'submissions'

/**
 * The form builder: fields from the left panel's Basic and Advanced tabs, or made from a
 * workflow's steps on its Workflow tab; the form in the middle; the selected field's settings,
 * or the form's own and the workflows it starts, on the right.
 */
export function FormBuilderPage() {
  const { formId } = useParams()
  const qc = useQueryClient()
  const { data: form, isLoading, error } = useQuery({ queryKey: ['form', formId], queryFn: () => formsApi.get(formId!), enabled: !!formId })
  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (error || !form) return <div className="page"><p className="text-sm text-red-600">Could not load the form{error ? `: ${(error as Error).message}` : ''}.</p><Link className="text-sm text-brand-600" to="/forms">Back to forms</Link></div>
  return <Builder key={form.id} form={form} onSaved={f => { qc.setQueryData(['form', f.id], f); void qc.invalidateQueries({ queryKey: ['forms'] }) }} />
}

function Builder({ form, onSaved }: { form: FormDto; onSaved: (f: FormDto) => void }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const [name, setName] = useState(form.name)
  const [description, setDescription] = useState(form.description ?? '')
  const [fields, setFields] = useState<FormField[]>(form.fields ?? [])
  const [settings, setSettings] = useState<FormSettings>(form.settings ?? {})
  const [dirty, setDirty] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [tab, setTab] = useState<PaletteTab>(params.get('tab') === 'workflow' || form.sourceWorkflowId ? 'workflow' : 'basic')
  const [view, setView] = useState<View>((params.get('view') as View) || 'build')
  const [notice, setNotice] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  const { data: workflows } = useQuery({ queryKey: ['workflows'], queryFn: () => workflowsApi.list() })
  const { data: allForms } = useQuery({ queryKey: ['forms'], queryFn: () => formsApi.list() })
  const problems = useMemo(() => problemsOf(fields), [fields])
  const pending = pendingCount(fields)

  const edit = (next: FormField[] | ((f: FormField[]) => FormField[])) => {
    setFields(next)
    setDirty(true)
  }

  const save = useMutation({
    mutationFn: () => formsApi.update(form.id, { name: name.trim() || 'Untitled form', description, fields: cleanFields(fields), settings }),
    onSuccess: saved => { onSaved(saved); setDirty(false); setNotice(null) },
    onError: e => setNotice((e as Error).message),
  })
  const publish = useMutation({
    mutationFn: async () => {
      if (dirty) onSaved(await formsApi.update(form.id, { name: name.trim() || 'Untitled form', description, fields: cleanFields(fields), settings }))
      setDirty(false)
      return form.status === 'published' && !dirty ? formsApi.unpublish(form.id) : formsApi.publish(form.id)
    },
    onSuccess: saved => { onSaved(saved); setNotice(saved.status === 'published' ? 'Published. Anyone with the link can fill it in.' : 'Unpublished. The link shows nothing until you publish again.') },
    onError: e => setNotice((e as Error).message),
  })
  const link = useMutation({
    mutationFn: (ids: string[]) => formsApi.setWorkflows(form.id, ids),
    onSuccess: saved => { onSaved(saved); void qc.invalidateQueries({ queryKey: ['workflows'] }) },
    onError: e => setNotice((e as Error).message),
  })
  const toggle = useMutation({
    mutationFn: ({ id, on }: { id: string; on: boolean }) => workflowsApi.setEnabled(id, on),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['workflows'] }); void qc.invalidateQueries({ queryKey: ['form', form.id] }) },
    onError: e => setNotice((e as Error).message),
  })

  // Ctrl/Cmd+S saves.
  const canSave = dirty && problems.size === 0 && !save.isPending
  const onKey = useCallback((e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') { e.preventDefault(); if (canSave) save.mutate() }
  }, [canSave, save])
  useEffect(() => { window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey) }, [onKey])
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const addField = (type: FieldType, index = fields.length) => {
    const f = newField(type, fields)
    edit(insertAt(fields, index, f))
    setSelected(f.id)
  }

  const addStep = (workflow: WorkflowDto, step: GraphStep, index = fields.length) => {
    const layout = layoutFor(workflow, step, fields)
    if (!layout.children?.length) {
      setNotice(`Every field “${step.label ?? step.id}” needs is already on the form.`)
      return
    }
    edit(insertAt(fields, index, layout))
    setSelected(null)
    // Fields made from this workflow only fill it when this form starts it.
    if (!form.workflows.some(w => w.id === workflow.id)) {
      if (!workflow.formId) {
        link.mutate([...form.workflows.map(w => w.id), workflow.id])
        setNotice(`“${workflow.name}” now starts when this form is sent${workflow.enabled ? '' : '. Turn it on to let it run'}.`)
      } else {
        setNotice(`“${workflow.name}” starts from another form. Choose it under “When someone sends this form” to start it from this one.`)
      }
    }
  }

  const insertStep = (workflowId: string, nodeId: string, index: number) => {
    const w = workflows?.find(x => x.id === workflowId)
    const s = w && stepsOf(w.graph).find(x => x.id === nodeId)
    if (w && s) addStep(w, s, index)
  }

  const duplicate = (id: string) => {
    const f = findField(fields, id)
    if (!f) return
    const copy: FormField = { ...f, id: newId(), label: `${f.label} copy`, ...(f.name ? { name: uniqueName(fields, f.name) } : {}), binding: undefined }
    const at = fields.findIndex(x => x.id === id)
    edit(insertAt(fields, at + 1, copy))
    setSelected(copy.id)
  }

  const current = findField(fields, selected)
  const url = form.publicToken ? publicFormUrl(form.publicToken) : null
  const published = form.status === 'published'
  const setView2 = (v: View) => { setView(v); setParams(p => { const n = new URLSearchParams(p); if (v === 'build') n.delete('view'); else n.set('view', v); n.delete('tab'); return n }, { replace: true }) }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex flex-wrap items-center gap-2 border-b border-gray-200 bg-white px-3 py-2 dark:border-gray-800 dark:bg-gray-950">
        <Button size="sm" variant="ghost" aria-label="Back to forms" onClick={() => navigate('/forms')}><ArrowLeft className="h-4 w-4" /></Button>
        <ClipboardList className="h-4 w-4 text-gray-500" />
        <input aria-label="Form name" value={name} onChange={e => { setName(e.target.value); setDirty(true) }}
          className="min-w-[10rem] flex-1 rounded-md border border-transparent bg-transparent px-1.5 py-1 text-base font-semibold text-gray-900 hover:border-gray-200 focus:border-brand-500 focus:outline-none dark:text-gray-100 dark:hover:border-gray-700 sm:flex-none sm:w-72" />
        <Badge variant={published ? 'success' : 'default'}>{published ? 'Published' : 'Draft'}</Badge>
        {dirty && <span className="text-xs text-gray-500">Unsaved changes</span>}
        <div role="tablist" aria-label="View" className="ml-auto flex rounded-lg bg-gray-100 p-0.5 dark:bg-gray-900">
          {([['build', 'Build', PenSquare], ['preview', 'Preview', Eye], ['submissions', `Submissions${form.submissionCount ? ` (${form.submissionCount})` : ''}`, Inbox]] as const).map(([v, label, Icon]) => (
            <button key={v} role="tab" aria-selected={view === v} type="button" onClick={() => setView2(v)}
              className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium ${view === v ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-800 dark:text-white' : 'text-gray-600 dark:text-gray-400'}`}>
              <Icon className="h-3.5 w-3.5" />{label}
            </button>
          ))}
        </div>
        {url && published && (
          <Button size="sm" variant="secondary" onClick={() => { void navigator.clipboard?.writeText(url); setCopied(true); setTimeout(() => setCopied(false), 1500) }}>
            {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}{copied ? 'Copied' : 'Copy link'}
          </Button>
        )}
        <Button size="sm" variant="secondary" disabled={!canSave} loading={save.isPending} onClick={() => save.mutate()}
          title={problems.size ? 'Fix the fields marked in red first' : undefined}><Save className="h-4 w-4" />Save</Button>
        <Button size="sm" variant={published && !dirty ? 'secondary' : 'primary'} loading={publish.isPending} disabled={problems.size > 0}
          onClick={() => publish.mutate()}>
          <Globe className="h-4 w-4" />{published ? (dirty ? 'Save and publish' : 'Unpublish') : 'Publish'}
        </Button>
      </header>
      {(notice || (pending > 0 && view === 'build')) && (
        <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 bg-gray-50 px-4 py-1.5 text-xs text-gray-700 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300">
          {notice && <span>{notice}</span>}
          {pending > 0 && view === 'build' && <span className="text-amber-700 dark:text-amber-300">{pending} suggested field{pending === 1 ? '' : 's'} waiting to be accepted.</span>}
          {notice && <button type="button" className="ml-auto text-gray-500 underline" onClick={() => setNotice(null)}>Dismiss</button>}
        </div>
      )}

      {view === 'build' && (
        <div className="grid min-h-0 flex-1 grid-cols-1 md:grid-cols-[300px_minmax(0,1fr)] xl:grid-cols-[300px_minmax(0,1fr)_320px]">
          <div className="hidden min-h-0 md:block">
            <FieldPalette tab={tab} onTab={setTab} workflows={workflows} focusWorkflowId={form.sourceWorkflowId}
              onAddField={t => addField(t)} onAddStep={(w, s) => addStep(w, s)} />
          </div>
          <div className="min-h-0 overflow-y-auto bg-gray-50 px-4 py-6 dark:bg-gray-900/50">
            <div className="mx-auto max-w-2xl">
              <div className="mb-4 rounded-xl border border-gray-200 bg-white p-5 dark:border-gray-800 dark:bg-gray-950">
                <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{name || 'Untitled form'}</h1>
                {description && <p className="mt-1 whitespace-pre-wrap text-sm text-gray-500">{description}</p>}
              </div>
              <FormCanvas fields={fields} selected={selected} problems={problems} onSelect={setSelected}
                onInsertField={(t, i) => addField(t, i)} onInsertStep={insertStep}
                onMove={(id, i) => edit(f => moveField(f, id, i))} onMoveWithin={(l, id, i) => edit(f => moveWithin(f, l, id, i))}
                onRemove={id => { edit(f => removeField(f, id)); if (selected === id) setSelected(null) }}
                onDuplicate={duplicate}
                onAccept={id => edit(f => updateField(f, id, { pending: undefined }))}
                onAcceptAll={id => edit(f => acceptAll(f, id))} />
              <p className="mt-6 text-center text-xs text-gray-400 md:hidden">Open this page on a wider screen to drag in fields.</p>
            </div>
          </div>
          <aside aria-label="Settings" className="min-h-0 overflow-y-auto border-l border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-950 md:col-span-2 xl:col-span-1">
            {current && current.type !== 'step_layout'
              ? <FieldSettings field={current} fields={fields} forms={allForms} currentFormId={form.id} problem={problems.get(current.id)}
                  onChange={patch => edit(f => updateField(f, current.id, patch))} />
              : <>
                  <FormSettingsPanel name={name} description={description} submitLabel={settings.submitLabel ?? ''} successMessage={settings.successMessage ?? ''}
                    onChange={p => {
                      if (p.name !== undefined) setName(p.name)
                      if (p.description !== undefined) setDescription(p.description)
                      if (p.submitLabel !== undefined || p.successMessage !== undefined) setSettings(s => ({ ...s, ...(p.submitLabel !== undefined ? { submitLabel: p.submitLabel || undefined } : {}), ...(p.successMessage !== undefined ? { successMessage: p.successMessage || undefined } : {}) }))
                      setDirty(true)
                    }} />
                  <StartsWorkflows form={form} workflows={workflows} busy={link.isPending || toggle.isPending}
                    onLink={ids => link.mutate(ids)} onToggle={(id, on) => toggle.mutate({ id, on })} />
                </>}
          </aside>
        </div>
      )}

      {view === 'preview' && (
        <div className="min-h-0 flex-1 overflow-y-auto bg-gray-50 px-4 py-8 dark:bg-gray-900/50">
          <PreviewForm name={name} description={description} fields={fields} submitLabel={settings.submitLabel} />
        </div>
      )}

      {view === 'submissions' && (
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6">
          <FormSubmissions form={{ ...form, fields }} />
        </div>
      )}
    </div>
  )
}

/** The workflows a submission starts, each with its on/off switch. */
function StartsWorkflows({ form, workflows, busy, onLink, onToggle }: {
  form: FormDto; workflows?: WorkflowDto[]; busy: boolean; onLink: (ids: string[]) => void; onToggle: (id: string, on: boolean) => void
}) {
  const linked = new Set(form.workflows.map(w => w.id))
  return (
    <section className="mt-6 border-t border-gray-200 pt-4 dark:border-gray-800">
      <h2 className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">When someone sends this form</h2>
      <p className="mt-1 text-xs text-gray-500">Start these workflows with the answers as their input. A workflow runs only while it is on.</p>
      {!workflows?.length && <p className="mt-3 text-xs text-gray-500">You have no workflows yet.</p>}
      <ul className="mt-3 space-y-2">
        {workflows?.map(w => {
          const on = linked.has(w.id)
          const elsewhere = !on && w.formId && w.formId !== form.id
          return (
            <li key={w.id} className="flex items-center gap-2">
              <label className="flex min-w-0 flex-1 items-center gap-2 text-sm text-gray-800 dark:text-gray-200">
                <input type="checkbox" disabled={busy} checked={on}
                  onChange={e => onLink(e.target.checked ? [...linked, w.id] : [...linked].filter(x => x !== w.id))} />
                <span className="min-w-0">
                  <span className="block truncate">{w.name}</span>
                  {elsewhere && <span className="block text-[11px] text-gray-500">Starts from another form now</span>}
                </span>
              </label>
              {on && (
                <span className="flex items-center gap-1.5 text-[11px] text-gray-500">
                  {w.enabled ? 'On' : 'Off'}
                  <Switch checked={!!w.enabled} disabled={busy} label={`Turn ${w.name} ${w.enabled ? 'off' : 'on'}`} onChange={v => onToggle(w.id, v)} />
                </span>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function PreviewForm({ name, description, fields, submitLabel }: { name: string; description: string; fields: FormField[]; submitLabel?: string }) {
  const [values, setValues] = useState<Record<string, unknown>>({})
  const shown = fields.flatMap(f => (f.pending ? [] : f.type === 'step_layout'
    ? [{ ...f, children: (f.children ?? []).filter(c => !c.pending) }].filter(l => l.children.length) : [f]))
  return (
    <div className="mx-auto max-w-2xl rounded-2xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-800 dark:bg-gray-950">
      <p className="mb-4 rounded-lg bg-gray-50 px-3 py-1.5 text-xs text-gray-500 dark:bg-gray-900">Preview: this is how people see the form. Answers here are not sent.</p>
      <h1 className="text-2xl font-semibold text-gray-900 dark:text-gray-100">{name || 'Untitled form'}</h1>
      {description && <p className="mt-1 whitespace-pre-wrap text-sm text-gray-500">{description}</p>}
      <div className="mt-6 space-y-5">
        {shown.map(f => f.type === 'step_layout'
          ? <div key={f.id} className="space-y-5">{f.children!.map(c => <FieldBlock key={c.id} field={c} value={values[c.name ?? c.id]} onChange={v => setValues(s => ({ ...s, [c.name ?? c.id]: v }))} />)}</div>
          : <FieldBlock key={f.id} field={f} value={values[f.name ?? f.id]} onChange={v => setValues(s => ({ ...s, [f.name ?? f.id]: v }))} />)}
      </div>
      <Button className="mt-6" disabled>{submitLabel || 'Submit'}</Button>
    </div>
  )
}
