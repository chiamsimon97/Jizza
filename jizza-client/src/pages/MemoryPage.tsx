import { useEffect, useRef, useState, type DragEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Brain, Download, Eye, FileImage, FileSpreadsheet, FileText, File as FileIcon, NotebookPen,
  Trash2, Upload,
} from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { SlideOver } from '@/components/ui/SlideOver'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { agentsApi } from '@/api/agents'
import { memoryApi } from '@/api/memory'
import type { AgentDto, MemoryFile, MemoryNote } from '@/types'

/** Each agent's memory: the notes it saved about you and the files you gave it. */
export function MemoryPage() {
  const { agentId } = useParams()
  const navigate = useNavigate()
  const { data: agents, isLoading } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })

  const ordered = [...(agents ?? [])].sort((a, b) => Number(b.key === 'jizza') - Number(a.key === 'jizza'))
  const selected = ordered.find(a => a.id === agentId) ?? ordered[0]

  useEffect(() => {
    if (selected && selected.id !== agentId) navigate(`/memory/${selected.id}`, { replace: true })
  }, [selected, agentId, navigate])

  return (
    <div className="page">
      <PageHeader icon={Brain} title="Memory"
        description="What each agent knows about you. An agent can only use its own memory." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && ordered.length === 0 && (
        <EmptyState icon={Brain} title="No agents yet" description="Agents appear here once the system is set up." />
      )}

      {ordered.length > 0 && (
        <div role="tablist" aria-label="Agents" className="mb-6 flex gap-2 overflow-x-auto pb-1">
          {ordered.map(agent => (
            <button key={agent.id} role="tab" aria-selected={agent.id === selected?.id}
              onClick={() => navigate(`/memory/${agent.id}`)}
              className={`flex shrink-0 items-center gap-2 rounded-xl border px-3 py-2 text-sm font-medium transition-colors ${
                agent.id === selected?.id
                  ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-950/40 dark:text-brand-200'
                  : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300 dark:hover:border-gray-700'
              }`}>
              <AgentLogo agentKey={agent.key} state={agent.enabled ? 'idle' : 'off'} size={28} />
              {agent.name}
            </button>
          ))}
        </div>
      )}

      {selected && <AgentMemoryPanel key={selected.id} agent={selected} />}
    </div>
  )
}

function AgentMemoryPanel({ agent }: { agent: AgentDto }) {
  const qc = useQueryClient()
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const [preview, setPreview] = useState<MemoryFile | null>(null)

  const queryKey = ['memory', agent.id]
  const { data, isLoading } = useQuery({ queryKey, queryFn: () => memoryApi.get(agent.id) })
  const refresh = () => qc.invalidateQueries({ queryKey })

  const upload = useMutation({
    mutationFn: async (files: File[]) => {
      for (const file of files) await memoryApi.upload(agent.id, file)
    },
    onMutate: () => setUploadError(''),
    onError: (err: unknown) => setUploadError(err instanceof Error ? err.message : 'Upload failed'),
    onSettled: refresh,
  })
  const deleteFile = useMutation({
    mutationFn: (id: string) => memoryApi.deleteFile(agent.id, id),
    onSuccess: refresh,
  })
  const deleteNote = useMutation({
    mutationFn: (id: string) => memoryApi.deleteNote(agent.id, id),
    onSuccess: refresh,
  })

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    if (e.dataTransfer.files.length) upload.mutate(Array.from(e.dataTransfer.files))
  }

  const files = data?.files ?? []
  const notes = data?.notes ?? []

  return (
    <div className="stagger flex flex-col gap-6">
      <div
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={`flex flex-wrap items-center gap-4 rounded-xl border-2 border-dashed p-5 transition-colors ${
          dragging
            ? 'border-brand-400 bg-brand-50 dark:border-brand-500 dark:bg-brand-950/30'
            : 'border-gray-200 dark:border-gray-800'
        }`}>
        <AgentLogo agentKey={agent.key} state={upload.isPending ? 'working' : 'idle'} size={48} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold text-gray-900 dark:text-gray-100">{agent.name}'s memory</p>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {files.length} {files.length === 1 ? 'file' : 'files'} · {notes.length} {notes.length === 1 ? 'note' : 'notes'}.
            Drop documents, PDFs or images here to teach {agent.name} about you. Up to 10 MB each.
          </p>
          {uploadError && <p className="mt-1 text-sm text-red-500">{uploadError}</p>}
        </div>
        <input ref={inputRef} type="file" multiple hidden
          accept="image/png,image/jpeg,image/gif,image/webp,.heic,application/pdf,.txt,.md,.csv,.tsv,.json,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
          onChange={e => {
            if (e.target.files?.length) upload.mutate(Array.from(e.target.files))
            e.target.value = ''
          }} />
        <Button onClick={() => inputRef.current?.click()} loading={upload.isPending}>
          <Upload className="h-4 w-4" /> Upload files
        </Button>
      </div>

      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}

      {data && (
        <>
          <section>
            <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">Files</h2>
            {files.length === 0 ? (
              <p className="text-sm text-gray-500 dark:text-gray-400">No files yet.</p>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                {files.map(file => (
                  <FileCard key={file.id} file={file} agentName={agent.name}
                    onView={() => setPreview(file)}
                    onDelete={() => {
                      if (window.confirm(`Remove "${file.filename}" from ${agent.name}'s memory?`)) deleteFile.mutate(file.id)
                    }} />
                ))}
              </div>
            )}
          </section>

          <section>
            <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">Notes {agent.name} saved</h2>
            {notes.length === 0 ? (
              <p className="text-sm text-gray-500 dark:text-gray-400">
                {agent.name} hasn't saved any notes yet. They appear here as {agent.name} learns about you in conversation.
              </p>
            ) : (
              <div className="flex flex-col gap-3">
                {notes.map(note => (
                  <NoteCard key={note.id} note={note}
                    onDelete={() => {
                      if (window.confirm(`Make ${agent.name} forget "${note.title}"?`)) deleteNote.mutate(note.id)
                    }} />
                ))}
              </div>
            )}
          </section>
        </>
      )}

      <FilePreview agentId={agent.id} file={preview} onClose={() => setPreview(null)} />
    </div>
  )
}

