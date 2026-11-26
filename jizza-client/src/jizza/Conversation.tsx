import { FormEvent, Fragment, useCallback, useEffect, useRef, useState } from 'react'
import { takeChatDraft } from './chatDraft'
import { ArrowUpRight, Check, ChevronLeft, ChevronRight, Copy, CornerUpLeft, FileText, Folder, FolderPlus, FolderSymlink, Globe, Image, Laptop, Loader2, Mic, MicOff, Paperclip, Pencil, Plus, Reply, RotateCcw, ScrollText, Send, ShieldQuestion, Sparkles, Split, Square, SquareSlash, ThumbsDown, ThumbsUp, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { notificationsApi } from '@/api/notifications'
import { conversationsApi } from '@/api/conversations'
import { queryClient } from '@/lib/queryClient'
import { useQuery } from '@tanstack/react-query'
import { agentsApi } from '@/api/agents'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { Button } from '@/components/ui/Button'
import { loadJob, resumeConversation, useAssistantStore, type AssistantMessage, type Question } from './assistantStore'
import type { ReplyFeedback, ThreadCard as ThreadCardData, WebSource } from '@/types'
import { JizzaOrb } from './JizzaOrb'
import { WorkflowPlanCard } from './WorkflowPlanCard'
import { AgentProposalCard } from './AgentProposalCard'
import { useDictation } from './useSpeech'
import { ContextChip, useCurrentContext } from './ContextChip'
import { commandsFor, helpText, matchCommands, matchWorkflows, runQuery, runTemplate, type SlashCommand } from './commands'
import { workflowsApi } from '@/api/workflows'
import type { LibraryEntry } from '@/types'
import { useContextStore } from './contextStore'
import { Markdown } from './Markdown'
import { earlierMarker, took } from './replyMeta'
import { UserStepsCard, cardLinks } from './UserStepsCard'
import { StartRunCard } from './StartRunCard'
import { KaiFormCard } from './KaiFormCard'
import { RunInChat } from './RunInChat'
import { matchAgents, mentionOf, mentionQuery } from './mentions'
import { AnsweredByLabel, AskAnotherModel, CompareAnswers, DepthSwitch, ModelPicker, TeamStrip } from './ModelControls'
import { fileAttachment, folderFromFiles, pickLinkedFolder, splitAttachments, withAttachments, type Attachment, type AttachmentKind } from './attachments'

const SUGGESTIONS = [
  'Create a workflow that summarises my week every Friday',
  'Schedule a task to check my inbox every morning at 8',
  'Show me what my agents cost this month',
  'Switch to dark mode',
]

function greeting() {
  const h = new Date().getHours()
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'
}

interface ConversationProps {
  compact?: boolean
  autoFocus?: boolean
  /** Opens a reply's job in the Activity panel ("See how this was done"). */
  onOpenJob?: (jobId: string) => void
  /** Replaces the greeting shown before the first message, e.g. in the workflow planner. */
  intro?: { title: string; text: string; suggestions: string[] }
  placeholder?: string
  /** Shows what Jizza and the specialists said to each other under each reply, in place. */
  inlineDiscussion?: boolean
}

/** The conversation with Jizza, shared by the Chat page and the dock so it is one thread everywhere. */
export function Conversation({ compact = false, autoFocus = false, onOpenJob, intro, placeholder, inlineDiscussion = false }: ConversationProps) {
  const { messages, thinking, incognito, send, stop, guide, localReply, newConversation, edit, tryAgain, rate, conversationId, lastReplyId, setThreadsNext, choice, setChoice, keepVersion } = useAssistantStore()
  // The Chat page's box picks up a question another page handed over ("Ask Kai").
  const [input, setInput] = useState(() => (compact ? '' : takeChatDraft() ?? ''))
  // The message being replied to: quoted above what the user writes.
  const [replyTo, setReplyTo] = useState<{ who: string; text: string } | null>(null)
  const { ctx, token } = useCurrentContext()
  // The slash menu: open while the message is "/" and a command name, with one highlighted.
  const slash = /^\/(\S*)$/.exec(input)
  const [menuIndex, setMenuIndex] = useState(0)
  const [menuClosed, setMenuClosed] = useState(false)
  const commands = slash && !menuClosed ? matchCommands(ctx, slash[1]) : []
  useEffect(() => { setMenuIndex(0) }, [slash?.[1]])
  useEffect(() => { if (!slash) setMenuClosed(false) }, [!!slash]) // eslint-disable-line react-hooks/exhaustive-deps
  // "/run " and part of a name: the workflows it could be, to run without Jizza thinking about it.
  const runTyped = runQuery(input)
  const [runClosed, setRunClosed] = useState(false)
  const runnable = useQuery({ queryKey: ['workflows', 'runnable'], queryFn: workflowsApi.runnable, enabled: runTyped !== null, staleTime: 30_000 })
  const runChoices = runTyped !== null && !runClosed ? matchWorkflows(runnable.data ?? [], runTyped).slice(0, 8) : []
  const runMenuOpen = runTyped !== null && !runClosed && (runnable.isLoading || runChoices.length > 0)
  useEffect(() => { setMenuIndex(0) }, [runTyped])
  useEffect(() => { if (runTyped === null) setRunClosed(false) }, [runTyped === null]) // eslint-disable-line react-hooks/exhaustive-deps
  // "@" and part of a name: the specialists who could answer this message themselves.
  const mentionTyped = mentionQuery(input)
  const [mentionClosed, setMentionClosed] = useState(false)
  const { data: allAgents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })
  const mentionChoices = mentionTyped !== null && !mentionClosed ? matchAgents(allAgents ?? [], mentionTyped).slice(0, 8) : []
  useEffect(() => { setMenuIndex(0) }, [mentionTyped])
  useEffect(() => { if (mentionTyped === null) setMentionClosed(false) }, [mentionTyped === null]) // eslint-disable-line react-hooks/exhaustive-deps
  // "/model": the next message goes to the deeper model, then it is back to the usual one.
  const [nextDeep, setNextDeep] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  // Files and folders from the "+" menu, tagged above the message box until sent (a linked folder stays).
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [attaching, setAttaching] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const folderInputRef = useRef<HTMLInputElement>(null)
  const linkFolderNext = useRef(false)
  // PDFs and photos upload as soon as they are picked: their tag shows a spinner, or that it failed.
  const [uploads, setUploads] = useState<Record<string, 'uploading' | 'failed'>>({})
  const attach = (added: (Attachment | null)[]) => {
    const real = added.filter((a): a is Attachment => a !== null)
    setAttachments(list => [...list, ...real])
    for (const a of real) {
      if (!a.uploaded) continue
      setUploads(u => ({ ...u, [a.id]: 'uploading' }))
      void a.uploaded.then(done => setUploads(u => {
        const { [a.id]: _, ...rest } = u // eslint-disable-line @typescript-eslint/no-unused-vars
        return done ? rest : { ...rest, [a.id]: 'failed' }
      }))
    }
  }
  const detach = (id: string) => setAttachments(list => list.filter(a => a.id !== id))
  // Files dragged over the message box: it lights up, and dropping attaches them.
  const [dragging, setDragging] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  const onDictated = useCallback((text: string) => { void send(text) }, [send])
  const dictation = useDictation(onDictated)

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [messages, thinking])
  useEffect(() => { if (autoFocus) inputRef.current?.focus() }, [autoFocus])

  // Grow the message box with its content, up to the max-height in CSS.
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [input, dictation.interim])

  async function submit(e?: FormEvent) {
    e?.preventDefault()
    const text = input.trim()
    if ((!text && attachments.length === 0) || thinking || attaching) return
    // A command typed out in full runs as if picked from the menu.
    const typed = /^\/(\S+)$/.exec(text)
    const command = typed && commandsFor(ctx).find(c => c.name === typed[1].toLowerCase())
    if (command) return runCommand(command)
    const written = text || 'Please take a look at what I attached.'
    let message = replyTo ? `${quote(replyTo.text)}\n\n${written}` : written
    if (attachments.length > 0) {
      setAttaching(true)
      try {
        message = await withAttachments(message, attachments)
      } catch {
        // A file that can no longer be read (moved, or permission withdrawn) goes by name only.
        message = `${message}\n\n${attachments.map(a => `<attachment kind="${a.kind}" name="${a.name.replace(/"/g, "'")}">\n(could not be read)\n</attachment>`).join('\n\n')}`
      } finally {
        setAttaching(false)
      }
    }
    // "@Kai …" is answered by Kai; the name stays in the message so the chat reads the same later.
    const addressee = mentionOf(written, allAgents ?? [])
    const extras = { ...(nextDeep ? { depth: 'deep' as const } : {}), ...(addressee ? { mention: addressee.id } : {}) }
    setInput('')
    setReplyTo(null)
    setNextDeep(false)
    setAttachments(list => list.filter(a => a.linked))
    void send(message, extras)
  }

  /** A specialist picked after "@": their name goes in the box, ready for the message. */
  function pickAgent(a: { name: string }) {
    const text = `@${a.name} `
    setInput(text)
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (el) { el.focus(); el.setSelectionRange(text.length, text.length) }
    })
  }

  /** "/thread": threads on for this chat, and a sentence to finish saying what the thread is for. */
  async function startThread() {
    if (incognito) {
      localReply('/thread', 'Threads are kept with a saved chat, so they are not available in incognito.')
      return
    }
    if (!conversationId) setThreadsNext(true)
    else {
      try {
        await conversationsApi.update(conversationId, { threads: true })
        useAssistantStore.setState(s => ({ conversationsVersion: s.conversationsVersion + 1 }))
      } catch {
        localReply('/thread', 'I couldn\'t switch threads on for this chat. Try the Threads button at the top.')
        return
      }
    }
    insertText('Start a new thread about ')
  }

  function insertText(text: string) {
    setInput(text)
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length) }
    })
  }

  function openSlashMenu() {
    setInput('/')
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (el) { el.focus(); el.setSelectionRange(1, 1) }
    })
  }

  async function linkFolder() {
    const picked = await pickLinkedFolder()
    if (picked === 'unsupported') {
      // Without the File System Access API (Safari, Firefox) the folder input takes a snapshot instead.
      linkFolderNext.current = true
      folderInputRef.current?.click()
    } else attach([picked])
  }

  const reply = (msg: AssistantMessage) => {
    setReplyTo({ who: msg.role === 'user' ? 'yourself' : msg.lead?.name ?? 'Jizza', text: splitQuote(msg.text).body })
    inputRef.current?.focus()
  }

  /** A workflow picked after "/run": runs at once, or first asks for the inputs it takes. */
  function pickWorkflow(w: LibraryEntry) {
    const template = runTemplate(w)
    if (!template) {
      setInput('')
      if (!thinking) void send(`/run ${w.name}`)
      return
    }
    setInput(template)
    const caret = template.indexOf('\n') + template.slice(template.indexOf('\n') + 1).split('\n')[0].length + 1
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (el) { el.focus(); el.setSelectionRange(caret, caret) }
    })
  }

  function runCommand(command: SlashCommand) {
    setInput('')
    if (command.run === 'new') newConversation()
    else if (command.run === 'help') localReply(`/${command.name}`, helpText(ctx))
    else if (command.run === 'leave') { if (token !== undefined) useContextStore.getState().pop(token) }
    else if (command.run === 'thread') void startThread()
    else if (command.run === 'model') setNextDeep(d => !d)
    else if (command.prompt) { if (!thinking) void send(command.prompt) }
    else if (command.insert) insertText(command.insert)
  }

  function choose(q: Question, option: string) {
    const reply = q.guided?.[option]
    if (reply) guide(option, reply)
    else void send(option)
    if (reply) inputRef.current?.focus()
  }

  // Only the latest reply can be asked again: asking an older one would drop everything after it.
  const lastReply = messages.map(m => m.role).lastIndexOf('assistant')
  const earlier = useQuery({
    queryKey: ['conversation', conversationId, 'earlier', lastReplyId],
    queryFn: () => conversationsApi.earlier(conversationId!),
    enabled: !!conversationId && !incognito,
    staleTime: 60_000,
  })
  const earlierAt = earlier.data ? earlierMarker(messages, earlier.data.through) : -1

  const contextual = ctx.kind === 'builder' || ctx.kind === 'step'
  const shownIntro = intro ?? (contextual ? {
    title: ctx.kind === 'step' ? `Let's set up ${ctx.detail ? `"${ctx.detail}"` : 'this step'}` : `Working on ${ctx.detail ? `"${ctx.detail}"` : 'this workflow'}`,
    text: ctx.kind === 'step'
      ? 'Tell me what it should do, paste API documentation, or type / for shortcuts. I fill in the settings; you review and save.'
      : 'Ask me about this workflow, or type / for shortcuts. Open a step\'s menu and choose "Configure using Jizza" to have me fill it in.',
    suggestions: commandsFor(ctx).filter(c => c.prompt).slice(0, 4).map(c => c.prompt!),
  } : undefined)

  const pad = compact ? 'px-3' : 'px-4 sm:px-6'
  const width = compact ? '' : 'mx-auto max-w-3xl'

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className={`${width} flex min-h-full flex-col gap-4 ${pad} py-5`}>
          {messages.length === 0 && (
            <div className="flex flex-1 flex-col items-center justify-center gap-5 py-6 text-center animate-fade-in-up">
              <JizzaOrb size={compact ? 52 : 72} state={dictation.listening ? 'listening' : 'idle'} />
              <div>
                <h3 className={`${compact ? 'text-lg' : 'text-2xl'} font-semibold tracking-tight text-gray-900 dark:text-gray-100`}>
                  {shownIntro?.title ?? (incognito ? 'Nothing here is saved' : `${greeting()}. What shall we do?`)}
                </h3>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  {shownIntro?.text ?? (incognito
                    ? 'Incognito chats are not stored in your history.'
                    : 'Tell me what you need. I can open pages, draft workflows and tasks, and only stop when I need you.')}
                </p>
              </div>
              <div className={`stagger grid w-full gap-2 ${compact ? '' : 'max-w-xl sm:grid-cols-2'}`}>
                {(shownIntro?.suggestions ?? SUGGESTIONS).map(text => (
                  <button
                    key={text}
                    type="button"
                    onClick={() => void send(text)}
                    className="group flex items-center justify-between gap-2 rounded-xl border border-gray-200 bg-white px-4 py-3 text-left text-sm text-gray-700 shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300 dark:hover:border-brand-700"
                  >
                    <span>{text}</span>
                    <ArrowUpRight className="h-4 w-4 shrink-0 text-gray-400 transition-transform group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:text-brand-500" />
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map((msg, i) => (
            <Fragment key={msg.id}>
              {i === earlierAt && earlier.data && <EarlierNote summary={earlier.data.summary} />}
              <MessageBubble msg={msg} compact={compact} onChoose={option => choose(msg.question!, option)} onOpenJob={onOpenJob}
                inlineDiscussion={inlineDiscussion} onRevise={() => inputRef.current?.focus()}
                onSendMany={text => void send(text)}
                onTryAgain={!thinking && !msg.streaming && (msg.error || i === lastReply) && messages.slice(0, i).some(m => m.role === 'user')
                  ? (deeper, model) => void tryAgain(msg.id, deeper, model) : undefined}
                onKeep={!thinking && !msg.streaming && msg.versions?.length ? index => keepVersion(msg.id, index) : undefined}
                onReply={() => reply(msg)} busy={thinking} onEdit={msg.role === 'user' && !thinking ? text => edit(msg.id, text) : undefined}
                onRate={msg.role === 'assistant' && conversationId && !incognito && msg.jobId ? feedback => rate(msg.id, feedback) : undefined}
                sentToThread={msg.role === 'user' && messages.slice(i + 1).some(m => m.role === 'assistant' && m.jobId === msg.jobId && !!m.jobId && !!m.thread)} />
            </Fragment>
          ))}

          {/* Between sending and the job starting there is no reply bubble yet to show progress in. */}
          {thinking && messages[messages.length - 1]?.role === 'user' && (
            <div className="flex items-center gap-2.5 animate-fade-in">
              <JizzaOrb size={28} state="thinking" tracking={false} />
              <span className="text-sm text-gray-500 dark:text-gray-400">Working on it…</span>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      <form onSubmit={submit} className={`${pad} pb-3 pt-2`}>
        <div className={`${width} relative`}>
          {runMenuOpen && (
            <ul role="listbox" aria-label="Workflows"
              className="absolute inset-x-0 bottom-full z-10 mb-2 max-h-72 overflow-y-auto rounded-xl border border-gray-200 bg-white p-1 shadow-lg animate-fade-in dark:border-gray-700 dark:bg-gray-900">
              <li className="px-2.5 pb-1 pt-1.5 text-[11px] font-medium uppercase tracking-wide text-gray-400">Run a workflow now · no AI, no tokens</li>
              {runnable.isLoading && <li className="px-2.5 py-1.5 text-sm text-gray-500">Loading your workflows…</li>}
              {runChoices.map((w, i) => (
                <li key={w.workflowId} role="option" aria-selected={i === menuIndex}>
                  <button type="button" onMouseEnter={() => setMenuIndex(i)} onMouseDown={e => e.preventDefault()} onClick={() => pickWorkflow(w)}
                    className={`flex w-full items-baseline gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm ${i === menuIndex ? 'bg-brand-50 dark:bg-brand-500/15' : ''}`}>
                    <span className="truncate font-medium text-gray-800 dark:text-gray-100">{w.name}</span>
                    {(w.profile?.inputs?.length ?? 0) > 0 && (
                      <span className="shrink-0 text-xs text-gray-400">asks for {w.profile!.inputs!.map(x => x.name).join(', ')}</span>
                    )}
                    {w.description && <span className="truncate text-xs text-gray-500 dark:text-gray-400">{w.description}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {mentionChoices.length > 0 && (
            <ul role="listbox" aria-label="Specialists"
              className="absolute inset-x-0 bottom-full z-10 mb-2 max-h-72 overflow-y-auto rounded-xl border border-gray-200 bg-white p-1 shadow-lg animate-fade-in dark:border-gray-700 dark:bg-gray-900">
              <li className="px-2.5 pb-1 pt-1.5 text-[11px] font-medium uppercase tracking-wide text-gray-400">Who should answer</li>
              {mentionChoices.map((a, i) => (
                <li key={a.id} role="option" aria-selected={i === menuIndex}>
                  <button type="button" onMouseEnter={() => setMenuIndex(i)} onMouseDown={e => e.preventDefault()} onClick={() => pickAgent(a)}
                    className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm ${i === menuIndex ? 'bg-brand-50 dark:bg-brand-500/15' : ''}`}>
                    <AgentLogo agentKey={a.key} size={18} />
                    <span className="truncate font-medium text-gray-800 dark:text-gray-100">{a.name}</span>
                    {a.domain && <span className="truncate text-xs text-gray-500 dark:text-gray-400">{a.domain}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {commands.length > 0 && (
            <ul role="listbox" aria-label="Shortcuts"
              className="absolute inset-x-0 bottom-full z-10 mb-2 max-h-72 overflow-y-auto rounded-xl border border-gray-200 bg-white p-1 shadow-lg animate-fade-in dark:border-gray-700 dark:bg-gray-900">
              <li className="px-2.5 pb-1 pt-1.5 text-[11px] font-medium uppercase tracking-wide text-gray-400">Shortcuts for the {ctx.label}</li>
              {commands.map((c, i) => (
                <li key={c.name} role="option" aria-selected={i === menuIndex}>
                  <button type="button" onMouseEnter={() => setMenuIndex(i)} onMouseDown={e => e.preventDefault()} onClick={() => runCommand(c)}
                    className={`flex w-full items-baseline gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm ${i === menuIndex ? 'bg-brand-50 dark:bg-brand-500/15' : ''}`}>
                    <span className="font-mono text-xs font-semibold text-brand-700 dark:text-brand-300">/{c.name}</span>
                    <span className="truncate text-gray-600 dark:text-gray-300">{c.description}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <ContextChip className="mb-1.5 px-1" />
          {nextDeep && (
            <div className="mb-1.5 px-1">
              <span className="inline-flex items-center gap-1.5 rounded-lg border border-brand-200 bg-brand-50 py-1 pl-2 pr-1 text-xs text-brand-700 dark:border-brand-500/30 dark:bg-brand-500/10 dark:text-brand-300">
                <Sparkles className="h-3.5 w-3.5" /> Deeper model for the next message
                <button type="button" aria-label="Use the usual model" onClick={() => setNextDeep(false)}
                  className="grid h-4 w-4 place-items-center rounded text-brand-400 hover:bg-brand-100 hover:text-brand-700 dark:hover:bg-brand-500/20">
                  <X className="h-3 w-3" />
                </button>
              </span>
            </div>
          )}
          {attachments.length > 0 && (
            <ul aria-label="Attached" className="mb-1.5 flex flex-wrap gap-1.5 px-1">
              {attachments.map(a => (
                <li key={a.id} className="inline-flex max-w-[16rem] items-center gap-1.5 rounded-lg border border-gray-200 bg-gray-50 py-1 pl-2 pr-1 text-xs text-gray-700 dark:border-gray-700 dark:bg-gray-900/60 dark:text-gray-200"
                  title={uploads[a.id] === 'failed' ? `${a.name} could not be uploaded; Jizza will only see its name`
                    : a.linked ? `${a.name} (linked: read again with every message)` : a.name}>
                  {uploads[a.id] === 'uploading'
                    ? <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-gray-400" aria-label="Uploading" />
                    : <AttachmentIcon kind={a.kind} linked={a.linked} className={`h-3.5 w-3.5 shrink-0 ${uploads[a.id] === 'failed' ? 'text-red-500' : 'text-brand-500'}`} />}
                  <span className="truncate">{a.name}</span>
                  {a.linked && <span className="shrink-0 text-[10px] uppercase tracking-wide text-gray-400">linked</span>}
                  <button type="button" aria-label={`Remove ${a.name}`} onClick={() => detach(a.id)}
                    className="grid h-4 w-4 shrink-0 place-items-center rounded text-gray-400 hover:bg-gray-200 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200">
                    <X className="h-3 w-3" />
                  </button>
                </li>
              ))}
            </ul>
          )}
          {replyTo && (
            <div className="mb-1.5 flex items-start gap-2 rounded-xl border border-gray-200 bg-gray-50 px-3 py-2 text-xs dark:border-gray-800 dark:bg-gray-900/60">
              <Reply className="mt-0.5 h-3.5 w-3.5 shrink-0 text-brand-500" />
              <div className="min-w-0 flex-1">
                <p className="font-medium text-gray-700 dark:text-gray-200">Replying to {replyTo.who}</p>
                <p className="truncate text-gray-500 dark:text-gray-400">{replyTo.text}</p>
              </div>
              <button type="button" aria-label="Cancel reply" onClick={() => setReplyTo(null)}
                className="grid h-5 w-5 shrink-0 place-items-center rounded text-gray-400 hover:bg-gray-200 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200">
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>
        <div
          onDragOver={e => { if (hasFiles(e.dataTransfer)) { e.preventDefault(); setDragging(true) } }}
          onDragLeave={() => setDragging(false)}
          onDrop={e => {
            if (!hasFiles(e.dataTransfer)) return
            e.preventDefault()
            setDragging(false)
            attach(Array.from(e.dataTransfer.files).map(fileAttachment))
          }}
          className={`${width} flex items-end gap-2 rounded-2xl border bg-white p-2 shadow-sm transition-all focus-within:border-brand-500 focus-within:ring-4 focus-within:ring-brand-500/15 dark:bg-gray-900 ${dragging ? 'border-brand-500 ring-4 ring-brand-500/15' : 'border-gray-300 dark:border-gray-700'}`}>
          <AttachMenu
            onFiles={() => fileInputRef.current?.click()}
            onSlash={openSlashMenu}
            onAnyFolder={() => { linkFolderNext.current = false; folderInputRef.current?.click() }}
            onLinkFolder={() => void linkFolder()}
          />
          <input ref={fileInputRef} type="file" multiple hidden data-testid="attach-files"
            onChange={e => { attach(Array.from(e.target.files ?? []).map(fileAttachment)); e.target.value = '' }} />
          <input ref={folderInputRef} type="file" hidden data-testid="attach-folder" {...{ webkitdirectory: '', directory: '' }}
            onChange={e => { attach([folderFromFiles(Array.from(e.target.files ?? []), linkFolderNext.current)]); e.target.value = '' }} />
          {dictation.supported && (
            <button
              type="button"
              onClick={dictation.listening ? dictation.stop : dictation.start}
              aria-label={dictation.listening ? 'Stop listening' : 'Speak to Jizza'}
              title={dictation.listening ? 'Stop listening' : 'Speak to Jizza'}
              className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl transition-all active:scale-90 ${
                dictation.listening
                  ? 'bg-red-500 text-white shadow-lg shadow-red-500/30'
                  : 'text-gray-500 hover:bg-gray-100 hover:text-brand-600 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-brand-300'
              }`}
            >
              {dictation.listening ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            </button>
          )}
          <textarea
            ref={inputRef}
            rows={1}
            value={dictation.listening ? dictation.interim : input}
            onChange={e => setInput(e.target.value)}
            onPaste={e => {
              // Pasted files (a screenshot, a copied document) attach; pasted text stays text.
              const files = Array.from(e.clipboardData?.files ?? [])
              if (files.length === 0) return
              e.preventDefault()
              attach(files.map(fileAttachment))
            }}
            placeholder={dictation.listening ? 'Listening…' : incognito ? 'Incognito — not saved…' : placeholder ?? (contextual ? 'Ask about this, or type / for shortcuts…' : 'Ask Jizza to do something… (/ for shortcuts)')}
            readOnly={dictation.listening}
            className="max-h-40 flex-1 resize-none bg-transparent px-2 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none dark:text-gray-100"
            onKeyDown={e => {
              if (mentionChoices.length > 0) {
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                  e.preventDefault()
                  setMenuIndex(i => (i + (e.key === 'ArrowDown' ? 1 : mentionChoices.length - 1)) % mentionChoices.length)
                  return
                }
                if ((e.key === 'Enter' && !e.shiftKey) || e.key === 'Tab') { e.preventDefault(); pickAgent(mentionChoices[menuIndex] ?? mentionChoices[0]); return }
                if (e.key === 'Escape') { e.preventDefault(); setMentionClosed(true); return }
              }
              if (runChoices.length > 0) {
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                  e.preventDefault()
                  setMenuIndex(i => (i + (e.key === 'ArrowDown' ? 1 : runChoices.length - 1)) % runChoices.length)
                  return
                }
                if ((e.key === 'Enter' && !e.shiftKey) || e.key === 'Tab') { e.preventDefault(); pickWorkflow(runChoices[menuIndex] ?? runChoices[0]); return }
                if (e.key === 'Escape') { e.preventDefault(); setRunClosed(true); return }
              }
              if (commands.length > 0) {
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                  e.preventDefault()
                  setMenuIndex(i => (i + (e.key === 'ArrowDown' ? 1 : commands.length - 1)) % commands.length)
                  return
                }
                if ((e.key === 'Enter' && !e.shiftKey) || e.key === 'Tab') { e.preventDefault(); runCommand(commands[menuIndex] ?? commands[0]); return }
                if (e.key === 'Escape') { e.preventDefault(); setMenuClosed(true); return }
              }
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void submit() }
            }}
          />
          {thinking ? (
            <Button type="button" variant="secondary" onClick={() => void stop()} className="h-9 w-9 shrink-0 rounded-xl !p-0" aria-label="Stop" title="Stop">
              <Square className="h-3.5 w-3.5 fill-current" />
            </Button>
          ) : (
            <Button type="submit" disabled={(!input.trim() && attachments.length === 0) || attaching} className="h-9 w-9 shrink-0 rounded-xl !p-0" aria-label="Send">
              <Send className="h-4 w-4" />
            </Button>
          )}
        </div>
        <div className={`${width} mt-1.5 flex flex-wrap items-center gap-2 px-1`}>
          <ModelPicker value={choice.model ?? undefined} onChange={model => setChoice({ ...choice, model })}
            attached={{
              image: attachments.some(a => a.kind === 'image'),
              pdf: attachments.some(a => a.kind === 'file' && /\.pdf$/i.test(a.name)),
            }} />
          <DepthSwitch value={choice.depth ?? undefined} onChange={depth => setChoice({ ...choice, depth })} />
        </div>
        {!compact && (
          <p className={`${width} mt-1.5 text-center text-[11px] text-gray-400 dark:text-gray-500`}>
            Enter to send · Shift+Enter for a new line · / for shortcuts · @ asks a specialist · /run starts a workflow · + to add files or a folder · Ctrl+K opens Jizza anywhere
          </p>
        )}
      </form>
    </div>
  )
}

function MessageBubble({ msg, compact, onChoose, onOpenJob, inlineDiscussion, onRevise, onSendMany, onTryAgain, onKeep, onReply, onEdit, onRate, sentToThread, busy }: {
  msg: AssistantMessage; compact: boolean; busy?: boolean; onChoose: (text: string) => void; onOpenJob?: (jobId: string) => void
  inlineDiscussion?: boolean; onRevise?: () => void
  /** Sends several ticked answers together. */
  onSendMany: (text: string) => void
  /**
   * Asks again for this reply, keeping this answer as an earlier version; {@code deeper} uses the
   * deeper model, {@code model} another model ("Ask another model").
   */
  onTryAgain?: (deeper: boolean, model?: string) => void
  /** Keeps an earlier answer in place of this one; false when it did not save. */
  onKeep?: (index: number) => Promise<boolean>
  /** Quotes this message in the next one. */
  onReply: () => void
  /** The user's own message: replaces it, and everything after it, with the edited text. */
  onEdit?: (text: string) => Promise<void>
  /** Jizza moved this message of the user's into a thread. */
  sentToThread?: boolean
  /** A saved reply: thumbs up or down, with an optional reason. */
  onRate?: (feedback: ReplyFeedback | null) => Promise<boolean>
}) {
  const [editing, setEditing] = useState(false)
  // Which answer is shown when "Try again" left earlier ones: the latest unless the user paged back.
  const versionCount = (msg.versions?.length ?? 0) + 1
  const [shown, setShown] = useState<number | null>(null)
  const current = shown === null || shown >= versionCount - 1
  const shownText = current ? msg.text : msg.versions![shown].text
  const shownDeep = current ? msg.deep : msg.versions![shown].deep
  const shownModel = current ? msg.model : msg.versions![shown].model
  const [comparing, setComparing] = useState(false)
  if (msg.role === 'event') {
    return (
      <div className="flex items-center gap-2 py-1 animate-fade-in" role="note">
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
        <span className="max-w-[80%] text-center text-xs text-gray-500 dark:text-gray-400">{msg.text}</span>
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
      </div>
    )
  }
  if (msg.role === 'user') {
    const { quote: quoted, body: written } = splitQuote(msg.text)
    const { text: body, attachments: attached, blocks } = splitAttachments(written)
    const width = compact ? 'max-w-[85%]' : 'max-w-[85%] sm:max-w-[75%]'
    if (editing && onEdit) {
      return (
        <EditBox initial={body} className={width} onCancel={() => setEditing(false)}
          onSave={async text => {
            const edited = blocks ? `${text}\n\n${blocks}` : text
            await onEdit(quoted ? `${quote(quoted)}\n\n${edited}` : edited)
            setEditing(false)
          }} />
      )
    }
    return (
      <div className="group/msg flex flex-col items-end gap-1 animate-fade-in-up">
        <div className={`${width} rounded-2xl rounded-br-md bg-brand-500 px-4 py-2.5 text-sm leading-relaxed text-white shadow-sm whitespace-pre-wrap`}>
          {quoted && <span className="mb-1.5 block border-l-2 border-white/60 pl-2 text-xs text-white/80 line-clamp-3">{quoted}</span>}
          {attached.length > 0 && (
            <span className="mb-1.5 flex flex-wrap gap-1" aria-label="Attached">
              {attached.map((a, i) => (
                <span key={i} className="inline-flex max-w-full items-center gap-1 rounded-md bg-white/20 px-1.5 py-0.5 text-xs">
                  <AttachmentIcon kind={a.kind} className="h-3 w-3 shrink-0" />
                  <span className="truncate">{a.name}</span>
                </span>
              ))}
            </span>
          )}
          {body}
        </div>
        {sentToThread && (
          <span className="inline-flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
            <Split className="h-3 w-3" /> Sent to a thread
          </span>
        )}
        <MessageActions text={msg.text} onReply={onReply} onEdit={onEdit ? () => setEditing(true) : undefined} />
      </div>
    )
  }
  return (
    <div className="group/msg flex items-start gap-2.5 animate-fade-in-up">
      {msg.lead
        ? <span className="mt-0.5 shrink-0" title={msg.lead.name}><AgentLogo agentKey={msg.lead.key} size={28} /></span>
        : <JizzaOrb size={28} className="mt-0.5" tracking={false} />}
      <div className={`flex min-w-0 flex-col gap-2 ${compact ? 'max-w-[88%]' : 'max-w-[85%] sm:max-w-[75%]'}`}>
        {msg.lead && <span className="-mb-1 text-xs font-medium text-gray-500 dark:text-gray-400">{msg.lead.name}</span>}
        {msg.helpers && msg.helpers.length > 0 && <Helpers helpers={msg.helpers} />}
        {msg.steps && msg.steps.length > 0 && (
          <ul className="flex flex-wrap gap-1.5">
            {msg.steps.map((step, i) => (
              <li key={i} className="inline-flex items-center gap-1 rounded-full bg-brand-50 px-2.5 py-1 text-xs font-medium text-brand-700 ring-1 ring-inset ring-brand-500/20 animate-fade-in dark:bg-brand-500/10 dark:text-brand-300" style={{ animationDelay: `${i * 120}ms` }}>
                <Check className="h-3 w-3" />
                {step.label}
                {step.href && (
                  <Link to={step.href} className="ml-1 inline-flex items-center gap-0.5 underline decoration-brand-500/40 underline-offset-2 hover:decoration-current">
                    {step.hrefLabel ?? 'Open'} <ArrowUpRight className="h-3 w-3" aria-hidden />
                  </Link>
                )}
              </li>
            ))}
          </ul>
        )}
        <div className={`group/reply relative rounded-2xl rounded-tl-md border px-4 py-2.5 text-sm leading-relaxed shadow-sm ${msg.error ? 'whitespace-pre-wrap' : ''} ${
          msg.error
            ? 'border-red-200 bg-red-50 text-red-800 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-200'
            : 'border-gray-200 bg-white text-gray-900 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-100'
        }`}>
          {msg.error ? msg.text : <Markdown text={shownText} hideLinks={current ? cardLinks(msg.userSteps) : undefined} />}
          {msg.streaming && !msg.text && <TypingDots />}
          {msg.streaming && msg.text && <span className="ml-0.5 inline-block h-3.5 w-1.5 translate-y-0.5 animate-pulse rounded-sm bg-brand-500/70" />}
          {msg.stopped && <span className="mt-1 block text-xs text-gray-400 dark:text-gray-500">Stopped</span>}
        </div>
        {current && !msg.streaming && msg.sources && msg.sources.length > 0 && <Sources sources={msg.sources} />}
        {(versionCount > 1 || (!msg.streaming && msg.text && !msg.error) || onTryAgain) && (
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            {versionCount > 1 && !msg.streaming && (
              <VersionPager index={current ? versionCount - 1 : shown!} count={versionCount} deep={shownDeep && !shownModel}
                onPick={i => setShown(i >= versionCount - 1 ? null : i)} />
            )}
            {!msg.streaming && !msg.error && <AnsweredByLabel model={shownModel} />}
            {versionCount > 1 && !msg.streaming && onKeep && (
              <button type="button" onClick={() => setComparing(true)}
                className="text-xs font-medium text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300">
                Compare
              </button>
            )}
            {!msg.streaming && msg.text && !msg.error && <MessageActions text={shownText} onReply={onReply} align="start" />}
            {!msg.streaming && msg.text && !msg.error && onRate && <FeedbackButtons feedback={msg.feedback} onRate={onRate} />}
            {onTryAgain && msg.error && (
              <button type="button" onClick={() => onTryAgain(false)}
                className="inline-flex w-fit items-center gap-1 text-xs font-medium text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300">
                <RotateCcw className="h-3 w-3" /> Try again
              </button>
            )}
            {onTryAgain && !msg.error && <TryAgainButtons onTryAgain={onTryAgain} />}
            {onTryAgain && !msg.error && <AskAnotherModel current={msg.model?.ref} onPick={ref => onTryAgain(false, ref)} />}
          </div>
        )}
        {current && !msg.streaming && msg.team && msg.team.length > 0 && <TeamStrip team={msg.team} />}
        {onKeep && msg.versions && (
          <CompareAnswers open={comparing} onClose={() => setComparing(false)} versions={msg.versions}
            current={{ text: msg.text, model: msg.model, deep: msg.deep }}
            onKeep={async i => { const ok = await onKeep(i); if (ok) setShown(null); return ok }} />
        )}
        {msg.plan && <WorkflowPlanCard plan={msg.plan} onRevise={onRevise} />}
        {msg.proposal && <AgentProposalCard proposal={msg.proposal} />}
        {msg.thread && <ThreadHandoffCard thread={msg.thread} />}
        {msg.handback && <HandbackCard conversationId={msg.handback.conversationId} />}
        {msg.jobId && !msg.streaming && <Approvals jobId={msg.jobId} />}
        {msg.jobId && inlineDiscussion && !msg.streaming && <Discussion jobId={msg.jobId} />}
        {msg.jobId && onOpenJob && !inlineDiscussion && !msg.streaming && (
          <WhatIDid jobId={msg.jobId} onOpen={() => onOpenJob(msg.jobId!)} />
        )}
        {msg.question && <QuestionCard question={msg.question} onChoose={onChoose} onSendMany={onSendMany} />}
        {!msg.streaming && msg.startRun && <StartRunCard card={msg.startRun} />}
        {!msg.streaming && msg.kaiForms?.map(card => <KaiFormCard key={card.id} card={card} />)}
        {!msg.streaming && msg.runs?.map(r => <RunInChat key={r.runId} runId={r.runId} name={r.name} />)}
        {!msg.streaming && msg.userSteps?.map((card, i) => (
          <UserStepsCard key={i} card={card} onSend={onSendMany} disabled={busy}
            storageKey={msg.jobId ? `jizza.steps.${msg.jobId}.${i}` : undefined} />
        ))}
      </div>
    </div>
  )
}

/**
 * Calls held for the user's approval while Jizza answered, each with Approve and Deny. Answering
 * here is the same as answering on the Notifications page; either way Jizza carries on in this
 * conversation and the card turns into the line saying what was decided.
 */
function Approvals({ jobId }: { jobId: string }) {
  const job = useAssistantStore(s => s.jobs[jobId])
  const answeredIds = useAssistantStore(s => s.messages.filter(m => m.role === 'event' && m.notificationId).map(m => m.notificationId!).join(','))
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (job && job.status === 'awaiting_user' && !job.loaded) void loadJob(jobId)
  }, [job, jobId])

  if (!job) return null
  const answered = new Set(answeredIds.split(',').filter(Boolean))
  const pending = job.events.filter(e => e.type === 'approval.required' && !answered.has(e.payload.notificationId))
  if (pending.length === 0) return null

  const answer = async (id: string, kind: 'APPROVE' | 'DENY') => {
    setBusy(id)
    setError(null)
    try {
      await notificationsApi.answer(id, kind)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That did not go through.')
    } finally {
      setBusy(null)
      void queryClient.invalidateQueries({ queryKey: ['notifications'] })
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {pending.map(e => (
        <div key={e.seq} className="rounded-2xl border border-amber-300 bg-amber-50/70 p-3 animate-fade-in-up dark:border-amber-500/40 dark:bg-amber-500/10">
          <p className="flex items-start gap-2 text-sm text-gray-900 dark:text-gray-100">
            <ShieldQuestion className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-300" />
            <span><span className="font-medium">{e.agentName ?? 'An agent'}</span> needs your approval to run <span className="font-medium">{e.payload.label ?? e.payload.tool}</span>.</span>
          </p>
          {e.payload.reason && <p className="ml-6 mt-0.5 text-xs text-gray-600 dark:text-gray-400">{e.payload.reason}</p>}
          <div className="ml-6 mt-2 flex gap-2">
            <Button size="sm" loading={busy === e.payload.notificationId} disabled={busy !== null} onClick={() => void answer(e.payload.notificationId, 'APPROVE')}>
              <Check className="h-3.5 w-3.5" /> Approve
            </Button>
            <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => void answer(e.payload.notificationId, 'DENY')}>
              <X className="h-3.5 w-3.5" /> Deny
            </Button>
          </div>
          {error && <p className="ml-6 mt-1.5 text-xs text-red-600 dark:text-red-400">{error}</p>}
        </div>
      ))}
    </div>
  )
}

/**
 * What Jizza asked the specialists and what they answered for this reply, folded by default. In
 * the workflow planner this is the discussion the plan came out of.
 */
function Discussion({ jobId }: { jobId: string }) {
  const job = useAssistantStore(s => s.jobs[jobId])
  const [open, setOpen] = useState(false)
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })

  useEffect(() => { if (open) void loadJob(jobId) }, [open, jobId])

  const lines = job?.events.filter(e => e.type === 'agent.message') ?? []
  if (job?.loaded && lines.length === 0) return null
  return (
    <div className="flex flex-col gap-1.5">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="inline-flex w-fit items-center gap-0.5 text-xs font-medium text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300"
      >
        {open ? 'Hide the discussion with the agents' : 'Show the discussion with the agents'}
        <ChevronRight className={`h-3 w-3 transition-transform ${open ? 'rotate-90' : ''}`} />
      </button>
      {open && (
        <ul className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-gray-50 p-2.5 animate-fade-in dark:border-gray-800 dark:bg-gray-950">
          {lines.length === 0 && <li className="text-xs text-gray-500 dark:text-gray-400">Loading…</li>}
          {lines.map(e => (
            <li key={e.seq} className="flex gap-2">
              <span className="shrink-0"><AgentLogo agentKey={agents?.find(a => a.id === e.agentId)?.key ?? 'other'} size={18} /></span>
              <div className="min-w-0">
                <p className="text-xs font-medium text-gray-700 dark:text-gray-200">
                  {e.agentName ?? 'Agent'} to {e.payload.toAgentName ?? 'Agent'}{e.payload.kind === 'failed' ? ' · could not finish' : ''}
                </p>
                <p className="whitespace-pre-wrap text-xs text-gray-600 dark:text-gray-400">{e.payload.text}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function TypingDots() {
  return (
    <span className="inline-flex items-center gap-1 py-1" aria-label="Jizza is working">
      {[0, 1, 2].map(i => (
        <span key={i} className="h-1.5 w-1.5 animate-bounce rounded-full bg-gray-400 dark:bg-gray-500" style={{ animationDelay: `${i * 150}ms` }} />
      ))}
    </span>
  )
}

/**
 * Where Jizza moved the request: the thread, who leads it and how far it has got, one tap from
 * opening it. Like a thread under a message, it stays with the chat it came from.
 */
function ThreadHandoffCard({ thread }: { thread: ThreadCardData }) {
  const current = useAssistantStore(s => s.conversationId)
  const version = useAssistantStore(s => s.conversationsVersion)
  const here = current === thread.conversationId
  const { data } = useQuery({
    queryKey: ['conversation-threads', current, version],
    queryFn: () => conversationsApi.threads(current!),
    enabled: !!current && !here,
    retry: false,
    staleTime: 5_000,
    // A lead still answering moves the thread on by itself: look again until it settles.
    refetchInterval: q => ((q.state.data as { id: string; stage?: string }[] | undefined)
      ?.some(t => t.id === thread.conversationId && t.stage === 'working') ? 3_000 : false),
  })
  const row = data?.find(t => t.id === thread.conversationId)
  const replies = row ? Math.floor(row.messageCount / 2) : 0
  const status = row?.stage === 'working' ? 'Working…' : row?.stage === 'waiting' ? 'Waiting on you'
    : row?.stage === 'resolved' ? 'Resolved' : replies > 0 ? `${replies} ${replies === 1 ? 'reply' : 'replies'}` : 'Thread'
  return (
    <button type="button" disabled={here} onClick={() => resumeConversation(thread.conversationId)}
      aria-label={`Open thread ${thread.title}`} title={thread.topic ?? thread.title}
      className="group/thread flex w-fit max-w-full items-center gap-2 rounded-xl border border-gray-200 bg-white py-1.5 pl-1.5 pr-2.5 text-left text-xs shadow-sm transition-colors animate-fade-in-up hover:border-brand-300 disabled:cursor-default dark:border-gray-800 dark:bg-gray-900 dark:hover:border-brand-700">
      <AgentLogo agentKey={thread.leadKey} size={22} />
      <span className="shrink-0 font-semibold text-brand-700 dark:text-brand-300">
        {thread.resumed ? `Continued in ${thread.leadName}'s thread` : `New thread with ${thread.leadName}`}
      </span>
      <span className="min-w-0 truncate font-medium text-gray-900 dark:text-gray-100">{thread.title}</span>
      <span className="shrink-0 text-gray-500 dark:text-gray-400">· {status}</span>
      {!here && <ChevronRight className="h-3.5 w-3.5 shrink-0 text-gray-400 transition-transform group-hover/thread:translate-x-0.5" />}
    </button>
  )
}

/** The lead passed the message back: Jizza picks it up in the main chat. */
function HandbackCard({ conversationId }: { conversationId: string }) {
  return (
    <button type="button" onClick={() => resumeConversation(conversationId)}
      className="inline-flex w-fit items-center gap-1.5 rounded-full border border-gray-200 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 shadow-sm transition-colors hover:border-brand-300 hover:text-brand-700 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300 dark:hover:text-brand-300">
      <CornerUpLeft className="h-3.5 w-3.5" />
      Follow it in the main chat
    </button>
  )
}

/** "Vita and Kai helped": the specialists behind a reply, each shown by its logo. */
function Helpers({ helpers }: { helpers: { agentId: string; agentName: string }[] }) {
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })
  const names = helpers.map(h => h.agentName)
  const label = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
  return (
    <div className="flex items-center gap-2 animate-fade-in">
      <div className="flex -space-x-1.5">
        {helpers.map(h => (
          <span key={h.agentId} title={h.agentName} className="rounded-lg ring-2 ring-white dark:ring-gray-950">
            <AgentLogo agentKey={agents?.find(a => a.id === h.agentId)?.key ?? 'other'} size={22} />
          </span>
        ))}
      </div>
      <span className="text-xs text-gray-500 dark:text-gray-400">{label} helped</span>
    </div>
  )
}

/**
 * A question Jizza is waiting on. One answer is a tap; when several can apply they are ticked and
 * sent together. Typing in the message box answers in the user's own words instead.
 */
function QuestionCard({ question, onChoose, onSendMany }: { question: Question; onChoose: (text: string) => void; onSendMany: (text: string) => void }) {
  const [picked, setPicked] = useState<string[]>([])
  const toggle = (o: string) => setPicked(p => (p.includes(o) ? p.filter(x => x !== o) : [...p, o]))
  const sendPicked = () => {
    const ordered = question.options.filter(o => picked.includes(o))
    onSendMany(ordered.length === 1 ? ordered[0] : `${ordered.slice(0, -1).join(', ')} and ${ordered[ordered.length - 1]}`)
  }
  return (
    <div className="rounded-2xl border border-brand-300 bg-brand-50/60 p-3 animate-fade-in-up dark:border-brand-700 dark:bg-brand-500/10">
      <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{question.text}</p>
      {question.multiple && <p className="mt-0.5 text-[11px] text-gray-500 dark:text-gray-400">Choose all that apply.</p>}
      {question.options.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-2" role={question.multiple ? 'group' : undefined}>
          {question.options.map(option => {
            const on = picked.includes(option)
            return question.multiple ? (
              <button key={option} type="button" role="checkbox" aria-checked={on} onClick={() => toggle(option)}
                className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm font-medium transition-all active:scale-95 ${on
                  ? 'border-brand-500 bg-brand-500 text-white'
                  : 'border-brand-300 bg-white text-brand-700 hover:border-brand-500 dark:border-brand-700 dark:bg-gray-900 dark:text-brand-300'}`}>
                <span className={`grid h-3.5 w-3.5 place-items-center rounded-sm border ${on ? 'border-white' : 'border-current'}`}>{on && <Check className="h-2.5 w-2.5" />}</span>
                {option}
              </button>
            ) : (
              <button key={option} type="button" onClick={() => onChoose(option)}
                className="rounded-full border border-brand-300 bg-white px-3 py-1 text-sm font-medium text-brand-700 transition-all hover:-translate-y-0.5 hover:bg-brand-500 hover:text-white active:scale-95 dark:border-brand-700 dark:bg-gray-900 dark:text-brand-300 dark:hover:bg-brand-500 dark:hover:text-white">
                {option}
              </button>
            )
          })}
        </div>
      )}
      {question.multiple && (
        <div className="mt-2.5 flex items-center gap-2">
          <Button size="sm" disabled={picked.length === 0} onClick={sendPicked}>
            <Send className="h-3.5 w-3.5" /> {picked.length > 1 ? `Send ${picked.length} choices` : 'Send'}
          </Button>
          <span className="text-[11px] text-gray-500 dark:text-gray-400">or type your own answer below</span>
        </div>
      )}
    </div>
  )
}

/** Reply, Edit (the user's own messages) and Copy, under a message; shown on hover, always on touch. */
/** The line where Jizza's summary of the older messages takes over, with the summary to read. */
function EarlierNote({ summary }: { summary: string }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="flex flex-col gap-2 py-1 animate-fade-in" role="note">
      <div className="flex items-center gap-2">
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
        <button type="button" onClick={() => setOpen(o => !o)} aria-expanded={open}
          className="inline-flex max-w-[85%] items-center gap-1.5 text-center text-xs text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300">
          <ScrollText className="h-3 w-3 shrink-0" />
          Jizza remembers the messages above as a summary, so long chats stay quick
          <span className="font-medium underline underline-offset-2">{open ? 'Hide' : 'View'}</span>
        </button>
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
      </div>
      {open && (
        <div className="mx-auto w-full max-w-2xl rounded-xl border border-gray-200 bg-gray-50 px-4 py-3 text-sm leading-relaxed text-gray-700 dark:border-gray-800 dark:bg-gray-900/60 dark:text-gray-300">
          <Markdown text={summary} />
        </div>
      )}
    </div>
  )
}

/** A drag that carries files rather than text or a link. */
function hasFiles(data: DataTransfer | null) {
  return !!data && Array.from(data.types ?? []).includes('Files')
}

/** The web pages a reply drew on, as chips that open them. */
function Sources({ sources }: { sources: WebSource[] }) {
  return (
    <ul className="flex flex-wrap gap-1.5" aria-label="Sources">
      {sources.map(source => (
        <li key={source.url} className="max-w-full">
          <a href={source.url} target="_blank" rel="noopener noreferrer nofollow" title={source.url}
            className="inline-flex max-w-[16rem] items-center gap-1 rounded-full border border-gray-200 bg-white px-2 py-0.5 text-xs text-gray-600 transition-colors hover:border-brand-300 hover:text-brand-700 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300 dark:hover:border-brand-700 dark:hover:text-brand-300">
            <Globe className="h-3 w-3 shrink-0" aria-hidden />
            <span className="truncate">{source.title}</span>
          </a>
        </li>
      ))}
    </ul>
  )
}

/** "‹ 2 / 3 ›": pages between a reply's answers when "Try again" left earlier ones. */
function VersionPager({ index, count, deep, onPick }: { index: number; count: number; deep?: boolean; onPick: (i: number) => void }) {
  return (
    <span className="inline-flex items-center gap-0.5 text-xs text-gray-500 dark:text-gray-400" aria-label="Answers">
      <button type="button" onClick={() => onPick(index - 1)} disabled={index === 0} aria-label="Earlier answer" title="Earlier answer"
        className="grid h-6 w-6 place-items-center rounded-md transition-colors enabled:hover:bg-gray-100 enabled:hover:text-gray-700 disabled:opacity-40 dark:enabled:hover:bg-gray-800 dark:enabled:hover:text-gray-200">
        <ChevronLeft className="h-3.5 w-3.5" />
      </button>
      <span className="tabular-nums">{index + 1} / {count}</span>
      <button type="button" onClick={() => onPick(index + 1)} disabled={index === count - 1} aria-label="Later answer" title="Later answer"
        className="grid h-6 w-6 place-items-center rounded-md transition-colors enabled:hover:bg-gray-100 enabled:hover:text-gray-700 disabled:opacity-40 dark:enabled:hover:bg-gray-800 dark:enabled:hover:text-gray-200">
        <ChevronRight className="h-3.5 w-3.5" />
      </button>
      {deep && <span className="ml-1 inline-flex items-center gap-0.5 text-brand-600 dark:text-brand-300"><Sparkles className="h-3 w-3" /> Deeper model</span>}
    </span>
  )
}

/** Ask for the latest reply again, the same way or with the deeper model. */
function TryAgainButtons({ onTryAgain }: { onTryAgain: (deeper: boolean) => void }) {
  return (
    <span className="inline-flex items-center gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover/msg:opacity-100 [@media(hover:none)]:opacity-100">
      <ActionButton label="Try again" onClick={() => onTryAgain(false)}><RotateCcw className="h-3.5 w-3.5" /></ActionButton>
      <ActionButton label="Try again with a deeper model" onClick={() => onTryAgain(true)}><Sparkles className="h-3.5 w-3.5" /></ActionButton>
    </span>
  )
}

/** Under a reply: what Jizza did for it, in one line, and how long it took. Opens the details. */
function WhatIDid({ jobId, onOpen }: { jobId: string; onOpen: () => void }) {
  const job = useAssistantStore(s => s.jobs[jobId])
  const summary = job?.status !== 'running' ? job?.summary?.trim().replace(/\.$/, '') : undefined
  const duration = job && job.status !== 'running' ? took(job.startedAt, job.finishedAt) : undefined
  const line = [summary, duration].filter(Boolean).join(' · ')
  return (
    <button type="button" onClick={onOpen} title="See how this was done"
      className="inline-flex w-fit max-w-full items-center gap-0.5 text-left text-xs font-medium text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300">
      <span className="truncate">{line || 'See how this was done'}</span>
      <ChevronRight className="h-3 w-3 shrink-0" />
    </button>
  )
}

function MessageActions({ text, onReply, onEdit, align = 'end' }: { text: string; onReply: () => void; onEdit?: () => void; align?: 'start' | 'end' }) {
  const [copied, setCopied] = useState(false)
  const copy = () => { void navigator.clipboard?.writeText(text).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500) }) }
  return (
    <div className={`flex items-center gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover/msg:opacity-100 [@media(hover:none)]:opacity-100 ${align === 'start' ? 'self-start' : 'self-end'}`}>
      <ActionButton label="Reply" onClick={onReply}><Reply className="h-3.5 w-3.5" /></ActionButton>
      {onEdit && <ActionButton label="Edit" onClick={onEdit}><Pencil className="h-3.5 w-3.5" /></ActionButton>}
      <ActionButton label={copied ? 'Copied' : 'Copy'} onClick={copy}>{copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}</ActionButton>
    </div>
  )
}

/**
 * Thumbs up and down under a reply. A thumb saves at once and opens a line for why, which is
 * optional; tapping the same thumb again takes it back. A reason in a thread is noted in its guide.
 */
function FeedbackButtons({ feedback, onRate }: { feedback?: ReplyFeedback; onRate: (f: ReplyFeedback | null) => Promise<boolean> }) {
  const [asking, setAsking] = useState<'up' | 'down' | null>(null)
  const [reason, setReason] = useState('')
  const [failed, setFailed] = useState(false)
  const pick = async (rating: 'up' | 'down') => {
    setFailed(false)
    if (feedback?.rating === rating) {
      setAsking(null)
      if (!(await onRate(null))) setFailed(true)
      return
    }
    setReason('')
    setAsking(rating)
    if (!(await onRate({ rating }))) { setFailed(true); setAsking(null) }
  }
  const saveReason = async () => {
    const why = reason.trim()
    if (!asking) return
    const rating = asking
    setAsking(null)
    if (why && !(await onRate({ rating, reason: why }))) setFailed(true)
  }
  const thumb = (rating: 'up' | 'down') => {
    const on = feedback?.rating === rating
    const Icon = rating === 'up' ? ThumbsUp : ThumbsDown
    const label = rating === 'up' ? (on ? 'Remove "good reply"' : 'Good reply') : (on ? 'Remove "bad reply"' : 'Bad reply')
    return (
      <button type="button" onClick={() => void pick(rating)} aria-label={label} title={on && feedback?.reason ? `${label}: ${feedback.reason}` : label} aria-pressed={on}
        className={`grid h-7 w-7 place-items-center rounded-md transition-colors hover:bg-gray-100 dark:hover:bg-gray-800 ${
          on ? 'text-brand-600 dark:text-brand-300' : 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'}`}>
        <Icon className={`h-3.5 w-3.5 ${on ? 'fill-current' : ''}`} />
      </button>
    )
  }
  return (
    <>
      <div className={`flex items-center gap-0.5 transition-opacity ${feedback || asking ? '' : 'opacity-0 focus-within:opacity-100 group-hover/msg:opacity-100 [@media(hover:none)]:opacity-100'}`}>
        {thumb('up')}
        {thumb('down')}
        {failed && <span className="text-xs text-red-600 dark:text-red-400">That did not save. Try again.</span>}
      </div>
      {asking && (
        <form className="flex w-full items-center gap-1.5 animate-fade-in" onSubmit={e => { e.preventDefault(); void saveReason() }}>
          <input autoFocus value={reason} onChange={e => setReason(e.target.value)} maxLength={500}
            aria-label={asking === 'down' ? 'What was wrong with this reply' : 'What was good about this reply'}
            placeholder={asking === 'down' ? 'What was wrong? (optional)' : 'What was good? (optional)'}
            onKeyDown={e => { if (e.key === 'Escape') { e.preventDefault(); setAsking(null) } }}
            className="min-w-0 flex-1 rounded-lg border border-gray-200 bg-white px-2.5 py-1 text-xs text-gray-900 focus:border-brand-400 focus:outline-none dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100" />
          <button type="submit" aria-label={reason.trim() ? 'Send feedback' : 'Done'} className="rounded-lg bg-brand-500 px-2.5 py-1 text-xs font-medium text-white hover:bg-brand-600">{reason.trim() ? 'Send' : 'Done'}</button>
        </form>
      )}
    </>
  )
}

function ActionButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick} aria-label={label} title={label}
      className="grid h-7 w-7 place-items-center rounded-md text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200">
      {children}
    </button>
  )
}

/** Editing the user's message in place: saving sends it again and drops what came after it. */
function EditBox({ initial, className, onSave, onCancel }: { initial: string; className: string; onSave: (text: string) => Promise<void>; onCancel: () => void }) {
  const [text, setText] = useState(initial)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const save = async () => {
    if (!text.trim() || saving) return
    setSaving(true)
    setError(null)
    try {
      await onSave(text.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That did not go through.')
      setSaving(false)
    }
  }
  return (
    <div className={`ml-auto flex w-full flex-col gap-2 rounded-2xl border border-brand-300 bg-white p-2 shadow-sm animate-fade-in dark:border-brand-700 dark:bg-gray-900 ${className}`}>
      <textarea autoFocus value={text} onChange={e => setText(e.target.value)} aria-label="Edit your message" rows={3}
        onKeyDown={e => {
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void save() }
          if (e.key === 'Escape') onCancel()
        }}
        className="max-h-60 w-full resize-y bg-transparent px-2 py-1 text-sm text-gray-900 focus:outline-none dark:text-gray-100" />
      {error && <p className="px-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
      <div className="flex items-center justify-end gap-2">
        <span className="mr-auto px-2 text-[11px] text-gray-400">Replies after this message are replaced.</span>
        <Button type="button" size="sm" variant="secondary" onClick={onCancel} disabled={saving}>Cancel</Button>
        <Button type="button" size="sm" loading={saving} disabled={!text.trim()} onClick={() => void save()}>Save and send</Button>
      </div>
    </div>
  )
}

/** A reply's quote: the quoted message as Markdown block-quote lines, trimmed to a few lines. */
function quote(text: string): string {
  const short = text.length > 400 ? `${text.slice(0, 399).trimEnd()}…` : text
  return short.split('\n').map(l => `> ${l}`).join('\n')
}

/** A message that opens with a quote ("> …" lines): the quoted part and what was written. */
export function splitQuote(text: string): { quote?: string; body: string } {
  const lines = text.split('\n')
  let i = 0
  while (i < lines.length && lines[i].startsWith('>')) i++
  if (i === 0) return { body: text }
  return { quote: lines.slice(0, i).map(l => l.replace(/^>\s?/, '')).join('\n'), body: lines.slice(i).join('\n').trim() }
}

function AttachmentIcon({ kind, linked, className }: { kind: AttachmentKind; linked?: boolean; className?: string }) {
  const Icon = kind === 'folder' ? (linked ? FolderSymlink : Folder) : kind === 'image' ? Image : FileText
  return <Icon className={className} aria-hidden />
}

/** The "+" beside the message box: add files, open the shortcuts, or work with a folder on this computer. */
function AttachMenu({ onFiles, onSlash, onAnyFolder, onLinkFolder }: {
  onFiles: () => void; onSlash: () => void; onAnyFolder: () => void; onLinkFolder: () => void
}) {
  const [open, setOpen] = useState(false)
  const [local, setLocal] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) { setLocal(false); return }
    const away = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', away); document.removeEventListener('keydown', esc) }
  }, [open])
  const pick = (action: () => void) => () => { setOpen(false); action() }
  const item = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'
  return (
    <div ref={ref} className="relative shrink-0">
      <button type="button" aria-label="Add files and more" title="Add files and more" aria-haspopup="menu" aria-expanded={open}
        onClick={() => setOpen(o => !o)}
        className={`grid h-9 w-9 place-items-center rounded-xl transition-all active:scale-90 ${open
          ? 'bg-gray-100 text-brand-600 dark:bg-gray-800 dark:text-brand-300'
          : 'text-gray-500 hover:bg-gray-100 hover:text-brand-600 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-brand-300'}`}>
        <Plus className="h-4 w-4" />
      </button>
      {open && (
        <div role="menu" aria-label="Add to message"
          className="absolute bottom-full left-0 z-20 mb-2 w-56 rounded-xl border border-gray-200 bg-white p-1 shadow-lg animate-fade-in dark:border-gray-700 dark:bg-gray-900">
          <button type="button" role="menuitem" className={item} onClick={pick(onFiles)} onMouseEnter={() => setLocal(false)}>
            <Paperclip className="h-4 w-4 text-gray-500" /> Add files or photos
          </button>
          <button type="button" role="menuitem" className={item} onClick={pick(onSlash)} onMouseEnter={() => setLocal(false)}>
            <SquareSlash className="h-4 w-4 text-gray-500" /> Slash commands
          </button>
          <div className="relative" onMouseEnter={() => setLocal(true)}>
            <button type="button" role="menuitem" aria-haspopup="menu" aria-expanded={local} onClick={() => setLocal(l => !l)}
              className={`${item} ${local ? 'bg-gray-100 dark:bg-gray-800' : ''}`}>
              <Laptop className="h-4 w-4 text-gray-500" /> <span className="flex-1">Work locally</span> <ChevronRight className="h-4 w-4 text-gray-400" />
            </button>
            {local && (
              <div role="menu" aria-label="Work locally"
                className="absolute bottom-0 left-full z-20 ml-1.5 w-48 rounded-xl border border-gray-200 bg-white p-1 shadow-lg animate-fade-in dark:border-gray-700 dark:bg-gray-900">
                <button type="button" role="menuitem" className={item} onClick={pick(onAnyFolder)} title="Attach a folder's files to your next message">
                  <Laptop className="h-4 w-4 text-gray-500" /> Any folder
                </button>
                <button type="button" role="menuitem" className={item} onClick={pick(onLinkFolder)} title="Keep a folder linked: Jizza reads it again with every message">
                  <FolderPlus className="h-4 w-4 text-gray-500" /> Link folder…
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
