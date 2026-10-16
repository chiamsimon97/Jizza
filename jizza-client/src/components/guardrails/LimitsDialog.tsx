import { useEffect, useState } from 'react'
import { Dialog } from '@/components/ui/Dialog'
import { Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import type { GuardrailLimits } from '@/types'

interface LimitsDialogProps {
  open: boolean
  title: string
  limits?: GuardrailLimits | null
  onClose: () => void
  onSave: (limits: GuardrailLimits | null) => void
  saving?: boolean
}

const PERIOD_OPTIONS = [
  { value: 'P1D', label: 'per day' },
  { value: 'P7D', label: 'per week' },
  { value: 'P30D', label: 'per month' },
]

const browserZone = () => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

const numberOrUndefined = (text: string) => (text.trim() === '' ? undefined : Number(text))

/**
 * The conditions that let Auto or Let Jizza decide apply. When a call does not meet them, the
 * agent asks you instead; it never does anything looser.
 */
export function LimitsDialog({ open, title, limits, onClose, onSave, saving }: LimitsDialogProps) {
  const [maxValue, setMaxValue] = useState('')
  const [field, setField] = useState('amount')
  const [budget, setBudget] = useState('')
  const [budgetPer, setBudgetPer] = useState('P7D')
  const [maxCalls, setMaxCalls] = useState('')
  const [callsPer, setCallsPer] = useState('P1D')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [condition, setCondition] = useState('')
  const [conditionLabel, setConditionLabel] = useState('')

  useEffect(() => {
    if (!open) return
    setMaxValue(limits?.maxValue != null ? String(limits.maxValue) : '')
    setField(limits?.field ?? limits?.budget?.field ?? 'amount')
    setBudget(limits?.budget ? String(limits.budget.max) : '')
    setBudgetPer(limits?.budget?.per ?? 'P7D')
    setMaxCalls(limits?.maxCalls ? String(limits.maxCalls.count) : '')
    setCallsPer(limits?.maxCalls?.per ?? 'P1D')
    setFrom(limits?.activeHours?.from ?? '')
    setTo(limits?.activeHours?.to ?? '')
    setCondition(limits?.condition ?? '')
    setConditionLabel(limits?.conditionLabel ?? '')
  }, [open, limits])

  const build = (): GuardrailLimits | null => {
    const out: GuardrailLimits = {}
    const max = numberOrUndefined(maxValue)
    if (max != null) { out.maxValue = max; out.field = field || 'amount' }
    const total = numberOrUndefined(budget)
    if (total != null) out.budget = { max: total, per: budgetPer, field: field || 'amount' }
    const calls = numberOrUndefined(maxCalls)
    if (calls != null) out.maxCalls = { count: calls, per: callsPer }
    if (from && to) out.activeHours = { from, to, zone: limits?.activeHours?.zone ?? browserZone() }
    if (condition.trim()) {
      out.condition = condition.trim()
      if (conditionLabel.trim()) out.conditionLabel = conditionLabel.trim()
    }
    return Object.keys(out).length === 0 ? null : out
  }

  const select = 'rounded-lg border border-gray-300 bg-white px-2 py-2 text-sm dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100'

  return (
    <Dialog open={open} title={title} onClose={onClose}
      description="When a call doesn't meet these, the agent asks you instead.">
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Input label="Most per call" inputMode="decimal" value={maxValue} onChange={e => setMaxValue(e.target.value)} placeholder="500" />
          <Input label="Measured in" value={field} onChange={e => setField(e.target.value)} hint="The argument that holds the amount" />
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1"><Input label="Budget in total" inputMode="decimal" value={budget} onChange={e => setBudget(e.target.value)} placeholder="2000" /></div>
          <select aria-label="Budget period" className={select} value={budgetPer} onChange={e => setBudgetPer(e.target.value)}>
            {PERIOD_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1"><Input label="Most runs" inputMode="numeric" value={maxCalls} onChange={e => setMaxCalls(e.target.value)} placeholder="5" /></div>
          <select aria-label="Runs period" className={select} value={callsPer} onChange={e => setCallsPer(e.target.value)}>
            {PERIOD_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Input label="Only from" type="time" value={from} onChange={e => setFrom(e.target.value)} />
          <Input label="Until" type="time" value={to} onChange={e => setTo(e.target.value)} />
        </div>
        <details className="text-sm">
          <summary className="cursor-pointer text-gray-600 dark:text-gray-400">Advanced condition</summary>
          <div className="mt-2 space-y-2">
            <Input label="Condition" value={condition} onChange={e => setCondition(e.target.value)}
              placeholder="args.symbol =~ ['VOO','VTI']" hint="Checked against the call's arguments; must be exactly true." />
            <Input label="In plain words" value={conditionLabel} onChange={e => setConditionLabel(e.target.value)} placeholder="only index funds" />
          </div>
        </details>
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" size="sm" onClick={() => onSave(null)} disabled={saving}>Remove limits</Button>
          <Button size="sm" onClick={() => onSave(build())} loading={saving}>Save</Button>
        </div>
      </div>
    </Dialog>
  )
}
