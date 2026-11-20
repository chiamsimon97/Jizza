import { useQueryClient } from '@tanstack/react-query'
import { notificationsApi } from '@/api/notifications'
import { guardrailsApi } from '@/api/guardrails'
import type { InboxActions } from './NotificationItem'

/**
 * What a notification card can do, wherever it is shown: the inbox, or a run's card in the chat
 * that started it. Each refreshes the shared list, so answering in one place closes it in both.
 */
export function useInboxActions(): InboxActions {
  const qc = useQueryClient()
  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] })
  const after = <T,>(p: Promise<T>) => p.finally(refresh)
  return {
    approve: (n, inputs) => after(notificationsApi.answer(n.id, 'APPROVE', undefined, undefined, inputs)),
    approveAlways: async (n, inputs) => {
      await guardrailsApi.saveRule({ agentId: n.sourceAgentId, toolRef: String(n.context?.tool), rule: 'ALLOW', precedence: 20 })
      void qc.invalidateQueries({ queryKey: ['guardrails'] })
      return after(notificationsApi.answer(n.id, 'APPROVE', undefined, undefined, inputs))
    },
    decline: (n, reason) => after(notificationsApi.answer(n.id, 'DENY', undefined, reason || undefined)),
    reply: (n, text) => after(notificationsApi.answer(n.id, 'SUBMIT_INSTRUCTION', text)),
    goBack: (n, node) => after(notificationsApi.answer(n.id, 'GO_BACK', node)),
    markRead: n => after(notificationsApi.markRead(n.id)),
    markUnread: n => after(notificationsApi.markUnread(n.id)),
    archive: n => after(notificationsApi.archive(n.id)),
    unarchive: n => after(notificationsApi.unarchive(n.id)),
    snooze: (n, until) => after(notificationsApi.snooze(n.id, until ? until.toISOString() : null)),
  }
}
