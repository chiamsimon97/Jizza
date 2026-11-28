import { Lock } from 'lucide-react'
import { useAuthorization, hasMembers } from '@/auth/useAuthorization'

/** Shown in place of a page the active account or role can't open. */
export function NoAccessPage() {
  const { data } = useAuthorization()
  const org = hasMembers(data) ? data!.activeWorkspace.name : null
  return (
    <div className="page">
      <div className="mx-auto flex max-w-md flex-col items-center gap-3 rounded-2xl border border-dashed border-gray-300 px-6 py-16 text-center animate-fade-in dark:border-gray-700">
        <div className="grid h-14 w-14 place-items-center rounded-2xl bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400">
          <Lock className="h-7 w-7" strokeWidth={1.5} />
        </div>
        <h1 className="text-sm font-semibold text-gray-900 dark:text-gray-100">No access</h1>
        <p className="text-sm text-gray-500 dark:text-gray-400">
          {org
            ? `You don't have access to this area. Ask an Owner or Admin of ${org}.`
            : "You don't have access to this area. It's part of organization accounts; switch accounts from the menu at the top of the sidebar."}
        </p>
      </div>
    </div>
  )
}
