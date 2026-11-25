import { PiggyBank } from 'lucide-react'
import { EmptyState } from '@/components/ui/EmptyState'
import type { FinProfileView } from '@/types/finance'

/** Home before anything is set up: the way into Quick start (which arrives with Money Health). */
export function Home({ profile, onOpen }: { profile: FinProfileView; onOpen: (view: string) => void }) {
  const managed = profile.setupProgress.peopleManaged
  return (
    <div className="flex flex-col gap-4">
      <EmptyState icon={PiggyBank} title="Start Quick start"
        description="Kai asks about income, spending, loans, cover and goals, then gives you a Money Health score and the next steps. Quick start arrives with the Money Health release; for now you can add the people you manage and look through Kai's defaults." />
      <div className="flex flex-wrap justify-center gap-2 text-sm">
        <button type="button" onClick={() => onOpen('people')}
          className="rounded-lg border border-gray-200 px-3 py-1.5 text-gray-700 hover:bg-gray-50 dark:border-gray-800 dark:text-gray-300 dark:hover:bg-gray-800">
          People you manage{managed ? ` (${managed})` : ''}
        </button>
        <button type="button" onClick={() => onOpen('settings')}
          className="rounded-lg border border-gray-200 px-3 py-1.5 text-gray-700 hover:bg-gray-50 dark:border-gray-800 dark:text-gray-300 dark:hover:bg-gray-800">
          Kai's defaults
        </button>
      </div>
    </div>
  )
}
