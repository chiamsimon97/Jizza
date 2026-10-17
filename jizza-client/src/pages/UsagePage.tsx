import { useQuery } from '@tanstack/react-query'
import { BarChart2 } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { usageApi } from '@/api/usage'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'

export function UsagePage() {
  const { data: summary, isLoading: loadingSummary } = useQuery({
    queryKey: ['usage', 'summary'],
    queryFn: () => usageApi.summary(),
  })
  const { data: entries, isLoading: loadingEntries } = useQuery({
    queryKey: ['usage', 'entries'],
    queryFn: () => usageApi.entries(),
  })

  return (
    <div className="page">
      <PageHeader icon={BarChart2} title="Usage & Cost" description="Tokens and spend across models." />

      {loadingSummary ? (
        <div className="flex justify-center py-8"><Spinner /></div>
      ) : summary ? (
        <div className="mb-6 grid gap-3 sm:grid-cols-3">
          <Card padding="md">
            <p className="text-xs text-gray-500 dark:text-gray-400 mb-1">Input tokens</p>
            <p className="text-2xl font-bold text-gray-900 dark:text-gray-100">{summary.totalInputTokens.toLocaleString()}</p>
          </Card>
          <Card padding="md">
            <p className="text-xs text-gray-500 dark:text-gray-400 mb-1">Output tokens</p>
            <p className="text-2xl font-bold text-gray-900 dark:text-gray-100">{summary.totalOutputTokens.toLocaleString()}</p>
          </Card>
          <Card padding="md">
            <div className="flex items-center gap-2 mb-1">
              <p className="text-xs text-gray-500 dark:text-gray-400">Cost units</p>
              {summary.budgetLimit != null && (
                <Badge variant={summary.withinBudget ? 'success' : 'danger'}>
                  {summary.withinBudget ? 'Within budget' : 'Over budget'}
                </Badge>
              )}
            </div>
            <p className="text-2xl font-bold text-gray-900 dark:text-gray-100">
              {Number(summary.totalCostUnits).toFixed(4)}
            </p>
            {summary.budgetLimit != null && (
              <p className="text-xs text-gray-400 mt-0.5">of {summary.budgetLimit} limit</p>
            )}
          </Card>
        </div>
      ) : null}

      {summary?.breakdown && summary.breakdown.length > 0 && (
        <div className="mb-6">
          <h2 className="mb-3 text-sm font-semibold text-gray-700 dark:text-gray-300">By model</h2>
          <div className="stagger space-y-2">
            {summary.breakdown.map(m => (
              <Card key={m.model} padding="sm">
                <div className="flex items-center justify-between gap-3 text-sm">
                  <span className="font-mono text-gray-800 dark:text-gray-200">{m.model}</span>
                  <div className="flex gap-4 text-xs text-gray-500 dark:text-gray-400">
                    <span>in: {m.inputTokens.toLocaleString()}</span>
                    <span>out: {m.outputTokens.toLocaleString()}</span>
                    <span className="text-gray-700 dark:text-gray-300 font-medium">{Number(m.costUnits).toFixed(4)}</span>
                  </div>
                </div>
              </Card>
            ))}
          </div>
        </div>
      )}

      <h2 className="mb-3 text-sm font-semibold text-gray-700 dark:text-gray-300">Recent entries</h2>
      {loadingEntries ? (
        <div className="flex justify-center py-8"><Spinner /></div>
      ) : entries && entries.length > 0 ? (
        <div className="space-y-1">
          {entries.map(e => (
            <Card key={e.id} padding="sm">
              <div className="flex items-center justify-between gap-3 text-xs">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-gray-700 dark:text-gray-300">{e.model}</span>
                  {e.purpose && <Badge variant="default">{e.purpose}</Badge>}
                </div>
                <div className="flex gap-3 text-gray-500 dark:text-gray-400">
                  <span>↑{e.inputTokens} ↓{e.outputTokens}</span>
                  <span className="text-gray-700 dark:text-gray-300">{Number(e.costUnits).toFixed(4)}</span>
                  <span>{new Date(e.createdAt).toLocaleString()}</span>
                </div>
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <p className="text-sm text-gray-400">No entries yet.</p>
      )}
    </div>
  )
}
