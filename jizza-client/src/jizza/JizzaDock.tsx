import { useEffect } from 'react'
import { useWebSocket } from '@/hooks/useWebSocket'
import { useLocation, useNavigate } from 'react-router-dom'
import { Maximize2, Plus, Volume2, VolumeX, X } from 'lucide-react'
import { useAssistantStore, bindNavigator, loadHistory } from './assistantStore'
import { Conversation } from './Conversation'
import { JizzaOrb } from './JizzaOrb'
import { pageForPath } from './pages'
import { useFormStore } from './formStore'
import { canSpeak, speak } from './useSpeech'

/**
 * Jizza, available on every page: a floating orb that opens the conversation in a panel, plus the
 * wiring that lets Jizza move around the app (navigation, Ctrl/Cmd+K, spoken replies).
 */
export function JizzaDock() {
  const navigate = useNavigate()
  const location = useLocation()
  const { dockOpen, setDockOpen, thinking, voiceReplies, setVoiceReplies, newConversation, lastReplyId, messages } =
    useAssistantStore()
  const onChatPage = location.pathname.startsWith('/chat')
  // A form slides in from the right; Jizza steps aside so the form's buttons stay reachable.
  const formOpen = useFormStore(s => s.request !== null)

  useEffect(() => {
    if (formOpen && !window.matchMedia('(min-width: 1024px)').matches) setDockOpen(false)
  }, [formOpen, setDockOpen])

  // Give the assistant hands: it navigates through the router and knows which page is up.
  useEffect(() => {
    bindNavigator({ go: path => navigate(path), currentPage: () => pageForPath(window.location.pathname) })
    return () => bindNavigator(null)
  }, [navigate])

  useEffect(() => { void loadHistory() }, [])

  // The dock is mounted on every page, so it is where chat events are followed, whichever view shows them.
  useWebSocket({ onChatEvent: event => useAssistantStore.getState().applyEvent(event) })

  // Ctrl/Cmd+K summons Jizza from anywhere; Escape dismisses the panel.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        if (onChatPage) document.querySelector<HTMLTextAreaElement>('textarea')?.focus()
        else setDockOpen(!useAssistantStore.getState().dockOpen)
      } else if (e.key === 'Escape' && useAssistantStore.getState().dockOpen) {
        setDockOpen(false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onChatPage, setDockOpen])

  // Read replies aloud when the user has asked for it.
  useEffect(() => {
    if (!voiceReplies || !lastReplyId) return
    const last = messages.find(m => m.id === lastReplyId)
    if (last) speak(last.text)
    // Only a new reply should trigger speech, not other message changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastReplyId])

  if (onChatPage) return null

  return (
    <>
      {dockOpen && (
        <div
          role="dialog"
          aria-label="Jizza"
          className={`fixed inset-0 z-50 flex flex-col overflow-hidden bg-white shadow-2xl animate-fade-in-up dark:bg-gray-950 sm:inset-auto sm:bottom-24 sm:h-[min(640px,calc(100vh-8rem))] ${formOpen ? 'sm:right-[38rem]' : 'sm:right-6'} sm:w-[400px] sm:rounded-2xl sm:border sm:border-gray-200 sm:dark:border-gray-800`}
        >
          <div className="flex items-center gap-2.5 border-b border-gray-200 px-3 py-2.5 dark:border-gray-800">
            <JizzaOrb size={30} state={thinking ? 'thinking' : 'idle'} />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">Jizza</p>
              <p className="text-xs text-gray-500 dark:text-gray-400">{thinking ? 'Working on it…' : 'Ready'}</p>
            </div>
            {canSpeak && (
              <DockButton label={voiceReplies ? 'Stop reading replies aloud' : 'Read replies aloud'} onClick={() => setVoiceReplies(!voiceReplies)} active={voiceReplies}>
                {voiceReplies ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
              </DockButton>
            )}
            <DockButton label="New conversation" onClick={newConversation}><Plus className="h-4 w-4" /></DockButton>
            <DockButton label="Open full chat" onClick={() => { setDockOpen(false); navigate('/chat') }}><Maximize2 className="h-4 w-4" /></DockButton>
            <DockButton label="Close" onClick={() => setDockOpen(false)}><X className="h-4 w-4" /></DockButton>
          </div>
          <div className="min-h-0 flex-1">
            <Conversation compact autoFocus onOpenJob={jobId => {
              setDockOpen(false)
              useAssistantStore.getState().openJob(jobId)
              navigate('/chat')
            }} />
          </div>
        </div>
      )}

      <button
        type="button"
        onClick={() => setDockOpen(!dockOpen)}
        aria-label={dockOpen ? 'Close Jizza' : 'Ask Jizza'}
        title="Ask Jizza (Ctrl+K)"
        className={`group fixed bottom-6 z-50 rounded-full transition-all duration-300 hover:scale-110 active:scale-95 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand-500/40 ${
          formOpen ? 'right-[38rem] hidden lg:block' : 'right-6'} ${dockOpen ? 'hidden sm:block' : ''}`}
      >
        <JizzaOrb size={56} state={thinking ? 'thinking' : 'idle'} />
        {!dockOpen && (
          <span className="pointer-events-none absolute right-full top-1/2 mr-3 -translate-y-1/2 whitespace-nowrap rounded-lg bg-gray-900 px-2.5 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity group-hover:opacity-100 dark:bg-gray-100 dark:text-gray-900">
            Ask Jizza · Ctrl K
          </span>
        )}
      </button>
    </>
  )
}

function DockButton({ label, onClick, active, children }: { label: string; onClick: () => void; active?: boolean; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      className={`grid h-8 w-8 place-items-center rounded-lg transition-colors ${
        active
          ? 'bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300'
          : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
      }`}
    >
      {children}
    </button>
  )
}
