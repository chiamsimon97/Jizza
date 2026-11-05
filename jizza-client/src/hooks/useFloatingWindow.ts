import { useCallback, useEffect, useRef, useState } from 'react'

/** Chromium's Document Picture-in-Picture API (Chrome and Edge 116+), not yet in the DOM types. */
interface DocumentPictureInPicture {
  requestWindow(options?: { width?: number; height?: number }): Promise<Window>
  window: Window | null
}

function api(): DocumentPictureInPicture | undefined {
  return (window as unknown as { documentPictureInPicture?: DocumentPictureInPicture }).documentPictureInPicture
}

/** Whether this browser can open a small always-on-top window. */
export function floatingWindowSupported(): boolean {
  return typeof window !== 'undefined' && api() !== undefined
}

/** Copies the app's styles and theme into the floating window so it looks like Jizza. */
function adopt(target: Window) {
  for (const node of document.querySelectorAll('style, link[rel="stylesheet"]')) {
    target.document.head.appendChild(node.cloneNode(true))
  }
  target.document.documentElement.className = document.documentElement.className
  target.document.body.className = 'bg-white text-gray-900 dark:bg-gray-900 dark:text-gray-100'
  target.document.title = 'Jizza'
}

/**
 * A small window that stays on top of every other window, for controls a person needs while
 * working in another app or window (recording in Chrome). It must be opened from a click. It
 * closes when the person closes it, when {@link close} is called, or when the component unmounts.
 */
export function useFloatingWindow() {
  const [floating, setFloating] = useState<Window | null>(null)
  const ours = useRef<Window | null>(null)

  const open = useCallback(async (size: { width: number; height: number }) => {
    const pip = api()
    if (!pip) return null
    if (ours.current && !ours.current.closed) return ours.current
    pip.window?.close()
    const opened = await pip.requestWindow(size)
    adopt(opened)
    opened.addEventListener('pagehide', () => { ours.current = null; setFloating(null) }, { once: true })
    ours.current = opened
    setFloating(opened)
    return opened
  }, [])

  const close = useCallback(() => {
    ours.current?.close()
    ours.current = null
    setFloating(null)
  }, [])

  useEffect(() => () => { ours.current?.close() }, [])

  return { floating, open, close, supported: floatingWindowSupported() }
}
