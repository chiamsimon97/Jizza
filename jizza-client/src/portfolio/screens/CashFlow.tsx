import { Wallet } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { formatINR } from '@/lib/money'
import type { QuickStartStep } from '@/types/finance'
import { AsOf, formatMonth } from '../components/AsOf'
import { Money } from '../components/Money'
import { SkeletonRows } from '../components/SkeletonRows'
import { StackedBar } from '../components/StackedBar'
import { useCashFlow, useSample } from '../data'

function Panel({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="flex min-w-0 flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  )
}

function Row({ label, children, strong = false }: { label: ReactNode; children: ReactNode; strong?: boolean }) {
  return (
    <div className={`flex justify-between gap-2 text-sm ${strong ? 'font-semibold' : ''}`}>
      <span className="text-gray-600 dark:text-gray-400">{label}</span>
      <span className="text-right text-gray-900 dark:text-gray-100">{children}</span>
    </div>
  )
}

/**
 * Cash flow (7.15): take-home by person, spending split into needs, wants and saved against the
 * 50/30/20 guide, what is left, and where Kai suggests that goes. Loan payments come from the
 * loans themselves, so they are never counted twice.
 */
export function CashFlow({ onOpen, onAnswer }: { onOpen: (view: string) => void; onAnswer: (step: QuickStartStep) => void }) {
  const sample = useSample()
  const { data, isLoading, error, refetch } = useCashFlow()
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 p-6 text-center">
        <p className="text-sm text-red-700 dark:text-red-300">Could not load your cash flow.</p>
        <Button size="sm" variant="secondary" onClick={() => refetch()}>Try again</Button>
      </div>
    )
  }
  if (!data.income.length && data.spendByBucket.need === 0 && data.spendByBucket.want === 0) {
    return <EmptyState icon={Wallet} title="No income or spending yet" description="Two Quick start questions fill this in: what comes in and what goes out."
      action={sample ? undefined : { label: 'Answer them', onClick: () => onAnswer('income') }} />
  }
  const { need, want, save } = data.spendByBucket
  const left = data.surplus
  const pct = data.needsWantsSavePct

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Cash flow · {formatMonth(data.month)}</h2>
        <AsOf date={data.asOf} />
        <span className="flex-1" />
        {!sample && <Button size="sm" variant="secondary" onClick={() => onAnswer('spending')}>Change income or spending</Button>}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Panel title="Income (take-home)">
          {data.income.map(i => <Row key={i.personId} label={i.name === 'Me' ? 'You' : i.name}><Money amount={i.takeHomeMonthly} /></Row>)}
          <Row label="Total" strong><Money amount={data.takeHome} /></Row>
          {data.income.some(i => i.grossMonthly == null) && (
            <p className="text-xs text-gray-500 dark:text-gray-400">Where gross pay is missing, Kai uses take-home for the debt check. That is the cautious way round.</p>
          )}
        </Panel>

        <Panel title="Where it goes">
          <StackedBar title="Needs, wants and saved" description="Guide: 50% needs, 30% wants, 20% saved."
            segments={[
              { label: 'Needs', value: need, className: 'bg-brand-500' },
              { label: 'Wants', value: want, className: 'bg-cyan-600' },
              { label: 'Saved', value: save, className: 'bg-green-600' },
              { label: 'Left over', value: Math.max(0, left), className: 'bg-gray-400 dark:bg-gray-600' },
            ]} />
          <Row label="Needs (with EMIs and premiums)"><Money amount={need} /></Row>
          <Row label="of which loan payments"><Money amount={data.loanPayments} /></Row>
          {data.premiumsMonthly > 0 && <Row label="of which premiums, a month"><Money amount={data.premiumsMonthly} /></Row>}
          <Row label="Wants"><Money amount={want} /></Row>
          <Row label="Saved (SIPs)"><Money amount={save} /></Row>
          <Row label="Left over" strong><span className={left < 0 ? 'text-red-600 dark:text-red-400' : ''}><Money amount={left} /></span></Row>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {pct.need}% needs, {pct.want}% wants, {pct.save}% saved of take-home.
          </p>
        </Panel>

        <Panel title="Where the surplus should go">
          {left <= 0 ? (
            <p className="text-sm text-gray-600 dark:text-gray-300">
              Nothing is left over this month{left < 0 ? `: spending is ${formatINR(-left)} more than take-home` : ''}. Kai looks at wants first.
            </p>
          ) : data.surplusUse.length ? (
            <>
              {data.surplusUse.map(u => <Row key={u.key} label={u.label}><Money amount={u.amount} /></Row>)}
              <p className="text-xs text-gray-500 dark:text-gray-400">Survival comes before goals: an emergency buffer and high-interest debt first.</p>
            </>
          ) : (
            <p className="text-sm text-gray-600 dark:text-gray-300">No survival gaps: the plan builder splits it across your goals. <button type="button" className="text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('plan-builder')}>Open the plan builder</button></p>
          )}
          {data.surplusUse.some(u => u.key === 'debt') && (
            <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('loans')}>See the repayment plan</button>
          )}
        </Panel>
      </div>

      <p className="text-xs text-gray-500 dark:text-gray-400">
        Spending here comes from your Quick start answers and budget. To bring in your bank statements, categorised row by row,{' '}
        <button type="button" className="text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('connect')}>open Connect</button>.
      </p>
    </div>
  )
}
