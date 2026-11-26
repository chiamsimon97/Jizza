import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Link2, Plug, RefreshCw, Trash2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { FinAccount, SyncResult, SyncSource, SyncStatus } from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { SelectField } from '../components/Fields'
import { Panel, Pill } from '../components/Panel'
import { errorText } from '../data'
import { financeKeys } from '../queryKeys'
import { accountLabel } from './Connect'

const BROKER_KINDS = ['demat', 'us_broker', 'other']

const STATUS: Record<SyncStatus, { label: string; tone: 'ok' | 'info' | 'warn' | 'bad' }> = {
  to_review: { label: 'To review', tone: 'info' },
  no_change: { label: 'Up to date', tone: 'ok' },
  needs_sign_in: { label: 'Sign in needed', tone: 'warn' },
  failed: { label: 'Failed', tone: 'bad' },
}

/**
 * Connected sources (Kai part A): a broker's MCP server from Connectors, such as Zerodha's Kite,
 * tied to a demat account. Kai reads only its holdings, on Sync now and after the market closes on
 * weekdays, and every sync comes back as an import to review; nothing is saved on its own.
 */
export function ConnectedSources({ accounts, onReview }: { accounts: FinAccount[]; onReview: (batchId: string) => void }) {
  const qc = useQueryClient()
  const view = useQuery({ queryKey: financeKeys.sources, queryFn: financeApi.sources })
  const brokerAccounts = accounts.filter(a => BROKER_KINDS.includes(a.kind))
  const [serverId, setServerId] = useState('')
  const [accountId, setAccountId] = useState('')
  const [results, setResults] = useState<Record<string, SyncResult>>({})
  const refresh = () => qc.invalidateQueries({ queryKey: financeKeys.sources })
  const link = useMutation({
    mutationFn: (v: { serverId: string; accountId: string }) => financeApi.linkSource(v.serverId, v.accountId),
    onSuccess: () => { setServerId(''); setAccountId(''); refresh() },
  })
  const sync = useMutation({
    mutationFn: (id: string) => financeApi.syncSource(id),
    onSuccess: (r, id) => {
      setResults(prev => ({ ...prev, [id]: r }))
      qc.invalidateQueries({ queryKey: financeKeys.imports })
      refresh()
      if (r.status === 'to_review' && r.batchId) onReview(r.batchId)
    },
  })
  const unlink = useMutation({ mutationFn: (id: string) => financeApi.unlinkSource(id), onSuccess: refresh })
  const toggle = useMutation({ mutationFn: (s: SyncSource) => financeApi.setSourceEnabled(s.id, !s.enabled), onSuccess: refresh })

  if (!view.data) return null
  const { sources, servers } = view.data
  const label = (s: SyncSource) => {
    const a = accounts.find(x => x.id === s.accountId)
    return a ? accountLabel(a) : s.accountName
  }
  const chosenServer = serverId || servers[0]?.serverId || ''
  const chosenAccount = accountId || brokerAccounts[0]?.id || ''

  return (
    <Panel title={<><Plug className="h-4 w-4 text-brand-600 dark:text-brand-300" aria-hidden />Connected brokers</>}>
      <p className="text-sm text-gray-600 dark:text-gray-400">
        Kai reads your holdings from a broker you've added on Connectors, such as Zerodha's Kite. It reads only your holdings and never places an order.
        Each sync comes back as an import you review before anything is saved.
      </p>

      {sources.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800" aria-label="Connected brokers">
          {sources.map(s => {
            const st = s.lastStatus ? STATUS[s.lastStatus] : null
            const r = results[s.id]
            return (
              <li key={s.id} className="flex flex-col gap-1.5 py-2.5">
                <div className="flex flex-wrap items-center gap-2">
                  <Link2 className="h-4 w-4 text-gray-400" aria-hidden />
                  <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{s.serverName}</span>
                  <span className="text-sm text-gray-500 dark:text-gray-400">into {label(s)}</span>
                  {!s.enabled && <Pill>Paused</Pill>}
                  {s.enabled && !s.signedIn && <Pill tone="warn">Sign in needed</Pill>}
                  {st && <Pill tone={st.tone}>{st.label}</Pill>}
                  {s.lastSyncedAt && <span className="text-xs text-gray-500 dark:text-gray-400">Last sync {formatDay(s.lastSyncedAt)}</span>}
                  <span className="flex-1" />
                  {s.lastStatus === 'to_review' && s.lastBatchId && (
                    <Button size="sm" variant="secondary" onClick={() => onReview(s.lastBatchId!)}>Review</Button>
                  )}
                  <Button size="sm" loading={sync.isPending && sync.variables === s.id} disabled={!s.enabled} onClick={() => sync.mutate(s.id)}>
                    <RefreshCw className="h-3.5 w-3.5" aria-hidden />Sync now
                  </Button>
                  <Button size="sm" variant="ghost" loading={toggle.isPending && toggle.variables?.id === s.id} onClick={() => toggle.mutate(s)}>
                    {s.enabled ? 'Pause' : 'Resume'}
                  </Button>
                  <Button size="sm" variant="ghost" aria-label={`Disconnect ${s.serverName}`} loading={unlink.isPending && unlink.variables === s.id}
                    onClick={() => unlink.mutate(s.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                </div>
                {(r || (s.enabled && !s.signedIn)) && (
                  <p role="status" className="text-xs text-gray-600 dark:text-gray-300">
                    {r ? r.message : `Sign in to ${s.serverName} on Connectors, then sync.`}
                    {(r?.status === 'needs_sign_in' || (!r && !s.signedIn)) && (
                      <> <Link to={`/connectors/mcp/${s.serverId}`} className="font-medium text-brand-600 hover:underline dark:text-brand-300">Open {s.serverName}</Link></>
                    )}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {servers.length === 0 ? (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          No broker connected yet. Add Zerodha Kite from the list on{' '}
          <Link to="/connectors" className="font-medium text-brand-600 hover:underline dark:text-brand-300">Connectors</Link>, press Sign in to log in with Zerodha, then come back here.
        </p>
      ) : brokerAccounts.length === 0 ? (
        <p className="text-sm text-gray-600 dark:text-gray-300">Add a demat account (Net worth, then Accounts) to sync holdings into.</p>
      ) : (
        <div className="flex flex-wrap items-end gap-2">
          <SelectField label="Broker" value={chosenServer} onChange={setServerId} className="min-w-[10rem]"
            options={servers.map(s => ({ value: s.serverId, label: s.name + (s.signedIn ? '' : ' (sign in needed)') }))} />
          <SelectField label="Into account" value={chosenAccount} onChange={setAccountId} className="min-w-[12rem]"
            options={brokerAccounts.map(a => ({ value: a.id, label: accountLabel(a) }))} />
          <Button size="sm" loading={link.isPending} disabled={!chosenServer || !chosenAccount}
            onClick={() => link.mutate({ serverId: chosenServer, accountId: chosenAccount })}>Connect</Button>
        </div>
      )}
      {[link.error, sync.error, unlink.error, toggle.error].filter(Boolean).slice(0, 1).map((e, i) => (
        <p key={i} role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(e, 'That did not work.')}</p>
      ))}
    </Panel>
  )
}
