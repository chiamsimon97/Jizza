import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plug, Plus, Zap, Play, Code2 } from 'lucide-react'
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
import { AUTH_LABELS, ServiceStatusBadge } from './shared'
import { Can } from '@/auth/Can'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'
import { McpServersSection } from './McpServers'

/** Connectors: the services Jizza's agents can work with, each one defined here. */
export function ConnectorsPage() {
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['services'], queryFn: () => servicesApi.list() })
  const canCreate = usePermission(PERMISSIONS.CONNECTOR_CREATE)

  return (
    <div className="page">
      <PageHeader icon={Plug} title="Connectors"
        description="Services your agents can work with. Define how to sign in, then the actions and triggers they offer."
        actions={<Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" onClick={() => setCreating(true)}><Plus className="h-4 w-4" />New service</Button></Can>} />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {isError && <LoadError what="your services" onRetry={() => void refetch()} />}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <EmptyState icon={Plug} title="No services yet"
          description="Create a service for any app with an HTTP API, then give your agents its actions."
          action={canCreate ? { label: 'New service', onClick: () => setCreating(true) } : undefined} />
      )}

      {data && data.length > 0 && (
        <div className="stagger grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {data.map(service => (
            <ServiceCard key={service.id} service={service} onOpen={() => navigate(`/connectors/${service.id}`)} />
          ))}
        </div>
      )}

      <McpServersSection />

      {/* OAuth needs its URLs and client id before it is valid, so the Authentication tab opens
          with it chosen and the details still to fill in. */}
      <NewServiceForm open={creating} onClose={() => setCreating(false)}
        onCreated={(s, scheme) => navigate(`/connectors/${s.id}/authentication${scheme === 'OAUTH2' ? '?scheme=OAUTH2' : ''}`)} />
    </div>
  )
}

function ServiceCard({ service, onOpen }: { service: ServiceInfo; onOpen: () => void }) {
  return (
    <Card interactive padding="lg" className="cursor-pointer" onClick={onOpen}>
      <div className="flex items-start gap-3">
        <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-100 text-lg font-semibold text-brand-700 dark:bg-brand-900/50 dark:text-brand-200">
          {service.name.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-gray-900 dark:text-gray-100">{service.name}</p>
          <p className="truncate text-xs text-gray-500 dark:text-gray-400">{service.category ?? AUTH_LABELS[service.authScheme]}</p>
        </div>
        <ServiceStatusBadge service={service} />
      </div>
      {service.description && <p className="mt-3 line-clamp-2 text-sm text-gray-600 dark:text-gray-300">{service.description}</p>}
      <div className="mt-4 flex flex-wrap gap-3 text-xs text-gray-500 dark:text-gray-400">
        <span className="inline-flex items-center gap-1"><Play className="h-3.5 w-3.5" />{service.actionCount} actions</span>
        <span className="inline-flex items-center gap-1"><Zap className="h-3.5 w-3.5" />{service.triggerCount} triggers</span>
        <span className="inline-flex items-center gap-1"><Code2 className="h-3.5 w-3.5" />{service.apiCount} APIs</span>
      </div>
      {service.authScheme !== 'NO_AUTH' && !service.credentialPresent && (
        <Badge variant="warning" className="mt-3">Not signed in yet</Badge>
      )}
    </Card>
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
