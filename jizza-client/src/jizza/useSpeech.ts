import { useCallback, useEffect, useRef, useState } from 'react'

// The Web Speech API is not in TypeScript's DOM lib yet; this is the slice we use.
interface Recognition {
  lang: string
  interimResults: boolean
  continuous: boolean
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }) => void) | null
  onend: (() => void) | null
  onerror: (() => void) | null
  start: () => void
  stop: () => void
}

function recognitionCtor(): (new () => Recognition) | undefined {
  const w = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition
}

/** Push-to-talk dictation. {@code onFinal} gets the whole utterance once the user stops. */
export function useDictation(onFinal: (text: string) => void) {
  const [listening, setListening] = useState(false)
  const [interim, setInterim] = useState('')
  const recRef = useRef<Recognition | null>(null)
  const supported = typeof window !== 'undefined' && !!recognitionCtor()

  const stop = useCallback(() => recRef.current?.stop(), [])

  const start = useCallback(() => {
    const Ctor = recognitionCtor()
    if (!Ctor || recRef.current) return
    const rec = new Ctor()
    rec.lang = navigator.language || 'en-US'
    rec.interimResults = true
    rec.continuous = false
    let finalText = ''
    rec.onresult = (e) => {
      let text = ''
      for (let i = 0; i < e.results.length; i++) {
        text += e.results[i][0].transcript
        if (e.results[i].isFinal) finalText = text
      }
      setInterim(text)
    }
    rec.onend = () => {
      recRef.current = null
      setListening(false)
      setInterim('')
      if (finalText.trim()) onFinal(finalText.trim())
    }
    rec.onerror = () => rec.stop()
    recRef.current = rec
    setListening(true)
    rec.start()
  }, [onFinal])

  useEffect(() => () => recRef.current?.stop(), [])

  return { supported, listening, interim, start, stop }
}

/** Reads a reply aloud, cutting off whatever was being read before. */
export function speak(text: string) {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) return
  window.speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(text.slice(0, 600))
  utterance.rate = 1.05
  window.speechSynthesis.speak(utterance)
}

export const canSpeak = typeof window !== 'undefined' && 'speechSynthesis' in window
