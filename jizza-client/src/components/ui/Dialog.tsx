import { type ReactNode, useEffect } from 'react'
import { createPortal } from 'react-dom'

interface DialogProps {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
}

/** A small centred dialog. Sits below the step-up prompt, which can open on top of it. */
export function Dialog({ open, title, description, onClose, children }: DialogProps) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null
  return createPortal(
    <div className="fixed inset-0 z-40 grid place-items-center px-4">
      <div className="absolute inset-0 bg-gray-950/50 backdrop-blur-[2px] animate-fade-in" onClick={onClose} />
      <section role="dialog" aria-label={title}
        className="relative w-full max-w-sm rounded-xl bg-white p-6 shadow-2xl dark:bg-gray-900">
        <h2 className="font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
        {description && <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{description}</p>}
        <div className="mt-4">{children}</div>
      </section>
    </div>,
    document.body,
  )
}
