import { Building2, ExternalLink } from 'lucide-react'
import { categoryLabel } from '@/api/listings'
import { Badge } from '@/components/ui/Badge'
import type { ListingAction, ListingProfile, ListingReview, ListingStatus } from '@/types'

type Variant = 'default' | 'success' | 'warning' | 'danger' | 'info'

export const LISTING_STATUS: Record<ListingStatus, { label: string; variant: Variant }> = {
  NONE:      { label: 'Private', variant: 'default' },
  PENDING:   { label: 'In review',  variant: 'info' },
  APPROVED:  { label: 'Public',     variant: 'success' },
  REJECTED:  { label: 'Not approved', variant: 'danger' },
  SUSPENDED: { label: 'Suspended',  variant: 'warning' },
}

const ACTION_LABEL: Record<ListingAction, string> = {
  SUBMITTED: 'Sent for review',
  APPROVED: 'Approved',
  REJECTED: 'Not approved',
  WITHDRAWN: 'Request withdrawn',
  UNLISTED: 'Unlisted',
  SUSPENDED: 'Suspended',
  REINSTATED: 'Reinstated',
}

export function ListingStatusBadge({ status }: { status: ListingStatus }) {
  const s = LISTING_STATUS[status]
  return <Badge variant={s.variant}>{s.label}</Badge>
}

export function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

/** The organization's logo, or a plain mark when it has none or the image can't load. */
export function OrgLogo({ profile, size = 48 }: { profile: ListingProfile; size?: number }) {
  const style = { width: size, height: size }
  if (profile.logoUrl) {
    return (
      <img src={profile.logoUrl} alt="" referrerPolicy="no-referrer" style={style}
        className="shrink-0 rounded-xl border border-gray-200 bg-white object-contain dark:border-gray-800"
        onError={e => { e.currentTarget.style.visibility = 'hidden' }} />
    )
  }
  return (
    <span style={style} aria-hidden
      className="grid shrink-0 place-items-center rounded-xl bg-brand-100 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300">
      <Building2 className="h-1/2 w-1/2" />
    </span>
  )
}

/** A public profile, as people see it on Discover. */
export function ProfilePreview({ profile }: { profile: ListingProfile }) {
  return (
    <div className="flex gap-4">
      <OrgLogo profile={profile} size={56} />
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-center gap-2">
          <span className="text-lg font-semibold text-gray-900 dark:text-gray-100">{profile.displayName}</span>
          <Badge>{categoryLabel(profile.category)}</Badge>
        </p>
        {profile.description && (
          <p className="mt-1 whitespace-pre-line text-sm text-gray-600 dark:text-gray-300">{profile.description}</p>
        )}
        {profile.website && (
          <a href={profile.website} target="_blank" rel="noopener noreferrer nofollow"
            className="mt-2 inline-flex items-center gap-1 text-sm text-brand-600 hover:underline dark:text-brand-400">
            {profile.website.replace(/^https:\/\//, '')}<ExternalLink className="h-3.5 w-3.5" />
          </a>
        )}
      </div>
    </div>
  )
}

export function ReviewHistory({ history }: { history: ListingReview[] }) {
  if (history.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">Nothing yet.</p>
  return (
    <ol className="space-y-3">
      {history.map(r => (
        <li key={r.id} className="text-sm">
          <p className="font-medium text-gray-900 dark:text-gray-100">
            {ACTION_LABEL[r.action]}
            <span className="ml-2 font-normal text-gray-500 dark:text-gray-400">
              {new Date(r.createdAt).toLocaleString()}{r.actorEmail ? ` · ${r.actorEmail}` : ''}
            </span>
          </p>
          {r.note && <p className="mt-0.5 whitespace-pre-line text-gray-600 dark:text-gray-300">{r.note}</p>}
        </li>
      ))}
    </ol>
  )
}
