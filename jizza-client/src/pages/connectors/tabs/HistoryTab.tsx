import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { servicesApi } from '@/api/services'
import type { ServiceRun } from '@/types'
import { Empty, JsonBlock, SectionHeader } from '../shared'
import type { TabProps } from '../ServiceEditorPage'

const SOURCE_LABELS: Record<string, string> = {
  agent: 'Run by an agent', test: 'Test from the editor', poll: 'Trigger check', baseline: 'First trigger check',
  workflow: 'Run by a workflow',
}

/** Every action run, trigger check and test against the service, newest first. */
export function HistoryTab({ detail }: TabProps) {
  const [filter, setFilter] = useState<'all' | 'error'>('all')
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['service-history', detail.service.id],
    queryFn: () => servicesApi.history(detail.service.id),
  })
  const runs = (data ?? []).filter(r => filter === 'all' || r.status === 'error')

  return (
    <div>
      <SectionHeader title="History" description="Inputs and outputs are kept small and scrubbed of anything that looks like a credential."
        action={<div className="flex gap-2">
          <select aria-label="Filter" className="rounded-lg border border-gray-300 bg-white px-2 text-sm dark:border-gray-700 dark:bg-gray-900"
            value={filter} onChange={e => setFilter(e.target.value as 'all' | 'error')}>
            <option value="all">All</option><option value="error">Failures</option>
          </select>
          <Button size="sm" variant="secondary" loading={isFetching} onClick={() => refetch()}><RefreshCw className="h-3.5 w-3.5" />Refresh</Button>
        </div>} />
      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
      {!isLoading && runs.length === 0 && <Empty>Nothing yet. Test an action or trigger, or publish the service and let agents use it.</Empty>}
      <div className="flex flex-col gap-2">{runs.map(r => <RunRow key={r.id} run={r} />)}</div>
    </div>
  )
}

function RunRow({ run }: { run: ServiceRun }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="rounded-xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-950">
      <button onClick={() => setOpen(o => !o)} className="flex w-full items-center gap-3 px-4 py-3 text-left">
        {open ? <ChevronDown className="h-4 w-4 text-gray-400" /> : <ChevronRight className="h-4 w-4 text-gray-400" />}
        <Badge variant={run.status === 'ok' ? 'success' : 'danger'}>{run.status === 'ok' ? 'OK' : 'Failed'}</Badge>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-gray-900 dark:text-gray-100">{run.refName ?? run.kind}</span>
        <span className="hidden text-xs text-gray-500 sm:inline">{SOURCE_LABELS[run.source] ?? run.source}</span>
        <span className="text-xs text-gray-500">{run.durationMs != null ? `${run.durationMs} ms · ` : ''}{new Date(run.createdAt).toLocaleString()}</span>
      </button>
      {open && (
        <div className="grid gap-3 border-t border-gray-100 p-4 dark:border-gray-800 md:grid-cols-2">
          {run.error && <div className="md:col-span-2"><p className="mb-1 text-xs font-medium text-red-500">Error</p><JsonBlock value={run.error} /></div>}
          <div><p className="mb-1 text-xs font-medium text-gray-500">Input</p><JsonBlock value={run.input ?? {}} /></div>
          <div><p className="mb-1 text-xs font-medium text-gray-500">Output</p><JsonBlock value={run.output ?? {}} /></div>
        </div>
      )}
    </div>
  )
}
