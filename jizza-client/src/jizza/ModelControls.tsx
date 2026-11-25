import { useEffect, useRef, useState } from 'react'
import { Check, ChevronDown, Columns2, Cpu, Settings2, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { aiModelsApi, canRead, formatCost, groupModels, priceHint, type Depth, type ModelInfo } from '@/api/aiModels'
import { Dialog } from '@/components/ui/Dialog'
import { Button } from '@/components/ui/Button'
import type { AnsweredBy, ReplyVersion, TeamMember } from '@/types'
import { Markdown } from './Markdown'

/** The models the person can pick: Jizza's own and those of every provider they connected. */
export function useModels() {
  return useQuery({ queryKey: ['ai-models', 'picker'], queryFn: aiModelsApi.models, staleTime: 60_000 })
}

/** Closes a popover on a click outside it or Escape. */
function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) close() }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, close])
  return ref
}

/** Why a model can't take this message, or nothing when it can. */
function unreadable(m: ModelInfo, attached: { image: boolean; pdf: boolean }): string | undefined {
  if (canRead(m, attached)) return undefined
  return attached.image && !m.vision ? "Can't see photos" : "Can't read PDFs"
}

/** The list of models in a popover, grouped by provider, with a note and a price hint each. */
function ModelMenu({ models, value, attached, onPick, auto, label }: {
  models: ModelInfo[]
  value?: string
  attached: { image: boolean; pdf: boolean }
  onPick: (ref: string) => void
  /** Offers "Auto" first: Jizza picks for each message. */
  auto?: boolean
  label: string
}) {
  return (
    <ul role="listbox" aria-label={label}
      className="absolute bottom-full left-0 z-20 mb-2 max-h-80 w-80 max-w-[calc(100vw-2rem)] overflow-y-auto rounded-xl border border-gray-200 bg-white p-1 shadow-lg animate-fade-in dark:border-gray-700 dark:bg-gray-900">
      {auto && (
        <li role="option" aria-selected={!value}>
          <button type="button" onClick={() => onPick('auto')}
            className="flex w-full items-start gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm hover:bg-gray-50 dark:hover:bg-gray-800">
            <Check className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${!value ? 'text-brand-600' : 'invisible'}`} />
            <span>
              <span className="block font-medium text-gray-800 dark:text-gray-100">Auto</span>
              <span className="block text-xs text-gray-500 dark:text-gray-400">Jizza picks a model for each message</span>
            </span>
          </button>
        </li>
      )}
      {groupModels(models).map(g => (
        <li key={g.group} role="presentation">
          <p className="px-2.5 pb-1 pt-2 text-[11px] font-medium uppercase tracking-wide text-gray-400">{g.group}</p>
          <ul role="group" aria-label={g.group}>
            {g.models.map(m => {
              const why = unreadable(m, attached)
              const price = priceHint(m)
              return (
                <li key={m.ref} role="option" aria-selected={value === m.ref} aria-disabled={!!why}>
                  <button type="button" disabled={!!why} onClick={() => onPick(m.ref)} title={why}
                    className="flex w-full items-start gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm enabled:hover:bg-gray-50 disabled:opacity-50 dark:enabled:hover:bg-gray-800">
                    <Check className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${value === m.ref ? 'text-brand-600' : 'invisible'}`} />
                    <span className="min-w-0">
                      <span className="block font-medium text-gray-800 dark:text-gray-100">{m.label}</span>
                      <span className="block text-xs text-gray-500 dark:text-gray-400">
                        {[why ?? m.note, !m.tools && 'talk only, no tools', price].filter(Boolean).join(' · ')}
                      </span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        </li>
      ))}
      <li role="presentation" className="border-t border-gray-100 pt-1 dark:border-gray-800">
        <Link to="/settings/ai-models"
          className="flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-xs text-gray-500 hover:bg-gray-50 hover:text-brand-700 dark:text-gray-400 dark:hover:bg-gray-800">
          <Settings2 className="h-3.5 w-3.5" /> Connect your own OpenAI, Gemini or other key
        </Link>
      </li>
    </ul>
  )
}

