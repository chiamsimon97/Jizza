import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GitBranch } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import type { ApiError } from '@/lib/api'
import type { TreeNode, TreeView } from '@/types/finance'
import { SourceBadge } from '../components/Badges'
import { LoadFailed, Panel, Pill } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { ShortGoalSharesDialog, isShortGoalWarning } from '../components/ShortGoalShares'
import { errorText, useSample } from '../data'
import { financeKeys } from '../queryKeys'
import { GoalPicker } from './GoalPicker'

/** Two decimals, as the server keeps targets. */
const round2 = (n: number) => Math.round(n * 100) / 100

/**
 * What stops a tree from saving, in the user's words, the same checks the server makes: every
 * group of siblings with targets adds up to 100%, and every class and sub-category has a target.
 */
export function treeProblems(nodes: TreeNode[]): string[] {
  const out: string[] = []
  const byId = new Map(nodes.map(n => [n.id, n]))
  const groups = new Map<string | null, TreeNode[]>()
  for (const n of nodes) groups.set(n.parentId, [...(groups.get(n.parentId) ?? []), n])
  for (const [parent, kids] of groups) {
    const withTarget = kids.filter(k => k.targetPct != null)
    if (!withTarget.length) continue
    const sum = round2(withTarget.reduce((s, k) => s + (k.targetPct ?? 0), 0))
    if (sum !== 100) {
      const p = parent == null ? null : byId.get(parent)
      out.push(`${p ? `The parts of ${p.name}` : 'The asset classes'} add up to ${sum}%, not 100%.`)
    }
  }
  for (const n of nodes) {
    if (n.targetPct == null && n.kind !== 'instrument') out.push(`${n.name} needs a target.`)
    if (n.targetPct != null && (n.targetPct < 0 || n.targetPct > 100)) out.push(`${n.name} is outside 0–100%.`)
  }
  return out
}

/** Each node's share of the whole goal, multiplying down the tree. */
function goalShares(nodes: TreeNode[]): Map<string, number> {
  const out = new Map<string, number>()
  const walk = (parent: string | null, share: number) => {
    for (const n of nodes.filter(x => x.parentId === parent)) {
      const s = n.targetPct == null ? 0 : (share * n.targetPct) / 100
      out.set(n.id, s)
      walk(n.id, s)
    }
  }
  walk(null, 100)
  return out
}

/** The asset tree screen (7.4): pick a goal, then edit its tree. */
export function AssetTree({ goal, onGoal }: { goal: string | null; onGoal: (id: string | null) => void }) {
  const sample = useSample()
  if (sample) {
    return <EmptyState icon={GitBranch} title="Trees belong to your own goals"
      description="Leave the sample household and pick one of your goals to set its asset tree." />
  }
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Asset tree</h2>
        <span className="flex-1" />
        <GoalPicker value={goal} onChange={onGoal} />
      </div>
      {goal ? <TreeEditor goalId={goal} /> : <p className="text-sm text-gray-600 dark:text-gray-400">Pick a goal to see its tree.</p>}
    </div>
  )
}

/**
 * One goal's tree: asset classes, their sub-categories, and any holdings placed by hand, each with
 * a target share of its parent. Save stays off until every group adds up to 100%. Until it is
 * saved the tree is Kai's template for the goal.
 */
export function TreeEditor({ goalId, onSaved }: { goalId: string; onSaved?: () => void }) {
  const { data, isLoading, error, refetch } = useQuery({ queryKey: financeKeys.tree(goalId), queryFn: () => financeApi.tree(goalId) })
  if (isLoading) return <SkeletonRows rows={6} />
  if (error || !data) return <LoadFailed text="Could not load this goal's tree." onRetry={() => refetch()} />
  return <Editor key={`${data.goalId}:${data.saved}:${data.nodes.length}`} tree={data} onSaved={onSaved} />
}

