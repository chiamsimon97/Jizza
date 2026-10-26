import { chatApi, type ChatFileUpload } from '@/api/chat'

/**
 * Files and folders added to a chat message with the "+" menu. Each goes into the message in an
 * <attachment> block that the chat shows as a tag rather than as text. Text files go in as text.
 * PDFs and photos are uploaded when picked and the block refers to them (ref="<id>"), so the model
 * reads the file itself; the block also carries a PDF's text, for later turns and other readers.
 * Other binary files go in by name only.
 */

export type AttachmentKind = 'file' | 'image' | 'folder'

/** A file or folder waiting above the message box. */
export interface Attachment {
  id: string
  kind: AttachmentKind
  name: string
  /** A linked folder stays on every message until removed, and is read again each time. */
  linked?: boolean
  /** The attachment's text for the message, in at most {@code budget} characters. */
  read: (budget: number) => Promise<string>
  /** A PDF or photo's upload, started when it was picked; null when it failed. */
  uploaded?: Promise<ChatFileUpload | null>
}

/** What the model can read itself (AnthropicLlmClient): PDFs, and photos up to 5 MB. */
const MODEL_READS = new Set(['application/pdf', 'image/jpeg', 'image/png', 'image/gif', 'image/webp'])
const MAX_PHOTO_BYTES = 5 * 1024 * 1024

export function modelReads(file: { name: string; type: string; size: number }) {
  const type = file.type || (extension(file.name) === 'pdf' ? 'application/pdf' : '')
  return MODEL_READS.has(type) && (type === 'application/pdf' || file.size <= MAX_PHOTO_BYTES)
}

/** What the server takes in one message (ChatUseCase.MAX_MESSAGE_CHARS), less a margin. */
export const MESSAGE_BUDGET = 15_500
/** A folder lists at most this many files. */
const MAX_LISTED = 150
/** Larger files are not opened. */
const MAX_TEXT_BYTES = 256 * 1024
const SKIPPED_DIRS = new Set(['node_modules', '.git', 'dist', 'build', 'target', '.idea', '.next', '__pycache__'])
const TEXT_EXTENSIONS = new Set(('txt md markdown csv tsv json jsonl yaml yml xml html htm css scss js jsx ts tsx mjs cjs java kt py rb go rs c h cpp hpp cs php sh bash zsh sql ' +
  'toml ini cfg conf env properties log gradle swift vue svelte graphql proto tex rst adoc').split(' '))

let seq = 0
const nextId = () => `att-${++seq}`

