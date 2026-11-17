import { useEffect, useRef, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { runsApi, type LivePicture } from '@/api/runs'
import { devicesApi } from '@/api/devices'

/** How often a new picture is asked for while the view is open. */
export const LIVE_EVERY_MS = 4_000

/** Takes one live picture: asks for it, and follows it up while the browser is still taking it. */
export async function takeLivePicture(runId: string): Promise<LivePicture> {
  let picture = await runsApi.liveView(runId)
  for (let i = 0; i < 3 && picture.status === 'pending' && picture.commandId; i += 1) {
    picture = await runsApi.livePicture(runId, picture.commandId)
  }
  return picture
}

/**
 * "Watch the browser" (plan 06, live view): while a run is going, shows what its browser shows,
 * refreshed every few seconds. To take over, the person uses Pause on the browser itself, does
 * what is needed, then carries on; a step they did by hand can be skipped from its card.
 */
export function LiveView({ runId }: { runId: string }) {
  const [open, setOpen] = useState(false)
  const [picture, setPicture] = useState<{ device: string; file: string } | null>(null)
  const [problem, setProblem] = useState('')
  const busy = useRef(false)

  useEffect(() => {
    if (!open) return
    let stopped = false
    async function look() {
      if (busy.current || stopped) return
      busy.current = true
      try {
        const p = await takeLivePicture(runId)
        if (stopped) return
        if (p.status === 'ready' && p.device && p.file) {
          setPicture({ device: p.device, file: p.file })
          setProblem('')
        } else if (p.status === 'failed') {
          setProblem(p.problem || 'The browser could not show the page')
        }
      } catch (e) {
        if (!stopped) setProblem(e instanceof Error ? e.message : 'The browser could not show the page')
      } finally {
        busy.current = false
      }
    }
    void look()
    const timer = setInterval(() => void look(), LIVE_EVERY_MS)
    return () => {
      stopped = true
      clearInterval(timer)
    }
  }, [open, runId])

  return (
    <div className="mt-2" data-testid="live-view">
      <button type="button" onClick={() => setOpen(o => !o)}
        className="inline-flex items-center gap-1 text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
        {open ? <EyeOff className="h-3 w-3" aria-hidden /> : <Eye className="h-3 w-3" aria-hidden />}
        {open ? 'Stop watching' : 'Watch the browser'}
      </button>
      {open && (
        <div className="mt-2 space-y-1">
          {picture
            ? <img src={devicesApi.fileUrl(picture.device, picture.file)} alt="What the browser shows now"
                className="max-h-96 w-full rounded-lg border border-gray-200 object-contain dark:border-gray-800" />
            : !problem && <p className="text-xs text-gray-500 dark:text-gray-400">Getting a picture of the page…</p>}
          {problem && <p className="text-xs text-red-600 dark:text-red-400">{problem}</p>}
          <p className="text-xs text-gray-500 dark:text-gray-400">
            To take over, choose Pause automation in the Jizza extension on that browser and do what is needed there. Then choose Resume automation, and press Try again or Skip on the step's card.
          </p>
        </div>
      )}
    </div>
  )
}
