import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, RotateCcw, SlidersHorizontal } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { Field, SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { Spinner } from '@/components/ui/Spinner'
import { Switch } from '@/components/ui/Switch'
import type { FinConfigItem, FinSettings, JsonValue } from '@/types/finance'
import { NeedsVerification, SourceBadge } from '../components/Badges'
import { financeKeys } from '../queryKeys'

/** Settings groups (7.23), by the first part of each key. */
export const GROUPS: { title: string; areas: string[] }[] = [
  { title: 'Money Health', areas: ['health', 'survival', 'protection', 'priority'] },
  { title: 'Planning', areas: ['buckets', 'allocation', 'inflation', 'returns', 'goals', 'risk'] },
  { title: 'Tax and rules', areas: ['tax', 'regulatory'] },
]

const LABELS: Record<string, string> = {
  'health.checks': 'Money Health checks: thresholds and weights',
  'survival.order': 'Survival order: emergency months, high-interest debt, health cover floor',
  'protection.term_multiple': 'Term cover: multiple of income by age',
  'priority.required_probability': 'Required success probability by priority',
  'buckets.months': 'Short, mid and long term, in months to the goal',
  'allocation.horizon_by_age': 'Split across short, mid and long term, by age',
  'allocation.short': 'Short-term mix',
  'allocation.mid_by_risk': 'Mid-term mix, by risk profile',
  'allocation.long_by_age': 'Long-term mix, by age',
  'allocation.equity_sub_by_risk': 'Equity split by fund category, by risk profile',
  'allocation.sleeves': 'International and direct-share limits',
  'allocation.lumpsum': 'Lumpsum: months to move into equity',
  'inflation.profiles': 'Inflation by kind of goal (% a year)',
  'returns.expected': 'Expected returns (% a year)',
  'returns.monte_carlo': 'Volatility, correlation and simulation paths',
  'goals.library': 'Goal library defaults',
  'risk.questionnaire_v1': 'Risk questionnaire, version 1',
  'tax.new_regime': 'New tax regime: slabs, standard deduction, rebate',
  'tax.old_regime': 'Old tax regime',
  'tax.capital_gains': 'Capital gains rates and exemption',
  'regulatory.gold_loan_ltv': 'Gold loan: loan-to-value caps',
  'regulatory.sip_failure': 'SIP cancelled after this many failed instalments',
  'regulatory.clubbing_relationships': 'Gifts whose income is clubbed with the giver',
  'regulatory.lrs_tcs': 'Overseas investing (LRS): TCS threshold and rate',
}

export function labelFor(key: string) {
  return LABELS[key] ?? key
}

/** A value in a line or two of plain text, so a setting reads without opening it. */
export function summarise(value: JsonValue): string {
  if (value == null) return '—'
  if (typeof value !== 'object') return String(value)
  if (Array.isArray(value)) return `${value.length} rows`
  return Object.entries(value)
    .map(([k, v]) => `${k} ${v == null ? '—' : typeof v === 'object' ? (Array.isArray(v) ? `${v.length} rows` : '…') : v}`)
    .join(' · ')
}

function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback
}

