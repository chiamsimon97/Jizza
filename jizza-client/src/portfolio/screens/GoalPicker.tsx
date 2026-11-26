import { useEffect, useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import { fieldClass } from '@/components/ui/SlideOver'
import { financeKeys } from '../queryKeys'

/**
 * Chooses one of the user's live goals. Unless optional, with no goal chosen yet it picks the
 * first, so a screen that needs a goal always has one once there are goals.
 */
export function GoalPicker({ value, onChange, label = 'Goal', optional = false }: {
  value: string | null
  onChange: (id: string | null) => void
  label?: string
  /** Offers "No goal" and never picks one by itself. */
  optional?: boolean
}) {
  const id = useId()
  const { data } = useQuery({ queryKey: financeKeys.goals, queryFn: financeApi.goals })
  const goals = (data ?? []).map(v => v.goal).filter(g => g.status === 'active' || g.status === 'paused' || g.status === 'draft')
  useEffect(() => {
    if (!optional && !value && goals.length) onChange(goals[0].id)
  }, [optional, value, goals, onChange])
  if (!data) return null
  if (!goals.length) return <span className="text-sm text-gray-500 dark:text-gray-400">No goals yet</span>
  return (
    <span className="flex items-center gap-2">
      <label htmlFor={id} className="text-sm text-gray-600 dark:text-gray-400">{label}</label>
      <select id={id} className={`${fieldClass} w-auto`} value={value ?? ''} onChange={e => onChange(e.target.value || null)}>
        {optional && <option value="">No goal</option>}
        {goals.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
      </select>
    </span>
  )
}
