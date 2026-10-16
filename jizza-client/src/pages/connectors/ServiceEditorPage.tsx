import { Navigate, NavLink, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft, Code2, Database, History, KeyRound, LayoutGrid, Play, Rocket, Trash2, Variable, Zap,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { servicesApi } from '@/api/services'
import type { ServiceDetail } from '@/types'
import { ServiceStatusBadge, errorText } from './shared'
import { OverviewTab } from './tabs/OverviewTab'
import { AuthenticationTab } from './tabs/AuthenticationTab'
import { ApisTab } from './tabs/ApisTab'
import { ResourcesTab } from './tabs/ResourcesTab'
import { ActionsTab } from './tabs/ActionsTab'
import { TriggersTab } from './tabs/TriggersTab'
import { VariablesTab } from './tabs/VariablesTab'
import { HistoryTab } from './tabs/HistoryTab'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'

const TABS = [
  { key: 'overview',       label: 'Overview',       icon: LayoutGrid },
  { key: 'authentication', label: 'Authentication', icon: KeyRound },
  { key: 'apis',           label: 'APIs',           icon: Code2 },
  { key: 'resources',      label: 'Resources',      icon: Database },
  { key: 'actions',        label: 'Actions',        icon: Play },
  { key: 'triggers',       label: 'Triggers',       icon: Zap },
  { key: 'variables',      label: 'Variables',      icon: Variable },
  { key: 'history',        label: 'History',        icon: History },
] as const

export type TabProps = { detail: ServiceDetail; refresh: () => void }

/** The service editor: one tab per part of the service, as in the onboarding doc's layout. */
export function ServiceEditorPage() {
  const { serviceId = '', tab = 'overview' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const queryKey = ['service', serviceId]
  const { data, isLoading, error } = useQuery({ queryKey, queryFn: () => servicesApi.get(serviceId) })
  const refresh = () => {
    qc.invalidateQueries({ queryKey })
    qc.invalidateQueries({ queryKey: ['services'] })
  }

  const publish = useMutation({
    mutationFn: () => data?.service.versionStatus === 'live'
      ? servicesApi.unpublish(serviceId) : servicesApi.publish(serviceId),
    onSuccess: refresh,
  })
  const remove = useMutation({
    mutationFn: () => servicesApi.remove(serviceId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['services'] })
      navigate('/connectors', { replace: true })
    },
  })

  if (!TABS.some(t => t.key === tab)) return <Navigate to={`/connectors/${serviceId}`} replace />
  if (isLoading) return <div className="page flex justify-center py-16"><Spinner /></div>
  if (error || !data) {
    return <div className="page"><p className="text-sm text-red-500">{errorText(error) || 'Service not found'}</p></div>
  }

  const { service } = data
  const live = service.versionStatus === 'live'
  const Tab = {
    overview: OverviewTab, authentication: AuthenticationTab, apis: ApisTab, resources: ResourcesTab,
    actions: ActionsTab, triggers: TriggersTab, variables: VariablesTab, history: HistoryTab,
  }[tab as (typeof TABS)[number]['key']]

  return (
    <div className="page">
      <button onClick={() => navigate('/connectors')}
        className="mb-3 inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200">
        <ArrowLeft className="h-4 w-4" /> Connectors
      </button>

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="grid h-11 w-11 shrink-0 place-items-center rounded-xl brand-gradient text-lg font-semibold text-white shadow-glow">
          {service.name.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="truncate text-xl font-semibold tracking-tight text-gray-900 dark:text-gray-100">{service.name}</h1>
            <ServiceStatusBadge service={service} />
          </div>
          <p className="truncate font-mono text-xs text-gray-500 dark:text-gray-400">{service.slug}</p>
        </div>
        <div className="flex items-center gap-2">
          <Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable">
            <Button size="sm" variant={live ? 'secondary' : 'primary'} loading={publish.isPending} onClick={() => publish.mutate()}>
              <Rocket className="h-4 w-4" /> {live ? 'Move to draft' : 'Publish'}
            </Button>
          </Can>
          <Can permission={PERMISSIONS.CONNECTOR_DELETE} mode="disable">
            <Button size="sm" variant="ghost" aria-label="Delete service" onClick={() => {
              if (window.confirm(`Delete ${service.name}, its APIs, actions, triggers and connection?`)) remove.mutate()
            }}><Trash2 className="h-4 w-4" /></Button>
          </Can>
        </div>
      </div>
      {publish.error && <p className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-950/40 dark:text-red-300">{errorText(publish.error)}</p>}

      <div className="flex flex-col gap-6 lg:flex-row">
        <nav aria-label="Service sections" className="flex shrink-0 gap-1 overflow-x-auto lg:w-48 lg:flex-col">
          {TABS.map(t => (
            <NavLink key={t.key} to={`/connectors/${serviceId}/${t.key === 'overview' ? '' : t.key}`} end
              className={({ isActive }) => `flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                isActive || (t.key === tab)
                  ? 'bg-brand-50 text-brand-700 dark:bg-brand-950/50 dark:text-brand-200'
                  : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'
              }`}>
              <t.icon className="h-4 w-4" /> {t.label}
              {t.key === 'actions' && <Count n={data.actions.length} />}
              {t.key === 'triggers' && <Count n={data.triggers.length} />}
              {t.key === 'apis' && <Count n={data.apis.length} />}
            </NavLink>
          ))}
        </nav>
        <section className="min-w-0 flex-1">
          <Tab detail={data} refresh={refresh} />
        </section>
      </div>
    </div>
  )
}

function Count({ n }: { n: number }) {
  return n > 0 ? <span className="ml-auto rounded-full bg-gray-200 px-1.5 text-[11px] text-gray-600 dark:bg-gray-800 dark:text-gray-300">{n}</span> : null
}
