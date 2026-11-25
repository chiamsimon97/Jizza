import { PiggyBank } from 'lucide-react'
import { EmptyState } from '@/components/ui/EmptyState'
import type { AgentDto } from '@/types'

/**
 * Kai's Portfolio tab: the family's finance plan. This is the shell the build phases fill in;
 * it is only reachable while the server's finance switch is on.
 */
export function PortfolioTab({ agent }: { agent: AgentDto }) {
  return (
    <div className="flex flex-col gap-4">
      <EmptyState icon={PiggyBank} title="Portfolio is on its way"
        description={`${agent.name} will plan your family's money here: cash flow, net worth, protection, loans, tax and goals. Nothing is set up yet.`} />
      <p className="text-center text-xs text-gray-500 dark:text-gray-400">
        Rule-based analysis of your family's own money, not advice from a SEBI-registered adviser.
      </p>
    </div>
  )
}