/** Beside Send: which model answers in this chat, Auto unless the person picks one. */
export function ModelPicker({ value, onChange, attached }: {
  value?: string
  onChange: (ref: string | undefined) => void
  attached: { image: boolean; pdf: boolean }
}) {
  const [open, setOpen] = useState(false)
  const ref = useDismiss(open, () => setOpen(false))
  const { data: models = [] } = useModels()
  const picked = value ? models.find(m => m.ref === value) : undefined
  const label = picked?.label ?? (value ? value.replace(/^[^:]+:/, '') : 'Auto')
  return (
    <div ref={ref} className="relative">
      <button type="button" onClick={() => setOpen(o => !o)} aria-haspopup="listbox" aria-expanded={open}
        aria-label={`Model: ${label}`} title="Which model answers"
        className="inline-flex max-w-[14rem] items-center gap-1 rounded-lg px-2 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-gray-100">
        <Cpu className="h-3.5 w-3.5 shrink-0" />
        <span className="truncate">{label}</span>
        <ChevronDown className="h-3 w-3 shrink-0" />
      </button>
      {open && (
        <ModelMenu models={models} value={value} attached={attached} auto label="Models"
          onPick={r => { onChange(r === 'auto' ? undefined : r); setOpen(false) }} />
      )}
    </div>
  )
}

const DEPTHS: { value: Depth; label: string; hint: string }[] = [
  { value: 'quick', label: 'Quick', hint: 'A fast, cheap answer' },
  { value: 'normal', label: 'Normal', hint: 'The usual' },
  { value: 'deep', label: 'Deep', hint: 'Thinks harder: slower and costs more' },
]

/** Quick, Normal or Deep: how hard the answer thinks. */
export function DepthSwitch({ value, onChange }: { value?: Depth; onChange: (d: Depth) => void }) {
  const current = value ?? 'normal'
  return (
    <div role="radiogroup" aria-label="How hard to think"
      className="inline-flex items-center rounded-lg bg-gray-100 p-0.5 text-xs dark:bg-gray-800">
      {DEPTHS.map(d => (
        <button key={d.value} type="button" role="radio" aria-checked={current === d.value} title={d.hint}
          onClick={() => onChange(d.value)}
          className={`rounded-md px-2 py-0.5 font-medium transition-colors ${current === d.value
            ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-950 dark:text-gray-100'
            : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'}`}>
          {d.label}
        </button>
      ))}
    </div>
  )
}

/** Under a reply: ask the same question of another model; the answer now shown is kept to compare. */
export function AskAnotherModel({ current, onPick }: { current?: string; onPick: (ref: string) => void }) {
  const [open, setOpen] = useState(false)
  const ref = useDismiss(open, () => setOpen(false))
  const { data: models = [] } = useModels()
  if (models.length < 2) return null
  return (
    <div ref={ref} className="relative">
      <button type="button" onClick={() => setOpen(o => !o)} aria-label="Ask another model" title="Ask another model"
        aria-haspopup="listbox" aria-expanded={open}
        className="grid h-7 w-7 place-items-center rounded-md text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200">
        <Columns2 className="h-3.5 w-3.5" />
      </button>
      {open && (
        <ModelMenu models={models.filter(m => m.ref !== current)} attached={{ image: false, pdf: false }}
          label="Ask another model" onPick={r => { onPick(r); setOpen(false) }} />
      )}
    </div>
  )
}

/** "Answered by GPT-5.1 · your key". */
export function AnsweredByLabel({ model }: { model?: AnsweredBy }) {
  if (!model?.label) return null
  return (
    <span className="inline-flex items-center gap-1 text-xs text-gray-400 dark:text-gray-500" title={model.ref}>
      <Cpu className="h-3 w-3" /> {model.label}{model.ownKey ? ' · your key' : ''}
    </span>
  )
}

/**
 * Two answers to the same question side by side: the one shown now and an earlier one. Keeping the
 * earlier one makes the chat continue from it.
 */
