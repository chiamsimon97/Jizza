import { useState } from 'react'
import { Maximize2 } from 'lucide-react'
import { devicesApi } from '@/api/devices'
import { Dialog } from '@/components/ui/Dialog'

/**
 * The element's small preview, and when the browser sent one, a "See on page" view: the visible
 * page with the element outlined, so a person can tell which of several look-alike buttons a step
 * uses. Older browser agents send only the preview.
 */
export function ElementPreview({ deviceId, preview, pagePreview, className, label = 'Element preview' }: {
  deviceId: string
  preview?: string | null
  pagePreview?: string | null
  className?: string
  label?: string
}) {
  const [open, setOpen] = useState(false)
  if (!preview && !pagePreview) return null
  const small = preview ? <img className={className} alt={label} src={devicesApi.fileUrl(deviceId, preview)} /> : null
  if (!pagePreview) return small
  return <>
    <button type="button" onClick={() => setOpen(true)} aria-label="See on page" title="See on page"
      className="group relative inline-block shrink-0 rounded-md text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500">
      {small ?? <span className={`${className ?? ''} grid place-items-center bg-gray-100 text-[11px] text-gray-500 dark:bg-gray-800`}>Page</span>}
      <span className="absolute right-1 top-1 rounded bg-white/90 p-0.5 text-gray-700 opacity-80 shadow group-hover:opacity-100 dark:bg-gray-900/90 dark:text-gray-200">
        <Maximize2 className="h-3 w-3" />
      </span>
    </button>
    <Dialog open={open} size="xl" title="On the page" description="The outlined element is the one this step uses." onClose={() => setOpen(false)}>
      <img className="max-h-[75vh] w-full rounded-lg border border-gray-200 object-contain dark:border-gray-700"
        alt="The page with the element outlined" src={devicesApi.fileUrl(deviceId, pagePreview)} />
    </Dialog>
  </>
}
