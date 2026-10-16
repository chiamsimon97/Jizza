import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { Plus, X } from 'lucide-react'
import { Conversation } from './Conversation'
import { JizzaOrb } from './JizzaOrb'
import { useAssistantStore } from './assistantStore'

const INTRO = {
  title: 'Plan a workflow with Jizza',
  text: 'Describe what you want done. Jizza checks with the agents who know that part of your life, asks you about anything unclear, then shows you a plan to approve.',
  suggestions: [
    'Check the gold rate every morning and tell me if it drops below my limit',
    'Every Friday, summarise my week and what is due next week',
    'When a bill arrives, check it against my budget and ask me before paying',
  ],
}

/**
 * The Workflows page's "Plan with Jizza" panel. It is a fresh conversation with Jizza, sent as
 * workflow planning: Jizza consults the specialists, asks the user what is left open, and proposes
 * a plan that is saved only once the user approves it.
 */
export function WorkflowPlanner({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { thinking, setPlannerOpen, newConversation } = useAssistantStore()

  useEffect(() => {
    if (!open) return
    // Each planning session starts clean, so an earlier chat doesn't leak into the plan.
    newConversation()
    setPlannerOpen(true)
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      setPlannerOpen(false)
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!open) return null
  return createPortal(
    <div className="fixed inset-0 z-40 flex justify-end">
      <div className="absolute inset-0 bg-gray-950/40 backdrop-blur-[2px] animate-fade-in" onClick={onClose} />
      <section role="dialog" aria-label="Plan a workflow with Jizza"
        className="relative flex h-full w-full max-w-2xl flex-col bg-white shadow-2xl animate-slide-in-right dark:bg-gray-950">
        <header className="flex items-center gap-2.5 border-b border-gray-200 px-4 py-3 dark:border-gray-800">
          <JizzaOrb size={30} state={thinking ? 'thinking' : 'idle'} />
          <div className="min-w-0 flex-1">
            <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Plan with Jizza</h2>
            <p className="text-xs text-gray-500 dark:text-gray-400">{thinking ? 'Talking it through with the agents…' : 'Nothing is saved until you approve the plan'}</p>
          </div>
          <PanelButton label="Start over" onClick={newConversation}><Plus className="h-4 w-4" /></PanelButton>
          <PanelButton label="Close" onClick={onClose}><X className="h-4 w-4" /></PanelButton>
        </header>
        <div className="min-h-0 flex-1">
          <Conversation compact autoFocus inlineDiscussion intro={INTRO} placeholder="Describe the workflow you want…" />
        </div>
      </section>
    </div>,
    document.body,
  )
}

function PanelButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick} title={label} aria-label={label}
      className="grid h-8 w-8 place-items-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100">
      {children}
    </button>
  )
}
