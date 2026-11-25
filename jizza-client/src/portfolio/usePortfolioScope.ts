import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { DEFAULT_VIEW, findView } from './nav'

/** The Portfolio's scope lives in the URL, so a link or a reload lands on the same screen. */
export interface PortfolioScope {
  view: string
  /** 'all', or a person's id. */
  person: string
  /** 'all', 'unassigned' or a goal's id. */
  goal: string
  /** '1' while looking around with the sample household. */
  sample: '1' | null
  /** The Quick start question to open on. */
  step: string | null
}

/** A change to the scope; null (or a default) removes the key from the URL. */
export type ScopePatch = { [K in keyof PortfolioScope]?: PortfolioScope[K] | null }

export function usePortfolioScope(): [PortfolioScope, (patch: ScopePatch) => void] {
  const [params, setParams] = useSearchParams()
  const scope: PortfolioScope = {
    view: findView(params.get('view')).id,
    person: params.get('person') ?? 'all',
    goal: params.get('goal') ?? 'all',
    sample: params.get('sample') === '1' ? '1' : null,
    step: params.get('step'),
  }
  const update = useCallback((patch: ScopePatch) => {
    setParams(prev => {
      const next = new URLSearchParams(prev)
      for (const [key, value] of Object.entries(patch)) {
        const isDefault = value == null || (key === 'view' ? value === DEFAULT_VIEW : value === 'all')
        if (isDefault) next.delete(key)
        else next.set(key, value)
      }
      return next
    }, { replace: true })
  }, [setParams])
  return [scope, update]
}
