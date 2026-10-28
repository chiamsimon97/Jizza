import { type ReactNode, useEffect } from 'react'
import { createPortal } from 'react-dom'

interface DialogProps {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  /** sm by default; lg and xl for dialogs that carry a form or a setup guide. */
  size?: 'sm' | 'lg' | 'xl'
  /** Drop the title block and padding, for dialogs that lay out their own header. */
  bare?: boolean
}

const WIDTHS = { sm: 'max-w-sm', lg: 'max-w-2xl', xl: 'max-w-5xl' }

/** A small centred dialog. Sits below the step-up prompt, which can open on top of it. */
export function Dialog({ open, title, description, onClose, children, size = 'sm', bare }: DialogProps) {
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
        className={`relative max-h-[92vh] w-full overflow-y-auto rounded-xl bg-white shadow-2xl dark:bg-gray-900 ${WIDTHS[size]} ${bare ? '' : 'p-6'}`}>
        {bare ? children : (
          <>
            <h2 className="font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
            {description && <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{description}</p>}
            <div className="mt-4">{children}</div>
          </>
        )}
      </section>
    </div>,
    document.body,
  )
}
