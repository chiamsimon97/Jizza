import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { History, MessageSquare } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
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
    <div className="page">
      <PageHeader icon={History} title="Chat History" description="Pick up where you left off." />

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
        <div className="stagger space-y-2">
          {data.map(conv => (
            <Card
              key={conv.id}
              padding="md"
              interactive
              className="cursor-pointer"
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
