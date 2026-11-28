import { useState } from 'react'
import { LogOut, SlidersHorizontal } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { ThemeSwitcher } from '@/components/theme/ThemeSwitcher'
import { useAuthorization } from '@/auth/useAuthorization'
import { useSignOut } from '@/hooks/useSignOut'
import { AccountChoices, CreateFamily, CreateOrganization, type CreateKind } from '@/components/layout/AccountSwitcher'

/** Settings › General: how Jizza looks, which account is active, and who is signed in. */
export function GeneralSettingsPage() {
  const { data: snapshot } = useAuthorization()
  const signOut = useSignOut()
  const [creating, setCreating] = useState<CreateKind | null>(null)
  return (
    <div className="page max-w-3xl">
      <PageHeader icon={SlidersHorizontal} title="General" description="How Jizza looks, the account you are working in, and who is signed in." />
      <div className="space-y-4">
        <Card padding="lg">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Theme</h2>
          <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">Pick how Jizza looks on this browser.</p>
          <ThemeSwitcher className="max-w-sm" />
        </Card>
        <Card padding="lg">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Account</h2>
          <p className="mb-2 text-sm text-gray-500 dark:text-gray-400">Switch between your Individual account, your Family and your organizations.</p>
          <div className="-mx-2.5 max-w-md"><AccountChoices onCreate={setCreating} /></div>
          <CreateOrganization open={creating === 'organization'} onClose={() => setCreating(null)} />
          <CreateFamily open={creating === 'family'} onClose={() => setCreating(null)} />
        </Card>
        <Card padding="lg">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Signed in as</h2>
          <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-gray-600 dark:text-gray-300">{snapshot?.email ?? '…'}</p>
            <Button size="sm" variant="secondary" onClick={signOut}><LogOut className="h-4 w-4" />Sign out</Button>
          </div>
        </Card>
      </div>
    </div>
  )
}