function FileCard({ file, agentName, onView, onDelete }: {
  file: MemoryFile; agentName: string; onView: () => void; onDelete: () => void
}) {
  const isImage = file.contentType.startsWith('image/') && file.contentType !== 'image/heic'
  const thumb = useObjectUrl(isImage ? file : null)
  const Icon = iconFor(file.contentType)

  return (
    <Card padding="none" className="flex flex-col overflow-hidden">
      <button onClick={onView} className="relative grid h-36 shrink-0 place-items-center overflow-hidden bg-gray-50 dark:bg-gray-950" aria-label={`View ${file.filename}`}>
        {thumb
          ? <img src={thumb} alt={file.filename} className="absolute inset-0 h-full w-full object-cover" />
          : <Icon className="h-10 w-10 text-gray-400 dark:text-gray-600" />}
      </button>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <p className="truncate font-medium text-gray-900 dark:text-gray-100" title={file.filename}>{file.filename}</p>
        <p className="text-xs text-gray-500 dark:text-gray-400">
          {formatBytes(file.sizeBytes)} · added {new Date(file.createdAt).toLocaleDateString()}
        </p>
        <div>
          {file.readableChars > 0
            ? <Badge variant="success">{agentName} can read this</Badge>
            : <Badge variant="default">Stored, {agentName} can't read inside it yet</Badge>}
        </div>
        <div className="mt-auto flex gap-2 pt-2">
          <Button size="sm" variant="secondary" onClick={onView}><Eye className="h-3.5 w-3.5" /> View</Button>
          <Button size="sm" variant="ghost" onClick={onDelete} aria-label={`Delete ${file.filename}`}>
            <Trash2 className="h-3.5 w-3.5" /> Delete
          </Button>
        </div>
      </div>
    </Card>
  )
}

function NoteCard({ note, onDelete }: { note: MemoryNote; onDelete: () => void }) {
  const sensitive = note.classification && note.classification !== 'NONE'
  return (
    <Card className="flex items-start gap-3">
      <NotebookPen className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-medium text-gray-900 dark:text-gray-100">{note.title}</p>
          {sensitive && <Badge variant="warning">{note.classification!.toLowerCase()}</Badge>}
        </div>
        {note.content && <p className="mt-1 whitespace-pre-wrap text-sm text-gray-600 dark:text-gray-300">{note.content}</p>}
        {note.updatedAt && <p className="mt-2 text-xs text-gray-400">Updated {new Date(note.updatedAt).toLocaleString()}</p>}
      </div>
      <Button size="sm" variant="ghost" onClick={onDelete} aria-label={`Forget ${note.title}`}>
        <Trash2 className="h-3.5 w-3.5" />
      </Button>
    </Card>
  )
}

function FilePreview({ agentId, file, onClose }: { agentId: string; file: MemoryFile | null; onClose: () => void }) {
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
function useObjectUrl(file: MemoryFile | null, agentId?: string) {
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

function iconFor(contentType: string) {
  if (contentType.startsWith('image/')) return FileImage
  if (contentType.includes('sheet') || contentType.includes('excel') || contentType === 'text/csv') return FileSpreadsheet
  if (contentType === 'application/pdf' || contentType.startsWith('text/') || contentType.includes('word')) return FileText
  return FileIcon
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
