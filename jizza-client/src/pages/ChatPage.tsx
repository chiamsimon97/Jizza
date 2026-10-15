import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { EyeOff, PanelLeft, PanelRight, Volume2, VolumeX, X } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { useAssistantStore, resumeConversation } from '@/jizza/assistantStore'
import { Conversation } from '@/jizza/Conversation'
import { JizzaOrb } from '@/jizza/JizzaOrb'
import { canSpeak } from '@/jizza/useSpeech'
import { ConversationRail } from '@/jizza/workspace/ConversationRail'
import { WorkPanel } from '@/jizza/workspace/WorkPanel'

/**
 * The chat workspace: conversations on the left, the conversation in the middle, and on the right
 * what Jizza did for it (Activity) over what it remembered and kept (Memory).
 */
export function ChatPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { conversationId, incognito, thinking, messages, voiceReplies, setVoiceReplies, setIncognito, openJob } =
    useAssistantStore()
  const [railOpen, setRailOpen] = useState(false)
  const [workOpen, setWorkOpen] = useState(false)

  // Opening a conversation from a link elsewhere in the app.
  useEffect(() => {
    const id = searchParams.get('conversationId')
    if (id) {
      if (id !== conversationId) resumeConversation(id)
      setSearchParams({}, { replace: true })
    }
  }, [searchParams, setSearchParams, conversationId])

  const firstQuestion = messages.find(m => m.role === 'user')?.text
  const title = incognito ? 'Incognito' : firstQuestion ? firstQuestion.slice(0, 80) : 'New conversation'

  return (
    <div className="relative flex h-full min-h-0">
      {/* Conversations */}
      <aside className="hidden w-64 shrink-0 border-r border-gray-200 bg-gray-50/60 dark:border-gray-800 dark:bg-gray-950 lg:block">
        <ConversationRail />
      </aside>
      {railOpen && (
        <Drawer side="left" onClose={() => setRailOpen(false)} label="Conversations">
          <ConversationRail onPicked={() => setRailOpen(false)} />
        </Drawer>
      )}

      {/* Conversation */}
      <section className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center gap-2 border-b border-gray-200 bg-white/70 px-3 py-2.5 backdrop-blur dark:border-gray-800 dark:bg-gray-900/70 sm:px-4">
          <IconButton label="Conversations" className="lg:hidden" onClick={() => setRailOpen(true)}><PanelLeft className="h-4 w-4" /></IconButton>
          <JizzaOrb size={28} state={thinking ? 'thinking' : 'idle'} />
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
            <p className="truncate text-xs text-gray-500 dark:text-gray-400">{thinking ? 'Working on it…' : 'Jizza'}</p>
          </div>
          {incognito && <Badge variant="warning"><EyeOff className="mr-1 inline h-3 w-3" />Incognito</Badge>}
          {canSpeak && (
            <IconButton label={voiceReplies ? 'Stop reading replies aloud' : 'Read replies aloud'} active={voiceReplies} onClick={() => setVoiceReplies(!voiceReplies)}>
              {voiceReplies ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
            </IconButton>
          )}
          <button
            onClick={() => setIncognito(!incognito)}
            aria-pressed={incognito}
            title={incognito ? 'Exit incognito' : 'Incognito: Jizza reads your memory but this chat is not saved'}
            className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-all active:scale-95 ${
              incognito
                ? 'bg-yellow-100 text-yellow-800 hover:bg-yellow-200 dark:bg-yellow-500/15 dark:text-yellow-300'
                : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
            }`}
          >
            <EyeOff className="h-4 w-4" />
            <span className="hidden sm:inline">{incognito ? 'Exit incognito' : 'Incognito'}</span>
          </button>
          <IconButton label="Activity and memory" className="xl:hidden" onClick={() => setWorkOpen(true)}>
            <span className="relative">
              <PanelRight className="h-4 w-4" />
              {thinking && <span className="absolute -right-1 -top-1 h-2 w-2 animate-pulse rounded-full bg-brand-500" />}
            </span>
          </IconButton>
        </div>
        <div className="min-h-0 flex-1">
          <Conversation autoFocus onOpenJob={jobId => { openJob(jobId); setWorkOpen(true) }} />
        </div>
      </section>

      {/* Activity over Memory */}
      <aside className="hidden w-[380px] shrink-0 border-l border-gray-200 dark:border-gray-800 xl:block">
        <WorkPanel />
      </aside>
      {workOpen && (
        <Drawer side="right" onClose={() => setWorkOpen(false)} label="Activity and memory">
          <WorkPanel />
        </Drawer>
      )}
    </div>
  )
}

/** Below the widths where a side column fits, it slides over the conversation instead. */
function Drawer({ side, onClose, children, label }: { side: 'left' | 'right'; onClose: () => void; children: React.ReactNode; label: string }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className={`absolute inset-0 z-30 flex ${side === 'right' ? 'justify-end' : ''} ${side === 'left' ? 'lg:hidden' : 'xl:hidden'}`}>
      <button type="button" aria-label="Close" onClick={onClose} className="absolute inset-0 bg-gray-900/30 backdrop-blur-[1px]" />
      <div
        role="dialog"
        aria-label={label}
        className={`relative flex h-full w-[min(380px,90vw)] flex-col bg-white shadow-2xl dark:bg-gray-950 ${side === 'left' ? 'animate-slide-in-left' : 'animate-fade-in'}`}
      >
        <button type="button" onClick={onClose} aria-label="Close panel" className="absolute right-2 top-2 z-10 grid h-7 w-7 place-items-center rounded-md text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800">
          <X className="h-4 w-4" />
        </button>
        <div className="min-h-0 flex-1 pt-8">{children}</div>
      </div>
    </div>
  )
}

function IconButton({ label, onClick, active, className = '', children }: {
  label: string; onClick: () => void; active?: boolean; className?: string; children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      className={`grid h-8 w-8 shrink-0 place-items-center rounded-lg transition-colors ${className} ${
        active
          ? 'bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300'
          : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
      }`}
    >
      {children}
    </button>
  )
}
