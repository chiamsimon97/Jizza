import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Copy, FlaskConical, Pencil, Plus, Trash2 } from 'lucide-react'
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

/**
 * Events in the service: found by polling a list API for items not seen before, or sent by the
 * service itself to the trigger's own address the moment they happen (instant).
 */
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
      <SectionHeader title="Triggers" description="A polling trigger checks a list API on its interval while the service is Live; the first check only takes a baseline. An instant trigger gives the service an address to call the moment something happens. New items are recorded in History and start the workflows that listen to the trigger."
        action={<Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" />New trigger</Button></Can>} />
      {detail.triggers.length === 0 && <Empty>No triggers yet.</Empty>}
      {detail.triggers.length > 0 && (
        <Table head={['Trigger', 'Source', 'Every', 'Last event', '']}>
          {detail.triggers.map(t => (
            <tr key={t.id}>
              <td className="px-4 py-3">
                <p className="font-medium text-gray-900 dark:text-gray-100">{t.label} {!t.enabled && <Badge className="ml-1">disabled</Badge>}</p>
                <p className="font-mono text-xs text-gray-500">{t.name}</p>
              </td>
              <td className="px-4 py-3 text-xs">
                {t.kind === 'webhook'
                  ? <><Badge variant="success">Instant</Badge>{t.signed && <span className="ml-1 text-gray-500">signed</span>}</>
                  : <span className="font-mono">{(t.apiId && apiOf(t.apiId)?.label) ?? '—'}</span>}
                {!!t.workflowIds?.length && <p className="mt-1 text-gray-500">Starts {t.workflowIds.length} workflow{t.workflowIds.length === 1 ? '' : 's'}</p>}
              </td>
              <td className="px-4 py-3 text-xs">{t.kind === 'webhook' ? 'When sent' : `${t.pollIntervalMinutes} min`}</td>
              <td className="px-4 py-3 text-xs text-gray-500">{when(t.kind === 'webhook' ? t.lastReceivedAt : t.lastPolledAt)}</td>
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
          {test.data?.ok && detail.triggers.find(t => t.id === tested)?.kind === 'webhook' && (
            <p className="text-sm text-emerald-700 dark:text-emerald-300">Events are arriving. The latest is in History.</p>
          )}
          {test.data?.ok && detail.triggers.find(t => t.id === tested)?.kind !== 'webhook' && <>
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

function when(iso?: string | null) {
  return iso ? new Date(iso).toLocaleString() : 'Not yet'
}

/** The address an instant trigger gives the service, with a copy button. */
function WebhookAddress({ url }: { url: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="flex items-center gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 dark:border-gray-800 dark:bg-gray-900">
      <code className="min-w-0 flex-1 truncate text-xs" title={url}>{url}</code>
      <Button size="sm" variant="ghost" type="button" aria-label="Copy address" onClick={() => {
        void navigator.clipboard?.writeText(url).then(() => setCopied(true), () => setCopied(false))
      }}><Copy className="h-3.5 w-3.5" />{copied ? 'Copied' : 'Copy'}</Button>
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
  const [kind, setKind] = useState<ServiceTrigger['kind']>(trigger?.kind ?? (getApis.length ? 'polling' : 'webhook'))
  const [apiId, setApiId] = useState(trigger?.apiId ?? (getApis.find(a => a.kind === 'polling') ?? getApis[0])?.id ?? '')
  const [secret, setSecret] = useState('')
  const [header, setHeader] = useState(trigger?.signatureHeader ?? '')
  const [prefix, setPrefix] = useState(trigger?.signaturePrefix ?? '')
  const [clearSecret, setClearSecret] = useState(false)
  const [resourceId, setResourceId] = useState(trigger?.resourceId ?? '')
  const [interval, setInterval] = useState(String(trigger?.pollIntervalMinutes ?? 15))
  const [itemsPath, setItemsPath] = useState(trigger?.itemsPath ?? '')
  const [idField, setIdField] = useState(trigger?.idField ?? 'id')
  const [enabled, setEnabled] = useState(trigger?.enabled ?? true)
  const save = useMutation({
    mutationFn: () => servicesApi.saveTrigger(detail.service.id, trigger?.id, {
      name, label, description, kind, apiId: kind === 'polling' ? apiId : null, resourceId: resourceId || null,
      pollIntervalMinutes: Number(interval), itemsPath, idField, enabled,
      ...(kind === 'webhook' ? {
        signingSecret: clearSecret ? '' : secret || undefined,
        signatureHeader: header, signaturePrefix: prefix,
      } : {}),
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
        <Field label="How Jizza hears about it">
          <div className="flex flex-col gap-1.5 text-sm text-gray-700 dark:text-gray-300" role="radiogroup">
            <label className="flex items-start gap-2"><input type="radio" name="kind" checked={kind === 'polling'} onChange={() => setKind('polling')} />
              <span>Jizza checks a list API every few minutes</span></label>
            <label className="flex items-start gap-2"><input type="radio" name="kind" checked={kind === 'webhook'} onChange={() => setKind('webhook')} />
              <span>Instant: the service calls Jizza when it happens (a webhook)</span></label>
          </div>
        </Field>
        {kind === 'polling' && (
          <Field label="API to poll" hint="A GET request that lists recent items. It is called without inputs.">
            <select className={fieldClass} required value={apiId} onChange={e => setApiId(e.target.value)}>
              {getApis.length === 0 && <option value="">No GET APIs yet. Add one on the APIs tab.</option>}
              {getApis.map(a => <option key={a.id} value={a.id}>{a.label} ({a.pathTemplate})</option>)}
            </select>
          </Field>
        )}
        {kind === 'webhook' && (
          <div className="flex flex-col gap-3 rounded-xl border border-gray-200 p-3 dark:border-gray-800">
            {trigger?.webhookUrl
              ? <Field label="Address for the service" hint="Paste it into the service's webhook settings. Anyone with it can send events, so keep it private or set a signing secret."><WebhookAddress url={trigger.webhookUrl} /></Field>
              : <p className="text-xs text-gray-500">Save the trigger to get the address the service should call.</p>}
            <Input label={trigger?.signed ? 'Signing secret (set; type to replace)' : 'Signing secret (optional)'} type="password" autoComplete="off"
              value={secret} onChange={e => { setSecret(e.target.value); setClearSecret(false) }}
              hint="The secret the service signs each event with (HMAC-SHA256). Events without the right signature are refused." />
            {trigger?.signed && (
              <label className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
                <input type="checkbox" checked={clearSecret} onChange={e => setClearSecret(e.target.checked)} /> Remove the secret and accept unsigned events
              </label>
            )}
            <div className="grid gap-3 sm:grid-cols-2">
              <Input label="Signature header" value={header} className="font-mono" placeholder="X-Signature" onChange={e => setHeader(e.target.value)} hint="Where the service puts the signature" />
              <Input label="Signature prefix" value={prefix} className="font-mono" placeholder="sha256=" onChange={e => setPrefix(e.target.value)} hint="Text before the hex digest, if any" />
            </div>
          </div>
        )}
        <Field label="Resource">
          <select className={fieldClass} value={resourceId} onChange={e => setResourceId(e.target.value)}>
            <option value="">None</option>
            {detail.resources.map(r => <option key={r.id} value={r.id}>{r.label}</option>)}
          </select>
        </Field>
        <div className={`grid gap-4 ${kind === 'polling' ? 'sm:grid-cols-3' : 'sm:grid-cols-2'}`}>
          {kind === 'polling' && <Input label="Check every (minutes)" type="number" min={1} max={1440} value={interval} onChange={e => setInterval(e.target.value)} />}
          <Input label="Items path" value={itemsPath} className="font-mono" onChange={e => setItemsPath(e.target.value)} placeholder={kind === 'polling' ? 'data.bills' : 'events'}
            hint={kind === 'polling' ? 'Blank: the list in the response' : 'Blank: the event is one item (or a list of them)'} />
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
