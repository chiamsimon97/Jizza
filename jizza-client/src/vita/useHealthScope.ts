import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { DEFAULT_VIEW, findView } from './nav'

/** The Health tab's scope lives in the URL, so a link or a reload lands on the same screen. */
export interface HealthScope {
  view: string
  /** A person's id, or null for "You". */
  person: string | null
  /** The condition whose page is open. */
  condition?: string | null
  /** A kind of reading to open Vitals on. */
  kind?: string | null
  /** The document open for review. */
  doc?: string | null
}

export function useHealthScope(): [HealthScope, (patch: Partial<HealthScope>) => void] {
  const [params, setParams] = useSearchParams()
  const scope: HealthScope = { view: findView(params.get('view')).id, person: params.get('person'), condition: params.get('condition'), kind: params.get('kind'), doc: params.get('doc') }
  const update = useCallback((patch: Partial<HealthScope>) => {
    setParams(prev => {
      const next = new URLSearchParams(prev)
      for (const [key, value] of Object.entries(patch)) {
        if (value == null || (key === 'view' && value === DEFAULT_VIEW)) next.delete(key)
        else next.set(key, value)
      }
      return next
    }, { replace: true })
  }, [setParams])
  return [scope, update]
}
