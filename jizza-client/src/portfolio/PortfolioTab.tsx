import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Eye, EyeOff, Lock, MessageSquare, Sparkles, X } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { ErrorBoundary } from '@/components/ui/ErrorBoundary'
import { fieldClass } from '@/components/ui/SlideOver'
import { SkeletonRows } from './components/SkeletonRows'
import { setChatDraft } from '@/jizza/chatDraft'
import { useAssistantStore } from '@/jizza/assistantStore'
import type { AgentDto } from '@/types'
import type { QuickStartStep } from '@/types/finance'
import { Disclaimer } from './components/Badges'
import { HideAmountsContext } from './components/Money'
import { SampleContext } from './data'
import { BUILT_VIEWS, PORTFOLIO_VIEWS, findView } from './nav'
import { financeKeys } from './queryKeys'
import { SAMPLE_PROFILE } from './sample'
import { ActionPlan } from './screens/ActionPlan'
import { CashFlow } from './screens/CashFlow'
import { Home } from './screens/Home'
import { GoalDetail, Goals, useGoals } from './screens/Goals'
import { GoalWizard } from './screens/GoalWizard'
import { Loans } from './screens/Loans'
import { NetWorth } from './screens/NetWorth'
import { NotYet } from './screens/NotYet'
import { People } from './screens/People'
import { PlanBuilder } from './screens/PlanBuilder'
import { Protection } from './screens/Protection'
import { QUICK_START_STEPS, QuickStart } from './screens/QuickStart'
import { Settings } from './screens/Settings'
import { Tax } from './screens/Tax'
import { usePortfolioScope } from './usePortfolioScope'

/** Views that work on the sample household; the rest are the user's own setup. */
const SAMPLE_VIEWS = new Set(['home', 'actions', 'cash-flow', 'loans', 'protection', 'net-worth'])

/**
 * Kai's Portfolio tab (7.1): a sticky bar with the person and goal switchers, the execution-mode
 * chip, Hide amounts and "Ask Kai", the sub-navigation, then the current view. Scope lives in the
 * URL. "Look around with sample data" shows Fixture D's household without saving anything.
 */
