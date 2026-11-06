import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { endLocalSession } from '@/lib/session'

// Off unless a deployment asks for it: the server already ends a session after 3 days without
// activity, and a 15-minute browser timer signed people out while they worked in another window.
const IDLE_MINUTES = Number(import.meta.env.VITE_IDLE_SIGN_OUT_MINUTES ?? 0)
const WARNING_SECONDS = 60
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const
const LAST_ACTIVE_KEY = 'jizza-last-active'

/**
 * When VITE_IDLE_SIGN_OUT_MINUTES is set above 0, signs out after that stretch of no activity, so an
 * unattended screen does not stay open on the user's memory and data. A minute before, it warns
 * and offers to stay. Activity in any tab counts, through a shared timestamp.
 */
export function useIdleSignOut() {
  const navigate = useNavigate()
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null)
  const lastActive = useRef(Date.now())

  const markActive = useCallback(() => {
    lastActive.current = Date.now()
    try { localStorage.setItem(LAST_ACTIVE_KEY, String(lastActive.current)) } catch { /* ignore */ }
    setSecondsLeft(null)
  }, [])

  useEffect(() => {
    if (!(IDLE_MINUTES > 0)) return
    const onActivity = () => {
      // Throttle storage writes: only note activity a few seconds apart.
      if (Date.now() - lastActive.current > 5_000) markActive()
    }
    ACTIVITY_EVENTS.forEach(e => window.addEventListener(e, onActivity, { passive: true }))

    const tick = window.setInterval(async () => {
      let last = lastActive.current
      try { last = Math.max(last, Number(localStorage.getItem(LAST_ACTIVE_KEY) ?? 0)) } catch { /* ignore */ }
      const remaining = Math.round((last + IDLE_MINUTES * 60_000 - Date.now()) / 1000)
      if (remaining <= 0) {
        window.clearInterval(tick)
        try { await authApi.logout() } catch { /* the session may already be gone */ }
        endLocalSession()
        navigate('/login', { replace: true, state: { notice: 'You were signed out after a period of inactivity.' } })
      } else {
        setSecondsLeft(remaining <= WARNING_SECONDS ? remaining : null)
      }
    }, 1000)

    return () => {
      window.clearInterval(tick)
      ACTIVITY_EVENTS.forEach(e => window.removeEventListener(e, onActivity))
    }
  }, [markActive, navigate])

  return { secondsLeft, stay: markActive }
}
