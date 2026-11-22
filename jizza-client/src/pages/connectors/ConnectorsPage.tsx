import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Code2, MonitorSmartphone, Plug, Plus } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState, LoadError } from '@/components/ui/EmptyState'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import type { AuthScheme, ServiceInfo } from '@/types'
import { AUTH_LABELS } from './shared'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'
import { McpServersSection } from './McpServers'
import { HUB_KEY, HubGrid, useConnectorCards } from './ConnectorHub'

type Tab = 'connected' | 'catalog' | 'build' | 'devices'

const TABS: { id: Tab; label: string }[] = [
  { id: 'connected', label: 'Connected' },
  { id: 'catalog', label: 'Catalog' },
  { id: 'build', label: 'Build your own' },
  { id: 'devices', label: 'Devices' },
]

/**
 * Connectors: every way your agents reach outside Jizza in one place. Services you built, MCP
 * servers and paired devices show as the same kind of card: whether it works, what its tools do,
 * which agents may use it and when it was last used.
 */
export function ConnectorsPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const tab = (TABS.some(t => t.id === params.get('tab')) ? params.get('tab') : 'connected') as Tab
  const setTab = (t: Tab) => setParams(p => { if (t === 'connected') p.delete('tab'); else p.set('tab', t); return p }, { replace: true })
  const [creating, setCreating] = useState(false)
  const { data, isLoading, isError, refetch } = useConnectorCards()
  const connected = (data ?? []).filter(c => c.kind !== 'device')
  const devices = (data ?? []).filter(c => c.kind === 'device')
  const needYou = connected.filter(c => c.status === 'needs_sign_in' || c.status === 'failing').length

  return (
    <div className="page">
      <PageHeader icon={Plug} title="Connectors"
        description="Everything your agents can reach: services, MCP servers and your devices. Choose who may use each, and test it any time."
        actions={<Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" onClick={() => setTab('catalog')}><Plus className="h-4 w-4" />Add a connector</Button></Can>} />

      <div className="mb-5 flex gap-1 overflow-x-auto border-b border-gray-200 dark:border-gray-800" role="tablist" aria-label="Connectors">
        {TABS.map(t => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}
            className={`-mb-px flex shrink-0 items-center gap-2 border-b-2 px-3 py-2 text-sm font-medium transition-colors ${tab === t.id
              ? 'border-brand-500 text-brand-700 dark:text-brand-300'
              : 'border-transparent text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'}`}>
            {t.label}
            {t.id === 'connected' && needYou > 0 && <Badge variant="warning">{needYou}</Badge>}
          </button>
        ))}
      </div>

      {(tab === 'connected' || tab === 'devices') && isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {(tab === 'connected' || tab === 'devices') && isError && <LoadError what="your connectors" onRetry={() => void refetch()} />}

      {tab === 'connected' && data && (connected.length === 0
        ? <EmptyState icon={Plug} title="Nothing connected yet"
            description="Install a ready-made server from the catalog, or build a connector for any app with an API."
            action={{ label: 'Open the catalog', onClick: () => setTab('catalog') }} />
        : <HubGrid cards={connected} />)}

      {tab === 'catalog' && <McpServersSection view="catalog" />}

      {tab === 'build' && (
        <div className="grid gap-4 md:grid-cols-2">
          <Card padding="lg" className="flex flex-col">
            <div className="flex items-start gap-3">
              <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-100 text-brand-700 dark:bg-brand-900/50 dark:text-brand-200"><Code2 className="h-5 w-5" /></div>
              <div className="min-w-0 flex-1">
                <p className="font-semibold text-gray-900 dark:text-gray-100">A service from any HTTP API</p>
                <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">Say how it signs in, then the actions and triggers it offers. Each action is typed from its HTTP method, so the guardrails apply at once.</p>
              </div>
            </div>
            <div className="mt-4"><Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" onClick={() => setCreating(true)}><Plus className="h-4 w-4" />New service</Button></Can></div>
          </Card>
          <McpServersSection view="url" />
        </div>
      )}

      {tab === 'devices' && data && (
        <>
          <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
            Computers, phones and browsers running the Jizza Agent app. Their tools are Jizza's to begin with; give them to other agents here.{' '}
            <Link to="/settings/devices" className="font-medium text-brand-600 hover:underline dark:text-brand-300">Pair a device</Link>
          </p>
          {devices.length === 0
            ? <EmptyState icon={MonitorSmartphone} title="No devices yet"
                description="Pair a computer, phone or browser so your agents can work on it."
                action={{ label: 'Pair a device', onClick: () => navigate('/settings/devices') }} />
            : <HubGrid cards={devices} />}
        </>
      )}

      {/* OAuth needs its URLs and client id before it is valid, so the Authentication tab opens
          with it chosen and the details still to fill in. */}
      <NewServiceForm open={creating} onClose={() => setCreating(false)}
        onCreated={(s, scheme) => navigate(`/connectors/${s.id}/authentication${scheme === 'OAUTH2' ? '?scheme=OAUTH2' : ''}`)} />
    </div>
  )
}

function NewServiceForm({ open, onClose, onCreated }: {
  open: boolean; onClose: () => void; onCreated: (s: ServiceInfo, scheme: AuthScheme) => void
}) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [authScheme, setAuthScheme] = useState<AuthScheme>('API_KEY')
  const create = useMutation({
    mutationFn: () => servicesApi.create({ name, description, category, baseUrl, authScheme }),
    onSuccess: async created => {
      await qc.invalidateQueries({ queryKey: ['services'] })
      void qc.invalidateQueries({ queryKey: HUB_KEY })
      onClose()
      onCreated(created, authScheme)
    },
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate()
  }

  return (
    <SlideOver open={open} title="New service" description="You can change all of this later." onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button type="submit" form="new-service" loading={create.isPending}>Create service</Button>
      </>}>
      <form id="new-service" onSubmit={submit} className="flex flex-col gap-4">
        <Input label="Name" required value={name} onChange={e => setName(e.target.value)} placeholder="Zoho Inventory" />
        <Field label="Description">
          <textarea className={fieldClass} rows={2} value={description} onChange={e => setDescription(e.target.value)}
            placeholder="What this service is and what your agents should use it for" />
        </Field>
        <Input label="Category" value={category} onChange={e => setCategory(e.target.value)} placeholder="Finance, CRM, Email…" />
        <Input label="Base URL" value={baseUrl} onChange={e => setBaseUrl(e.target.value)}
          placeholder="https://api.example.com/v1" hint="Every API path is called on this host. It must be https." />
        <Field label="How it signs in">
          <select className={fieldClass} value={authScheme} onChange={e => setAuthScheme(e.target.value as AuthScheme)}>
            {(Object.keys(AUTH_LABELS) as AuthScheme[]).map(k => <option key={k} value={k}>{AUTH_LABELS[k]}</option>)}
          </select>
        </Field>
        {create.error && <p className="text-sm text-red-500">{(create.error as Error).message}</p>}
      </form>
    </SlideOver>
  )
}
