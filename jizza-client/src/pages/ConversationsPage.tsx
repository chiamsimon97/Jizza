import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { History, MessageSquare } from 'lucide-react'
import { conversationsApi } from '@/api/conversations'
import { Card } from '@/components/ui/Card'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function ConversationsPage() {
  const navigate = useNavigate()
  const { data, isLoading } = useQuery({
    queryKey: ['conversations'],
    queryFn: () => conversationsApi.list(),
  })

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center gap-2">
        <History className="h-5 w-5 text-brand-500" />
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Chat History</h1>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState
          icon={MessageSquare}
          title="No conversations yet"
          description="Start a chat to see your history here."
          action={{ label: 'New chat', onClick: () => navigate('/chat') }}
        />
      )}

      {data && data.length > 0 && (
        <div className="space-y-2">
          {data.map(conv => (
            <Card
              key={conv.id}
              padding="md"
              className="cursor-pointer hover:border-brand-300 dark:hover:border-brand-700 transition-colors"
              onClick={() => navigate(`/chat?conversationId=${conv.id}`)}
            >
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate">
                    Conversation {conv.id.slice(0, 8)}…
                  </p>
                  {conv.messages && conv.messages[0] && (
                    <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400 truncate">
                      {conv.messages[0].content}
                    </p>
                  )}
                </div>
                <span className="shrink-0 text-xs text-gray-400">
                  {new Date(conv.createdAt).toLocaleDateString()}
                </span>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