function extension(name: string) {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

export function isText(file: { name: string; type: string }) {
  return file.type.startsWith('text/') || /json|xml|yaml|javascript|typescript|csv|sql/.test(file.type) || TEXT_EXTENSIONS.has(extension(file.name))
}

function cut(text: string, budget: number) {
  if (budget <= 0) return ''
  return text.length <= budget ? text : `${text.slice(0, Math.max(0, budget - 40)).trimEnd()}\n… (cut here, the file is longer)`
}

async function contentOf(file: File, budget: number) {
  if (!isText(file)) return `(${file.type || 'binary'} file, ${size(file.size)}; Jizza can't open this kind of file yet)`
  if (file.size > MAX_TEXT_BYTES) return `(${size(file.size)}, too large to include)`
  return cut(await textOf(file), budget)
}

/** Blob.text(), or FileReader where a browser (or jsdom) lacks it. */
function textOf(file: File): Promise<string> {
  if (typeof file.text === 'function') return file.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

function size(bytes: number) {
  return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${Math.round(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** A file picked with "Add files or photos". */
export function fileAttachment(file: File): Attachment {
  const kind = file.type.startsWith('image/') ? 'image' : 'file'
  if (!modelReads(file)) return { id: nextId(), kind, name: file.name, read: budget => contentOf(file, budget) }
  const uploaded = chatApi.uploadFile(file).catch(() => null)
  return {
    id: nextId(), kind, name: file.name, uploaded,
    read: async budget => {
      const upload = await uploaded
      if (!upload) return `(${size(file.size)}; the upload failed, so Jizza can't open it)`
      if (upload.text.trim()) return cut(upload.text.trim(), budget)
      return kind === 'image' ? '(photo; Jizza sees it)' : '(PDF with no text layer; Jizza reads its pages)'
    },
  }
}

interface FolderEntry { path: string; file: () => Promise<File> }

/** The listing of a folder, then as many of its text files as fit. */
async function folderText(entries: FolderEntry[], budget: number) {
  const listed = entries.slice(0, MAX_LISTED).map(e => e.path)
  let out = `Files (${entries.length}):\n${listed.join('\n')}${entries.length > MAX_LISTED ? `\n… and ${entries.length - MAX_LISTED} more` : ''}`
  for (const entry of entries) {
    const left = budget - out.length - entry.path.length - 20
    if (left < 200) break
    if (!isText({ name: entry.path, type: '' })) continue
    const file = await entry.file()
    if (file.size > MAX_TEXT_BYTES) continue
    out += `\n\n--- ${entry.path}\n${cut(await textOf(file), left)}`
  }
  return cut(out, budget)
}

const skipped = (path: string) => path.split('/').some(part => SKIPPED_DIRS.has(part))

/** A folder chosen with a browser folder picker (an input with webkitdirectory): a snapshot. */
export function folderFromFiles(files: File[], linked = false): Attachment | null {
  if (files.length === 0) return null
  const root = (files[0].webkitRelativePath || files[0].name).split('/')[0]
  const entries = files
    .map(file => ({ path: file.webkitRelativePath || file.name, file: async () => file }))
    .filter(e => !skipped(e.path))
    .sort((a, b) => a.path.localeCompare(b.path))
  return { id: nextId(), kind: 'folder', name: root, linked, read: budget => folderText(entries, budget) }
}

/** The File System Access API's directory handle; only some browsers (Chrome, Edge) have it. */
interface DirectoryHandle {
  name: string
  kind: 'directory'
  values: () => AsyncIterable<{ kind: 'file' | 'directory'; name: string; getFile?: () => Promise<File> } & Partial<DirectoryHandle>>
}

async function walk(dir: DirectoryHandle, prefix: string, out: FolderEntry[]) {
  for await (const entry of dir.values()) {
    const path = `${prefix}/${entry.name}`
    if (entry.kind === 'directory') {
      if (!SKIPPED_DIRS.has(entry.name) && out.length < 2000) await walk(entry as DirectoryHandle, path, out)
    } else if (entry.getFile) {
      out.push({ path, file: entry.getFile.bind(entry) })
    }
  }
}

/** A folder linked with "Link folder…": read again from disk on every message. */
export function linkedFolder(handle: DirectoryHandle): Attachment {
  return {
    id: nextId(), kind: 'folder', name: handle.name, linked: true,
    read: async budget => {
      const entries: FolderEntry[] = []
      await walk(handle, handle.name, entries)
      entries.sort((a, b) => a.path.localeCompare(b.path))
      return folderText(entries, budget)
    },
  }
}

/** Asks the browser for a folder it can read again later, or null where it can't (then use the folder input). */
export async function pickLinkedFolder(): Promise<Attachment | null | 'unsupported'> {
  const picker = (window as unknown as { showDirectoryPicker?: (o?: object) => Promise<DirectoryHandle> }).showDirectoryPicker
  if (!picker) return 'unsupported'
  try {
    return linkedFolder(await picker({ mode: 'read' }))
  } catch {
    return null // cancelled
  }
}

const escapeName = (name: string) => name.replace(/"/g, "'").replace(/[\r\n]/g, ' ')

/** The message with each attachment's block after what the user wrote, sharing what room is left. */
export async function withAttachments(text: string, attachments: Attachment[]): Promise<string> {
  let out = text
  for (let i = 0; i < attachments.length; i++) {
    const a = attachments[i]
    const upload = a.uploaded ? await a.uploaded : null
    const ref = upload ? ` ref="${upload.id}"` : ''
    const head = `<attachment kind="${a.kind}" name="${escapeName(a.name)}"${ref}>\n`
    const tail = '\n</attachment>'
    // Split what is left evenly between this attachment and the ones after it.
    const share = Math.floor((MESSAGE_BUDGET - out.length) / (attachments.length - i)) - head.length - tail.length - 2
    const body = share > 40 ? await a.read(share) : '(left out, the message is full)'
    out += `${out ? '\n\n' : ''}${head}${body}${tail}`
  }
  return out
}

export interface SentAttachment { kind: AttachmentKind; name: string }

const BLOCK = /<attachment kind="(file|image|folder)" name="([^"]*)"(?: ref="[^"]*")?>\n[\s\S]*?\n<\/attachment>/g

/** A sent message: what the user wrote, the attachments' tags, and the blocks as sent (kept on edit). */
export function splitAttachments(text: string): { text: string; attachments: SentAttachment[]; blocks: string } {
  const start = text.search(/(^|\n)<attachment kind="/)
  if (start < 0) return { text, attachments: [], blocks: '' }
  const blocks = text.slice(start).trim()
  const attachments = [...blocks.matchAll(BLOCK)].map(m => ({ kind: m[1] as AttachmentKind, name: m[2] }))
  return { text: text.slice(0, start).trim(), attachments, blocks }
}