export function CompareAnswers({ open, onClose, current, versions, onKeep }: {
  open: boolean
  onClose: () => void
  current: { text: string; model?: AnsweredBy; deep?: boolean }
  versions: ReplyVersion[]
  /** Keeps earlier version {@code index}; false when it did not save. */
  onKeep: (index: number) => Promise<boolean>
}) {
  const [other, setOther] = useState(versions.length - 1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { if (open) { setOther(versions.length - 1); setError('') } }, [open, versions.length])
  const earlier = versions[other]
  const name = (v: { model?: AnsweredBy; deep?: boolean }, fallback: string) =>
    v.model?.label ?? (v.deep ? 'Deeper model' : fallback)
  return (
    <Dialog open={open} onClose={onClose} title="Compare answers" size="xl"
      description="Keep the one you prefer: the chat carries on from it, and the other stays as an earlier answer.">
      {versions.length > 1 && (
        <div className="mb-3 flex flex-wrap gap-1.5" role="tablist" aria-label="Earlier answers">
          {versions.map((v, i) => (
            <button key={i} type="button" role="tab" aria-selected={i === other} onClick={() => setOther(i)}
              className={`rounded-full border px-2.5 py-0.5 text-xs ${i === other
                ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300'
                : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'}`}>
              {i + 1}. {name(v, 'Earlier answer')}
            </button>
          ))}
        </div>
      )}
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <section className="flex min-w-0 flex-col rounded-xl border border-brand-300 p-3 dark:border-brand-700" aria-label="Answer shown now">
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">Now · {name(current, 'This answer')}</h3>
          <div className="max-h-[55vh] flex-1 overflow-y-auto text-sm"><Markdown text={current.text} /></div>
          <Button size="sm" variant="secondary" className="mt-3 self-start" onClick={onClose}>Keep this one</Button>
        </section>
        {earlier && (
          <section className="flex min-w-0 flex-col rounded-xl border border-gray-200 p-3 dark:border-gray-700" aria-label="Earlier answer">
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">Earlier · {name(earlier, 'Earlier answer')}</h3>
            <div className="max-h-[55vh] flex-1 overflow-y-auto text-sm"><Markdown text={earlier.text} /></div>
            <Button size="sm" className="mt-3 self-start" loading={busy}
              onClick={async () => {
                setBusy(true)
                setError('')
                const ok = await onKeep(other)
                setBusy(false)
                if (ok) onClose()
                else setError("That didn't save. Try again.")
              }}>
              Keep this one
            </Button>
          </section>
        )}
      </div>
      {error && <p role="alert" className="mt-2 text-sm text-red-600">{error}</p>}
    </Dialog>
  )
}

/** Who worked on a reply: each specialist, what they were asked, the model they used and what it cost. */
export function TeamStrip({ team }: { team: TeamMember[] }) {
  const [open, setOpen] = useState(false)
  if (team.length === 0) return null
  const total = team.reduce((sum, t) => sum + (t.cost ?? 0), 0)
  return (
    <div className="text-xs text-gray-500 dark:text-gray-400">
      <button type="button" onClick={() => setOpen(o => !o)} aria-expanded={open}
        className="inline-flex items-center gap-1 hover:text-gray-800 dark:hover:text-gray-200">
        <Users className="h-3 w-3" />
        {team.length === 1 ? '1 specialist' : `${team.length} specialists`} worked on this
        {total > 0 && <> · {formatCost(total)}</>}
        <ChevronDown className={`h-3 w-3 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <ul className="mt-1 space-y-1 rounded-lg border border-gray-200 p-2 dark:border-gray-800" aria-label="Team">
          {team.map((t, i) => (
            <li key={i} className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-medium text-gray-700 dark:text-gray-200">{t.name || t.key}</span>
              {t.task && <span className="min-w-0 flex-1 truncate" title={t.task}>{t.task}</span>}
              <span className="text-gray-400">{t.model}{t.ownKey ? ' · your key' : ''}{t.cost ? ` · ${formatCost(t.cost)}` : ''}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
