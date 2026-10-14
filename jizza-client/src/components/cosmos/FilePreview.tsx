import { useEffect, useState } from 'react'
import { Download } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { SlideOver } from '@/components/ui/SlideOver'
import { memoryApi } from '@/api/memory'
import type { MemoryFile } from '@/types'

/** A file the user gave an agent, shown back to them. */
export function FilePreview({ agentId, file, onClose }: { agentId: string; file: MemoryFile | null; onClose: () => void }) {
  const url = useObjectUrl(file, agentId)
  const [text, setText] = useState<string | null>(null)
  const isText = !!file && /^(text\/|application\/json)/.test(file.contentType)

  useEffect(() => {
    setText(null)
    if (!file || !isText) return
    let cancelled = false
    memoryApi.content(agentId, file.id).then(b => b.text()).then(t => { if (!cancelled) setText(t) })
    return () => { cancelled = true }
  }, [agentId, file, isText])

  if (!file) return null
  const previewable = file.contentType.startsWith('image/') || file.contentType === 'application/pdf' || isText

  return (
    <SlideOver open title={file.filename} onClose={onClose}
      description={`${formatBytes(file.sizeBytes)} · ${file.contentType}`}
      footer={url && (
        <a href={url} download={file.filename}>
          <Button variant="secondary"><Download className="h-4 w-4" /> Download</Button>
        </a>
      )}>
      {!url && <div className="flex justify-center py-10"><Spinner /></div>}
      {url && file.contentType.startsWith('image/') && (
        <img src={url} alt={file.filename} className="w-full rounded-lg" />
      )}
      {url && file.contentType === 'application/pdf' && (
        <iframe src={url} title={file.filename} className="h-[70vh] w-full rounded-lg border border-gray-200 dark:border-gray-800" />
      )}
      {isText && text !== null && (
        <pre className="whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-4 text-sm text-gray-800 dark:bg-gray-950 dark:text-gray-200">{text}</pre>
      )}
      {url && !previewable && (
        <p className="text-sm text-gray-500 dark:text-gray-400">This file can't be previewed here. Download it to open it.</p>
      )}
    </SlideOver>
  )
}

/** Fetches a memory file's bytes and exposes them as a short-lived object URL. */
export function useObjectUrl(file: MemoryFile | null, agentId?: string) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    setUrl(null)
    if (!file) return
    let objectUrl: string | null = null
    let cancelled = false
    memoryApi.content(agentId ?? file.agentId, file.id).then(blob => {
      if (cancelled) return
      objectUrl = URL.createObjectURL(blob)
      setUrl(objectUrl)
    }).catch(() => {})
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [file, agentId])
  return url
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
