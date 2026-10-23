import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import { Button } from './Button'

interface Props {
  children: ReactNode
  /** Changing it clears a caught error, e.g. the current path, so navigating away recovers. */
  resetKey?: unknown
  /** The whole app, rather than one page: offers a reload only. */
  fullPage?: boolean
}

interface State { error: Error | null }

/**
 * Catches an error thrown while a page renders, so one broken page shows a message the user can
 * act on instead of leaving the whole window blank.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('A page failed to render', error, info.componentStack)
  }

  componentDidUpdate(prev: Props) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null })
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div role="alert" className={`grid place-items-center p-6 ${this.props.fullPage ? 'min-h-screen' : 'min-h-[60vh]'}`}>
        <div className="max-w-md text-center">
          <AlertTriangle className="mx-auto mb-3 h-8 w-8 text-amber-500" />
          <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">This page hit a problem</h2>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Nothing you saved is lost. Try again, or reload the app if it keeps happening.
          </p>
          <div className="mt-4 flex justify-center gap-2">
            {!this.props.fullPage && (
              <Button size="sm" variant="secondary" onClick={() => this.setState({ error: null })}>Try again</Button>
            )}
            <Button size="sm" onClick={() => window.location.reload()}><RefreshCw className="h-4 w-4" />Reload</Button>
          </div>
        </div>
      </div>
    )
  }
}
