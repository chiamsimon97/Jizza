import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Eye, EyeOff, Lock } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { ErrorBoundary } from '@/components/ui/ErrorBoundary'
import { fieldClass } from '@/components/ui/SlideOver'
import { Spinner } from '@/components/ui/Spinner'
import type { AgentDto } from '@/types'
import { Disclaimer } from './components/Badges'
import { HideAmountsContext } from './components/Money'
import { BUILT_VIEWS, PORTFOLIO_VIEWS, findView } from './nav'
import { financeKeys } from './queryKeys'
import { Home } from './screens/Home'
import { NotYet } from './screens/NotYet'
import { People } from './screens/People'
import { Settings } from './screens/Settings'
import { usePortfolioScope } from './usePortfolioScope'

/**
 * Kai's Portfolio tab (7.1): a sticky bar with the person and goal switchers, the execution-mode
 * chip and Hide amounts, the sub-navigation, then the current view. Scope lives in the URL.
 */
export function PortfolioTab({ agent }: { agent: AgentDto }) {
  const qc = useQueryClient()
  const [scope, setScope] = usePortfolioScope()
  const { data: profile, isLoading, error } = useQuery({ queryKey: financeKeys.profile, queryFn: financeApi.profile })
  const hide = useMutation({
    mutationFn: (hideAmounts: boolean) => financeApi.updateSettings({ hideAmounts }),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.profile }),
  })

  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (error || !profile) return <p className="text-sm text-red-600">Could not open {agent.name}'s Portfolio. Try again in a moment.</p>

  const advanced = profile.settings.advanced
  const hidden = profile.settings.hideAmounts
  const view = findView(scope.view)
  const visible = PORTFOLIO_VIEWS.filter(v => !v.advanced || advanced)
  const investments = visible.filter(v => v.group === 'investments')
  const people = profile.people.filter(p => p.status === 'active')
  const inInvestments = view.group === 'investments'

  return (
    <HideAmountsContext.Provider value={hidden}>
      <div className="flex flex-col gap-4">
        <div className="sticky top-0 z-10 -mx-1 flex flex-col gap-2 border-b border-gray-200 bg-gray-50/95 px-1 pb-2 pt-1 backdrop-blur dark:border-gray-800 dark:bg-gray-950/95">
          <div className="flex flex-wrap items-center gap-2">
            <label className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
              Person
              <select aria-label="Person" className={`${fieldClass} !w-auto !py-1 text-xs`} value={scope.person}
                onChange={e => setScope({ person: e.target.value })}>
                <option value="all">All</option>
                {people.map(p => <option key={p.id} value={p.id}>{p.kind === 'self' ? 'Me' : p.displayName}</option>)}
              </select>
            </label>
            <label className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
              Goal
              <select aria-label="Goal" className={`${fieldClass} !w-auto !py-1 text-xs`} value="all" disabled title="Goals arrive with the plan builder">
                <option value="all">All goals</option>
              </select>
            </label>
            <span className="inline-flex items-center gap-1 rounded-full bg-gray-900 px-2 py-0.5 text-xs font-medium text-white dark:bg-gray-100 dark:text-gray-900"
              title="Kai only tracks and flags; nothing is proposed or placed in this release">
              <Lock className="h-3 w-3" aria-hidden /> Observe only
            </span>
            <button type="button" onClick={() => hide.mutate(!hidden)} aria-pressed={hidden}
              className="ml-auto inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800">
              {hidden ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
              {hidden ? 'Amounts hidden' : 'Hide amounts'}
            </button>
          </div>
          <nav aria-label="Portfolio sections" className="flex gap-1 overflow-x-auto md:flex-wrap">
            {visible.filter(v => !v.group).map((v, i) => (
              <span key={v.id} className="contents">
                {i === 4 && investments.length > 0 && (
                  <NavButton label="Investments" active={inInvestments} onClick={() => setScope({ view: investments[0].id })} />
                )}
                <NavButton label={v.label} active={view.id === v.id} onClick={() => setScope({ view: v.id })} />
              </span>
            ))}
          </nav>
          {inInvestments && (
            <nav aria-label="Investments" className="flex gap-1 overflow-x-auto">
              {investments.map(v => <NavButton key={v.id} small label={v.label} active={view.id === v.id} onClick={() => setScope({ view: v.id })} />)}
            </nav>
          )}
        </div>

        <ErrorBoundary>
          {view.id === 'home' && <Home profile={profile} onOpen={v => setScope({ view: v })} />}
          {view.id === 'people' && <People />}
          {view.id === 'settings' && <Settings settings={profile.settings} />}
          {!BUILT_VIEWS.has(view.id) && <NotYet view={view} />}
        </ErrorBoundary>

        <Disclaimer />
      </div>
    </HideAmountsContext.Provider>
  )
}

function NavButton({ label, active, onClick, small = false }: { label: string; active: boolean; onClick: () => void; small?: boolean }) {
  return (
    <button type="button" onClick={onClick} aria-current={active ? 'page' : undefined}
      className={`shrink-0 whitespace-nowrap rounded-lg px-2.5 ${small ? 'py-1 text-xs' : 'py-1.5 text-sm'} ${active
        ? 'bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200'
        : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
      {label}
    </button>
  )
}
