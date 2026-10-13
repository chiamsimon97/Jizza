import { type ReactNode, useEffect } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'

interface SlideOverProps {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}

/** A panel that slides in from the right for create and edit forms. */
export function SlideOver({ open, title, description, onClose, children, footer }: SlideOverProps) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null
  // Portalled to <body>: pages animate in with a transform, which would otherwise pin this
  // "fixed" panel to the page instead of the window.
  return createPortal(
    <div className="fixed inset-0 z-40 flex justify-end">
      <div className="absolute inset-0 bg-gray-950/40 backdrop-blur-[2px] animate-fade-in" onClick={onClose} />
      <section
        role="dialog"
        aria-label={title}
        className="relative flex h-full w-full max-w-xl flex-col bg-white shadow-2xl animate-slide-in-right dark:bg-gray-900"
      >
        <header className="flex items-start gap-3 border-b border-gray-200 px-6 py-4 dark:border-gray-800">
          <div className="min-w-0 flex-1">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
            {description && <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{description}</p>}
          </div>
          <button onClick={onClose} aria-label="Close" className="grid h-8 w-8 place-items-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900 dark:hover:bg-gray-800 dark:hover:text-gray-100">
            <X className="h-4 w-4" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">{children}</div>
        {footer && <footer className="flex justify-end gap-2 border-t border-gray-200 px-6 py-4 dark:border-gray-800">{footer}</footer>}
      </section>
    </div>,
    document.body,
  )
}

/** Shared look for selects and textareas so they match {@link Input}. */
export const fieldClass =
  'w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 shadow-sm transition-all placeholder-gray-400 hover:border-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:placeholder-gray-500 dark:hover:border-gray-600'

export function Field({ label, hint, children, highlight }: { label: string; hint?: string; children: ReactNode; highlight?: boolean }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-sm font-medium text-gray-700 dark:text-gray-300">{label}</span>
      <span className={highlight ? 'rounded-lg animate-jizza-filled' : ''}>{children}</span>
      {hint && <span className="text-xs text-gray-400">{hint}</span>}
    </label>
  )
}
