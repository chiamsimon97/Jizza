import { useSearchParams } from 'react-router-dom'
import { Pill } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { MyMedicinesToday } from '@/vita/screens/MyMedicinesToday'

/**
 * "My medicines today" (spec 7.20) as a page of its own, with no agent tabs around it: opened after
 * sign-in for a person linked to their own record, from a reminder (?slot=the slot's time), and
 * from "My medicines" in the left menu.
 */
export function MyMedicinesPage() {
  const [params] = useSearchParams()
  const today = new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })
  return (
    <div className="page max-w-3xl">
      <PageHeader icon={Pill} title="My medicines today" description={today} />
      <MyMedicinesToday slot={params.get('slot')} />
    </div>
  )
}
