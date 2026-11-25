import { Hourglass } from 'lucide-react'
import { EmptyState } from '@/components/ui/EmptyState'
import type { PortfolioView } from '../nav'

/** A view a later build phase brings; it says what it will hold. */
export function NotYet({ view }: { view: PortfolioView }) {
  return <EmptyState icon={Hourglass} title={`${view.label} comes later`} description={view.about} />
}