function Editor({ tree, onSaved }: { tree: TreeView; onSaved?: () => void }) {
  const qc = useQueryClient()
  const [nodes, setNodes] = useState<TreeNode[]>(tree.nodes)
  const [warning, setWarning] = useState<ApiError | null>(null)
  const problems = useMemo(() => treeProblems(nodes), [nodes])
  const shares = useMemo(() => goalShares(nodes), [nodes])
  const changed = JSON.stringify(nodes) !== JSON.stringify(tree.nodes)

  const save = useMutation({
    mutationFn: (keepShares: boolean) => financeApi.saveTree(tree.goalId, nodes, keepShares),
    onSuccess: () => {
      setWarning(null)
      qc.invalidateQueries({ queryKey: financeKeys.all })
      onSaved?.()
    },
    onError: e => { if (isShortGoalWarning(e)) setWarning(e) },
  })

  const setTarget = (id: string, text: string) => {
    const v = text.trim() === '' ? null : Number(text)
    setNodes(ns => ns.map(n => (n.id === id ? { ...n, targetPct: v == null || Number.isNaN(v) ? null : v, source: 'you' } : n)))
  }

  const rows: { node: TreeNode; depth: number }[] = []
  const walk = (parent: string | null, depth: number) => {
    nodes.filter(n => n.parentId === parent).sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)).forEach(n => {
      rows.push({ node: n, depth })
      walk(n.id, depth + 1)
    })
  }
  walk(null, 0)

  return (
    <Panel title={<>{tree.goalName} {tree.bucket === 'short' && <Pill tone="warn">Under 3 years</Pill>}{!tree.saved && <SourceBadge source="kai_default" />}</>}>
      {!tree.saved && <p className="text-sm text-gray-600 dark:text-gray-400">This is Kai&apos;s starting mix for the goal. Change any target and save to make it yours.</p>}
      {tree.neverRebalanced && <p className="text-sm text-gray-600 dark:text-gray-400">An emergency fund is never rebalanced; the targets only describe it.</p>}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[26rem] text-sm">
          <caption className="sr-only">Asset tree targets</caption>
          <thead>
            <tr className="border-b border-gray-200 text-left text-xs text-gray-500 dark:border-gray-800 dark:text-gray-400">
              <th scope="col" className="py-2 pr-3 font-medium">Part</th>
              <th scope="col" className="py-2 pr-3 text-right font-medium">Share of parent</th>
              <th scope="col" className="py-2 text-right font-medium">Of the goal</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ node, depth }) => (
              <tr key={node.id} className="border-b border-gray-100 last:border-0 dark:border-gray-800/60">
                <td className="py-1.5 pr-3" style={{ paddingLeft: `${depth * 1.25}rem` }}>
                  <span className={depth === 0 ? 'font-medium text-gray-900 dark:text-gray-100' : 'text-gray-800 dark:text-gray-200'}>
                    {node.kind === 'instrument' ? node.instrumentName ?? node.name : node.name}
                  </span>
                  {node.userChoiceNote && <span className="ml-2"><Pill tone="info">{node.userChoiceNote}</Pill></span>}
                </td>
                <td className="py-1.5 pr-3 text-right">
                  <input aria-label={`Target for ${node.name}`} inputMode="decimal"
                    className="w-20 rounded-lg border border-gray-300 px-2 py-1 text-right tabular-nums dark:border-gray-700 dark:bg-gray-900"
                    value={node.targetPct ?? ''} onChange={e => setTarget(node.id, e.target.value)} /> %
                </td>
                <td className="py-1.5 text-right tabular-nums text-gray-600 dark:text-gray-400">{(shares.get(node.id) ?? 0).toFixed(1)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {problems.length > 0 && (
        <ul className="flex flex-col gap-0.5 text-sm text-red-600" role="alert">
          {problems.map(p => <li key={p}>{p}</li>)}
        </ul>
      )}
      {save.error && !isShortGoalWarning(save.error) && <p className="text-sm text-red-600" role="alert">{errorText(save.error, 'Could not save the tree.')}</p>}
      <div className="flex justify-end gap-2">
        {changed && <Button variant="secondary" onClick={() => setNodes(tree.nodes)}>Undo changes</Button>}
        <Button disabled={problems.length > 0 || (!changed && tree.saved)} loading={save.isPending && !warning} onClick={() => save.mutate(false)}>
          {tree.saved ? 'Save tree' : 'Save as this goal’s tree'}
        </Button>
      </div>
      <ShortGoalSharesDialog error={warning} busy={save.isPending} onKeep={() => save.mutate(true)}
        onCancel={() => { setWarning(null); save.reset() }} />
    </Panel>
  )
}
