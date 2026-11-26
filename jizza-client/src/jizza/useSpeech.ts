import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { voiceApi } from '@/api/voice'
import { toWav } from './wav'

// The Web Speech API is not in TypeScript's DOM lib yet; this is the slice we use.
interface Recognition {
  lang: string
  interimResults: boolean
  continuous: boolean
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }) => void) | null
  onend: (() => void) | null
  onerror: ((e: { error?: string }) => void) | null
  onstart: (() => void) | null
  onaudiostart: (() => void) | null
  start: () => void
  stop: () => void
}

function recognitionCtor(): (new () => Recognition) | undefined {
  if (typeof window === 'undefined') return undefined
  const w = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition
}

/** Languages people can pick to speak in. "auto" works only when an AI key hears the recording. */
export const VOICE_LANGUAGES: { tag: string; name: string; native?: string }[] = [
  { tag: 'auto', name: 'Any language (detect it)' },
  { tag: 'en-IN', name: 'English (India)' },
  { tag: 'en-US', name: 'English (US)' },
  { tag: 'en-GB', name: 'English (UK)' },
  { tag: 'hi-IN', name: 'Hindi', native: 'हिन्दी' },
  { tag: 'ta-IN', name: 'Tamil', native: 'தமிழ்' },
  { tag: 'te-IN', name: 'Telugu', native: 'తెలుగు' },
  { tag: 'kn-IN', name: 'Kannada', native: 'ಕನ್ನಡ' },
  { tag: 'ml-IN', name: 'Malayalam', native: 'മലയാളം' },
  { tag: 'mr-IN', name: 'Marathi', native: 'मराठी' },
  { tag: 'bn-IN', name: 'Bengali', native: 'বাংলা' },
  { tag: 'gu-IN', name: 'Gujarati', native: 'ગુજરાતી' },
  { tag: 'pa-IN', name: 'Punjabi', native: 'ਪੰਜਾਬੀ' },
  { tag: 'ur-IN', name: 'Urdu', native: 'اردو' },
  { tag: 'or-IN', name: 'Odia', native: 'ଓଡ଼ିଆ' },
  { tag: 'es-ES', name: 'Spanish', native: 'Español' },
  { tag: 'fr-FR', name: 'French', native: 'Français' },
  { tag: 'de-DE', name: 'German', native: 'Deutsch' },
  { tag: 'ar-SA', name: 'Arabic', native: 'العربية' },
  { tag: 'zh-CN', name: 'Chinese', native: '中文' },
  { tag: 'ja-JP', name: 'Japanese', native: '日本語' },
]

export interface VoiceSettings {
  /** The language spoken, a BCP-47 tag, or "auto". */
  language: string
  /** Put what was said into the chat box in English. */
  english: boolean
}

const SETTINGS_KEY = 'jizza.voice'
const MAX_SECONDS = 120

function loadSettings(): VoiceSettings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? 'null') as Partial<VoiceSettings> | null
    if (saved && typeof saved.language === 'string') return { language: saved.language, english: saved.english !== false }
  } catch { /* storage blocked: use the defaults */ }
  return { language: 'auto', english: true }
}

/** How speech becomes text here: the person's own AI key, the browser's own service, or not at all. */
export type VoiceMode = 'key' | 'browser' | 'none'
export type VoicePhase = 'idle' | 'listening' | 'working'

const isEnglish = (tag: string) => tag.toLowerCase().startsWith('en')

/** The browser's own error, said plainly with what to do about it. */
export function browserErrorMessage(code: string | undefined): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Jizza isn\'t allowed to use the microphone. Allow it from the icon in the address bar, and for your browser in macOS System Settings, Privacy & Security, Microphone.'
    case 'network':
      return 'This browser couldn\'t reach its speech service. Ulaa and some other Chromium browsers don\'t include one. Use Chrome, or connect OpenAI, Google Gemini or OpenRouter under Settings, AI models so Jizza hears you with your key.'
    case 'audio-capture':
      return 'No microphone was found. Check that one is connected and picked in your system settings.'
    case 'language-not-supported':
      return 'This browser can\'t hear that language. Pick another, or connect OpenAI, Google Gemini or OpenRouter under Settings, AI models.'
    case 'no-speech':
      return 'Didn\'t hear anything. Try again a little closer to the microphone.'
    default:
      return `The microphone stopped (${code ?? 'unknown error'}). Try again.`
  }
}

function micErrorMessage(e: unknown): string {
  const name = (e as { name?: string })?.name
  if (name === 'NotAllowedError' || name === 'SecurityError') return browserErrorMessage('not-allowed')
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return browserErrorMessage('audio-capture')
  if (name === 'NotReadableError') return 'The microphone is busy in another app. Close it there and try again.'
  return e instanceof Error && e.message ? e.message : 'The microphone could not be started.'
}

/**
 * Speaking to Jizza (the chat box's microphone). What was said lands in the box for the person to
 * check and send, in English when they want, whatever language they spoke.
 *
 * <p>With an OpenAI, Gemini or OpenRouter key connected, a recording is sent to it: that works in
 * every browser and copes with people mixing languages. Otherwise the browser's own speech service
 * writes down what it hears in the picked language, and Jizza translates it.
 */