/** Settings and assumptions (7.23): every Kai default with its source, your values, and reset. */
export function Settings({ settings }: { settings: FinSettings }) {
  const qc = useQueryClient()
  const { data: items, isLoading } = useQuery({ queryKey: financeKeys.config, queryFn: financeApi.config })
  const [editing, setEditing] = useState<FinConfigItem | null>(null)
  const toggle = useMutation({
    mutationFn: (patch: Partial<FinSettings>) => financeApi.updateSettings(patch),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.profile }),
  })
  const reset = useMutation({
    mutationFn: (key: string) => financeApi.resetConfig(key),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.config }),
  })

  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-3 rounded-xl border border-gray-200 p-4 dark:border-gray-800">
        <SettingSwitch label="Advanced" checked={settings.advanced} onChange={v => toggle.mutate({ advanced: v })}
          help="Shows Rules under Investments and the finer controls." />
        <SettingSwitch label="Hide amounts" checked={settings.hideAmounts} onChange={v => toggle.mutate({ hideAmounts: v })}
          help="Masks every amount on screen, for when someone is looking over your shoulder." />
        <p className="text-xs text-gray-500 dark:text-gray-400">Muting alerts comes with Money Health, when Kai starts sending them.</p>
      </section>

      {isLoading || !items ? <div className="flex justify-center py-8"><Spinner /></div> : GROUPS.map(group => {
        const rows = items.filter(i => group.areas.includes(i.area))
        if (!rows.length) return null
        return (
          <section key={group.title} className="flex flex-col gap-2">
            <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{group.title}</h2>
            <ul className="divide-y divide-gray-200 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
              {rows.map(item => (
                <li key={item.key} className="flex flex-wrap items-center gap-2 px-4 py-3">
                  <div className="min-w-0 flex-1 basis-64">
                    <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{labelFor(item.key)}</p>
                    <p className="truncate text-xs text-gray-500 dark:text-gray-400" title={summarise(item.value)}>{summarise(item.value)}</p>
                  </div>
                  <SourceBadge source={item.source} />
                  {item.needsVerification && <NeedsVerification />}
                  <Button size="sm" variant="ghost" onClick={() => setEditing(item)} aria-label={`Change ${labelFor(item.key)}`}>
                    <SlidersHorizontal className="h-3.5 w-3.5" />
                  </Button>
                  {item.isOverride && (
                    <Button size="sm" variant="ghost" loading={reset.isPending && reset.variables === item.key}
                      onClick={() => reset.mutate(item.key)}><RotateCcw className="h-3.5 w-3.5" /> Reset</Button>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )
      })}
      {editing && <EditSetting item={editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function SettingSwitch({ label, help, checked, onChange }: { label: string; help: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div>
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{label}</p>
        <p className="text-xs text-gray-500 dark:text-gray-400">{help}</p>
      </div>
      <Switch label={label} checked={checked} onChange={onChange} />
    </div>
  )
}

/** One setting's value, edited as text in its own shape; the server checks the shape. */
function EditSetting({ item, onClose }: { item: FinConfigItem; onClose: () => void }) {
  const qc = useQueryClient()
  const [text, setText] = useState(JSON.stringify(item.value, null, 2))
  const [parseError, setParseError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: (value: JsonValue) => financeApi.setConfig(item.key, value),
    onSuccess: () => { qc.invalidateQueries({ queryKey: financeKeys.config }); onClose() },
  })
  const submit = () => {
    try {
      const value = JSON.parse(text) as JsonValue
      setParseError(null)
      save.mutate(value)
    } catch {
      setParseError('This is not valid JSON. Check commas and quotes.')
    }
  }

  return (
    <SlideOver open title={labelFor(item.key)} onClose={onClose}
      description="Change the values and keep the same fields. Reset brings back Kai's default."
      footer={<>
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} onClick={submit}>Save as my value</Button>
      </>}>
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap gap-2"><SourceBadge source={item.source} />{item.needsVerification && <NeedsVerification />}</div>
        {item.needsVerification && (
          <p className="text-sm text-yellow-800 dark:text-yellow-300">
            This is a tax or regulatory figure that nobody has confirmed for the financial year starting {item.effectiveFrom}. Check it with your CA before relying on results that use it.
          </p>
        )}
        {item.sourceUrl && (
          <a href={item.sourceUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm text-brand-600 hover:underline dark:text-brand-300">
            Source <ExternalLink className="h-3.5 w-3.5" />
          </a>
        )}
        <Field label="Value" hint="Percentages are whole numbers: 6 means 6%. Amounts are in rupees.">
          <textarea className={`${fieldClass} min-h-[16rem] font-mono text-xs`} value={text} onChange={e => setText(e.target.value)} spellCheck={false} />
        </Field>
        {item.isOverride && (
          <details className="text-xs text-gray-500 dark:text-gray-400">
            <summary className="cursor-pointer">Kai's default</summary>
            <pre className="mt-2 overflow-x-auto rounded-lg bg-gray-50 p-3 dark:bg-gray-900">{JSON.stringify(item.defaultValue, null, 2)}</pre>
          </details>
        )}
        {(parseError || save.error) && <p className="text-sm text-red-600">{parseError ?? errorText(save.error, 'Could not save.')}</p>}
      </div>
    </SlideOver>
  )
}