export function PortfolioTab({ agent }: { agent: AgentDto }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [scope, setScope] = usePortfolioScope()
  const { data: profile, isLoading, error } = useQuery({ queryKey: financeKeys.profile, queryFn: financeApi.profile })
  const goals = useGoals()
  const hide = useMutation({
    mutationFn: (hideAmounts: boolean) => financeApi.updateSettings({ hideAmounts }),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.profile }),
  })

  if (isLoading) return <SkeletonRows rows={4} />
  if (error || !profile) return <p className="text-sm text-red-600">Could not open {agent.name}'s Portfolio. Try again in a moment.</p>

  const sample = scope.sample === '1'
  const shown = sample ? SAMPLE_PROFILE : profile
  const advanced = profile.settings.advanced
  const hidden = profile.settings.hideAmounts
  const view = findView(scope.view)
  const visible = PORTFOLIO_VIEWS.filter(v => !v.hidden && (!v.advanced || advanced))
  const investments = visible.filter(v => v.group === 'investments')
  const people = shown.people.filter(p => p.status === 'active')
  const inInvestments = view.group === 'investments'
  const open = (id: string) => setScope({ view: id, step: null, goal: null })
  const answer = (step: QuickStartStep) => setScope({ view: 'quick-start', step, sample: null })
  const startStep = QUICK_START_STEPS.some(s => s.id === scope.step) ? scope.step as QuickStartStep : null
  const askKai = () => {
    setChatDraft(`@${agent.name} ${view.ask ?? ''}`)
    useAssistantStore.getState().newConversation()
    navigate('/chat')
  }

  return (
    <SampleContext.Provider value={sample}>
      <HideAmountsContext.Provider value={hidden}>
        <div className="flex flex-col gap-4">
          <div className="sticky top-0 z-10 -mx-1 flex flex-col gap-2 border-b border-gray-200 bg-gray-50/95 px-1 pb-2 pt-1 backdrop-blur dark:border-gray-800 dark:bg-gray-950/95">
            {sample && (
              <div className="flex flex-wrap items-center gap-2 rounded-lg bg-amber-50 px-3 py-1.5 text-sm text-amber-900 dark:bg-amber-900/30 dark:text-amber-100" role="status">
                <Sparkles className="h-4 w-4" aria-hidden />
                Sample household: every person and amount is made up, and nothing you do here is saved.
                <button type="button" onClick={() => setScope({ sample: null, view: 'home' })}
                  className="ml-auto inline-flex items-center gap-1 font-medium underline">
                  <X className="h-3.5 w-3.5" aria-hidden /> Leave sample
                </button>
              </div>
            )}
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
                <select aria-label="Goal" className={`${fieldClass} !w-auto !py-1 text-xs`} disabled={sample || !goals.data?.length}
                  value={view.id === 'goal' ? scope.goal : 'all'}
                  onChange={e => setScope(e.target.value === 'all' ? { view: 'goals', goal: null } : { view: 'goal', goal: e.target.value, step: null })}>
                  <option value="all">All goals</option>
                  {!sample && goals.data?.filter(g => g.goal.status !== 'archived').map(g => <option key={g.goal.id} value={g.goal.id}>{g.goal.name}</option>)}
                </select>
              </label>
              <span className="inline-flex items-center gap-1 rounded-full bg-gray-900 px-2 py-0.5 text-xs font-medium text-white dark:bg-gray-100 dark:text-gray-900"
                title="Kai only tracks and flags; nothing is proposed or placed in this release">
                <Lock className="h-3 w-3" aria-hidden /> Observe only
              </span>
              <span className="ml-auto flex items-center gap-1">
                <button type="button" onClick={() => hide.mutate(!hidden)} aria-pressed={hidden}
                  className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800">
                  {hidden ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                  {hidden ? 'Amounts hidden' : 'Hide amounts'}
                </button>
                <button type="button" onClick={askKai} disabled={sample} title={sample ? 'Kai answers about your own money, not the sample' : `Ask ${agent.name} about this screen`}
                  className="inline-flex items-center gap-1 rounded-lg border border-gray-200 px-2 py-1 text-xs text-gray-700 hover:bg-gray-100 disabled:opacity-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800">
                  <MessageSquare className="h-3.5 w-3.5" aria-hidden /> Ask {agent.name}
                </button>
              </span>
            </div>
            <nav aria-label="Portfolio sections" className="flex gap-1 overflow-x-auto md:flex-wrap">
              {visible.filter(v => !v.group).map((v, i) => (
                <span key={v.id} className="contents">
                  {i === 4 && investments.length > 0 && (
                    <NavButton label="Investments" active={inInvestments} onClick={() => open(investments[0].id)} />
                  )}
                  <NavButton label={v.label} active={view.id === v.id} onClick={() => open(v.id)} />
                </span>
              ))}
            </nav>
            {inInvestments && (
              <nav aria-label="Investments" className="flex gap-1 overflow-x-auto">
                {investments.map(v => <NavButton key={v.id} small label={v.label} active={view.id === v.id} onClick={() => open(v.id)} />)}
              </nav>
            )}
          </div>

          <ErrorBoundary>
            {sample && !SAMPLE_VIEWS.has(view.id) && BUILT_VIEWS.has(view.id)
              ? <p className="text-sm text-gray-500 dark:text-gray-400">This part is your own setup, so the sample household doesn't show it. Leave the sample to open it.</p>
              : <>
                {view.id === 'home' && <Home profile={shown} onOpen={open} onAnswer={answer} onSample={() => setScope({ sample: '1', view: 'home' })} />}
                {view.id === 'quick-start' && <QuickStart profile={profile} startAt={startStep} onOpen={open} />}
                {view.id === 'actions' && <ActionPlan onOpen={open} />}
                {view.id === 'goals' && <Goals person={scope.person} onNew={() => open('goal-new')} onOpenGoal={id => setScope({ view: 'goal', goal: id })} />}
                {view.id === 'goal' && scope.goal !== 'all' && (
                  <GoalDetail key={scope.goal} id={scope.goal} people={profile.people} onBack={() => open('goals')}
                    onEdit={() => setScope({ view: 'goal-new', goal: scope.goal })} />
                )}
                {view.id === 'goal' && scope.goal === 'all' && <Goals person={scope.person} onNew={() => open('goal-new')} onOpenGoal={id => setScope({ view: 'goal', goal: id })} />}
                {view.id === 'goal-new' && (
                  <GoalWizard key={scope.goal} people={profile.people} editId={scope.goal !== 'all' ? scope.goal : null}
                    onDone={id => setScope({ view: 'goal', goal: id })}
                    onCancel={() => (scope.goal !== 'all' ? setScope({ view: 'goal' }) : open('goals'))} />
                )}
                {view.id === 'plan-builder' && <PlanBuilder onOpen={open} />}
                {view.id === 'tax' && <Tax people={profile.people} person={scope.person} onOpen={open} />}
                {view.id === 'cash-flow' && <CashFlow onOpen={open} onAnswer={answer} />}
                {view.id === 'loans' && <Loans />}
                {view.id === 'protection' && <Protection people={people} onOpen={open} onAnswer={answer} />}
                {view.id === 'net-worth' && <NetWorth people={people} onOpen={open} />}
                {view.id === 'people' && <People />}
                {view.id === 'settings' && <Settings settings={profile.settings} />}
                {!BUILT_VIEWS.has(view.id) && <NotYet view={view} />}
              </>}
          </ErrorBoundary>

          <Disclaimer />
        </div>
      </HideAmountsContext.Provider>
    </SampleContext.Provider>
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