export function useVoiceInput(onText: (text: string) => void) {
  const [settings, setSettingsState] = useState<VoiceSettings>(loadSettings)
  const [phase, setPhase] = useState<VoicePhase>('idle')
  const [interim, setInterim] = useState('')
  const [error, setError] = useState<string | null>(null)
  const setup = useQuery({ queryKey: ['voice-setup'], queryFn: voiceApi.setup, staleTime: 60_000, retry: false })
  const listener = setup.data?.listener ?? null
  const canRecord = typeof window !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== 'undefined'
  const mode: VoiceMode = listener && canRecord ? 'key' : recognitionCtor() ? 'browser' : 'none'

  const recRef = useRef<Recognition | null>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const timerRef = useRef<number | null>(null)
  const onTextRef = useRef(onText)
  onTextRef.current = onText

  const setSettings = useCallback((s: VoiceSettings) => {
    setSettingsState(s)
    try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(s)) } catch { /* not remembered */ }
  }, [])

  const deliver = (text: string) => { if (text.trim()) onTextRef.current(text.trim()) }

  const stop = useCallback(() => {
    if (timerRef.current) { window.clearTimeout(timerRef.current); timerRef.current = null }
    if (recorderRef.current && recorderRef.current.state !== 'inactive') recorderRef.current.stop()
    recRef.current?.stop()
  }, [])

  const startRecording = async () => {
    if (!window.isSecureContext) {
      setError('The microphone only works on https:// or localhost addresses.')
      return
    }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
    } catch (e) {
      setError(micErrorMessage(e))
      return
    }
    const recorder = new MediaRecorder(stream)
    const chunks: Blob[] = []
    recorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data) }
    recorder.onstop = async () => {
      stream.getTracks().forEach(t => t.stop())
      recorderRef.current = null
      const recording = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' })
      if (recording.size === 0) { setPhase('idle'); setError(browserErrorMessage('no-speech')); return }
      setPhase('working')
      try {
        const wav = await toWav(recording)
        const heard = await voiceApi.transcribe(wav, settings.language, settings.english)
        if (heard.text.trim()) deliver(heard.text)
        else setError(browserErrorMessage('no-speech'))
      } catch (e) {
        setError(e instanceof Error && e.message ? e.message : 'Couldn\'t turn that into text. Try again.')
      } finally {
        setPhase('idle')
      }
    }
    recorderRef.current = recorder
    recorder.start()
    setPhase('listening')
    timerRef.current = window.setTimeout(() => stop(), MAX_SECONDS * 1000)
  }

  const startBrowser = () => {
    const Ctor = recognitionCtor()
    if (!Ctor) return
    const language = settings.language === 'auto' ? (navigator.language || 'en-US') : settings.language
    const rec = new Ctor()
    rec.lang = language
    rec.interimResults = true
    rec.continuous = true
    let finalText = ''
    let latest = ''
    let failed = false
    // A Chromium browser without Google's speech service (Ulaa, for one) accepts start() and then
    // does nothing at all: no start, no error. Notice that, rather than listen forever.
    let started = false
    rec.onstart = () => { started = true }
    rec.onaudiostart = () => { started = true }
    const watchdog = window.setTimeout(() => {
      if (started || recRef.current !== rec) return
      failed = true
      setError(browserErrorMessage('network'))
      rec.stop()
    }, 5000)
    rec.onresult = (e) => {
      let done = ''
      let all = ''
      for (let i = 0; i < e.results.length; i++) {
        all += e.results[i][0].transcript
        if (e.results[i].isFinal) done += e.results[i][0].transcript
      }
      finalText = done
      latest = all
      setInterim(all)
    }
    rec.onerror = (e) => {
      // "aborted" is our own stop; "no-speech" just ends a quiet session.
      if (e.error === 'aborted') return
      failed = true
      setError(browserErrorMessage(e.error))
    }
    rec.onend = async () => {
      window.clearTimeout(watchdog)
      recRef.current = null
      if (!started && !failed) { failed = true; setError(browserErrorMessage('network')) }
      setInterim('')
      // Chrome can end before the last words are marked final; keep what was on screen.
      const text = (finalText || latest).trim()
      if (!text) { setPhase('idle'); if (!failed) setError(browserErrorMessage('no-speech')); return }
      if (!settings.english || isEnglish(language)) { setPhase('idle'); deliver(text); return }
      setPhase('working')
      try {
        deliver((await voiceApi.translate(text, language)).text)
      } catch {
        // Better what they said than nothing: it goes in as heard.
        deliver(text)
        setError('Couldn\'t translate just now, so it went in as you said it.')
      } finally {
        setPhase('idle')
      }
    }
    recRef.current = rec
    setPhase('listening')
    try {
      rec.start()
    } catch (e) {
      recRef.current = null
      setPhase('idle')
      setError(micErrorMessage(e))
    }
  }

  const start = useCallback(() => {
    if (phase !== 'idle') return
    setError(null)
    if (mode === 'key') void startRecording()
    else if (mode === 'browser') startBrowser()
    else setError('This browser can\'t turn speech into text. Connect OpenAI, Google Gemini or OpenRouter under Settings, AI models, and Jizza will hear you with your key.')
  }, [phase, mode, settings]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => () => {
    if (timerRef.current) window.clearTimeout(timerRef.current)
    recRef.current?.stop()
    const r = recorderRef.current
    if (r && r.state !== 'inactive') r.stop()
  }, [])

  return {
    mode, phase, listening: phase === 'listening', interim, error, clearError: () => setError(null),
    start, stop, settings, setSettings, listenerName: setup.data?.listenerName ?? null,
  }
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
