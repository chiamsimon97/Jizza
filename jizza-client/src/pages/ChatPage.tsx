import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { EyeOff, Plus, Volume2, VolumeX } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { useAssistantStore, resumeConversation } from '@/jizza/assistantStore'
import { Conversation } from '@/jizza/Conversation'
import { JizzaOrb } from '@/jizza/JizzaOrb'
import { canSpeak } from '@/jizza/useSpeech'

export function ChatPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { conversationId, incognito, thinking, messages, voiceReplies, setVoiceReplies, setIncognito, newConversation } =
    useAssistantStore()

  // Opening a conversation from History.
  useEffect(() => {
    const id = searchParams.get('conversationId')
    if (id) {
      if (id !== conversationId) resumeConversation(id)
      setSearchParams({}, { replace: true })
    }
  }, [searchParams, setSearchParams, conversationId])

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 border-b border-gray-200 bg-white/70 px-4 py-3 backdrop-blur dark:border-gray-800 dark:bg-gray-900/70 sm:px-6">
        <JizzaOrb size={32} state={thinking ? 'thinking' : 'idle'} />
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-semibold text-gray-900 dark:text-gray-100">
            {incognito ? 'Incognito' : 'Jizza'}
          </h2>
          <p className="truncate text-xs text-gray-500 dark:text-gray-400">
            {thinking ? 'Working on it…' : conversationId ? `Conversation ${conversationId.slice(0, 8)}…` : 'New conversation'}
          </p>
        </div>
        {incognito && <Badge variant="warning"><EyeOff className="mr-1 inline h-3 w-3" />Incognito</Badge>}
        {canSpeak && (
          <Button size="sm" variant="ghost" onClick={() => setVoiceReplies(!voiceReplies)} aria-pressed={voiceReplies}
            title={voiceReplies ? 'Stop reading replies aloud' : 'Read replies aloud'}>
            {voiceReplies ? <Volume2 className="h-4 w-4 text-brand-500" /> : <VolumeX className="h-4 w-4" />}
            <span className="hidden sm:inline">Voice</span>
          </Button>
        )}
        {(messages.length > 0 || conversationId) && (
          <Button size="sm" variant="ghost" onClick={newConversation}>
            <Plus className="h-4 w-4" />
            <span className="hidden sm:inline">New</span>
          </Button>
        )}
        <button
          onClick={() => setIncognito(!incognito)}
          aria-pressed={incognito}
          className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-all active:scale-95 ${
            incognito
              ? 'bg-yellow-100 text-yellow-800 hover:bg-yellow-200 dark:bg-yellow-500/15 dark:text-yellow-300'
              : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
          }`}
        >
          <EyeOff className="h-4 w-4" />
          <span className="hidden sm:inline">{incognito ? 'Exit incognito' : 'Incognito'}</span>
        </button>
      </div>
      <div className="min-h-0 flex-1">
        <Conversation autoFocus />
      </div>
    </div>
  )
}
