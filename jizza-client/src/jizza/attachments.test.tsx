import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('@/api/chat', () => ({
  chatApi: {
    send: vi.fn(async (body: { conversationId?: string }) => ({ jobId: 'job-1', conversationId: body.conversationId ?? 'plain-1' })),
    cancel: vi.fn(),
    job: vi.fn(),
    uploadFile: vi.fn(async (file: File) => ({
      id: '11111111-2222-3333-4444-555555555555', name: file.name, contentType: file.type, sizeBytes: file.size,
      text: file.type === 'application/pdf' ? 'Right eye 6/9, left eye 6/12'
        : file.name.endsWith('.xlsx') ? '## Sheet: Budget\nRent\t1200' : '',
    })),
  },
}))

import { chatApi } from '@/api/chat'
import { useAssistantStore } from './assistantStore'
import { Conversation } from './Conversation'
import { fileAttachment, folderFromFiles, splitAttachments, withAttachments } from './attachments'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false })
  vi.clearAllMocks()
})

const inFolder = (path: string, text: string) => {
  const file = new File([text], path.split('/').pop()!, { type: 'text/plain' })
  Object.defineProperty(file, 'webkitRelativePath', { value: path })
  return file
}

describe('Attachments', () => {
  it('put text files in the message, upload PDFs and photos, and leave other files by name', async () => {
    const message = await withAttachments('Summarise these', [
      fileAttachment(new File(['# Plan\nShip on Friday'], 'plan.md', { type: 'text/markdown' })),
      fileAttachment(new File([new Uint8Array([1, 2, 3])], 'eye test.pdf', { type: 'application/pdf' })),
      fileAttachment(new File([new Uint8Array([1, 2, 3])], 'photo.png', { type: 'image/png' })),
      fileAttachment(new File([new Uint8Array([1, 2, 3])], 'logo.sketch', { type: 'application/octet-stream' })),
    ])
    expect(chatApi.uploadFile).toHaveBeenCalledTimes(2)
    expect(message).toContain('<attachment kind="file" name="plan.md">\n# Plan\nShip on Friday\n</attachment>')
    expect(message).toContain('<attachment kind="file" name="eye test.pdf" ref="11111111-2222-3333-4444-555555555555">\nRight eye 6/9, left eye 6/12\n</attachment>')
    expect(message).toContain('<attachment kind="image" name="photo.png" ref="11111111-2222-3333-4444-555555555555">\n(photo; Jizza sees it)')
    expect(message).toContain("can't open this kind of file yet")
    expect(splitAttachments(message)).toMatchObject({
      text: 'Summarise these',
      attachments: [{ kind: 'file', name: 'plan.md' }, { kind: 'file', name: 'eye test.pdf' }, { kind: 'image', name: 'photo.png' }, { kind: 'file', name: 'logo.sketch' }],
    })
  })

  it('read Word, Excel and PowerPoint files on the server and put their text in the message', async () => {
    const sheet = new File(['PK'], 'budget.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const message = await withAttachments('Check my budget', [fileAttachment(sheet)])
    expect(chatApi.uploadFile).toHaveBeenCalledWith(sheet)
    expect(message).toContain('<attachment kind="file" name="budget.xlsx">\n## Sheet: Budget\nRent\t1200\n</attachment>')
    // Its text is all the model needs: no reference to the stored file.
    expect(message).not.toContain('ref="')
  })

  it('attach files dropped on the message box', async () => {
    wrap(<Conversation onOpenJob={() => {}} />)
    const note = new File(['Buy milk'], 'list.txt', { type: 'text/plain' })
    const target = screen.getByPlaceholderText(/Ask Jizza/).closest('div')!
    fireEvent.drop(target, { dataTransfer: { files: [note], types: ['Files'] } })
    expect(await screen.findByText('list.txt')).toBeInTheDocument()
  })

  it('attach files pasted into the message box', async () => {
    wrap(<Conversation onOpenJob={() => {}} />)
    const shot = new File(['img'], 'screenshot.png', { type: 'image/png' })
    fireEvent.paste(screen.getByPlaceholderText(/Ask Jizza/), { clipboardData: { files: [shot] } })
    expect(await screen.findByText('screenshot.png')).toBeInTheDocument()
  })

  it('send a PDF by name when its upload fails', async () => {
    vi.mocked(chatApi.uploadFile).mockRejectedValueOnce(new Error('offline'))
    const message = await withAttachments('Read this', [fileAttachment(new File([new Uint8Array([1])], 'scan.pdf', { type: 'application/pdf' }))])
    expect(message).toContain('<attachment kind="file" name="scan.pdf">\n')
    expect(message).toContain('the upload failed')
  })

  it('list a folder, skip node_modules, and stay within the message limit', async () => {
    const big = 'x'.repeat(40_000)
    const folder = folderFromFiles([
      inFolder('site/src/app.ts', 'export const app = 1'),
      inFolder('site/node_modules/lib/index.js', 'ignored'),
      inFolder('site/README.md', big),
    ])!
    expect(folder.name).toBe('site')
    const message = await withAttachments('Review this', [folder])
    expect(message).toContain('Files (2):\nsite/README.md\nsite/src/app.ts')
    expect(message).not.toContain('node_modules')
    expect(message.length).toBeLessThan(16_000)
  })

  it('are tagged above the chat bar, removable, and sent with the message', async () => {
    wrap(<Conversation />)
    fireEvent.click(screen.getByRole('button', { name: 'Add files and more' }))
    expect(screen.getByRole('menuitem', { name: /Add files or photos/ })).toBeInTheDocument()
    fireEvent.mouseEnter(screen.getByRole('menuitem', { name: /Work locally/ }).parentElement!)
    expect(screen.getByRole('menuitem', { name: /Any folder/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Link folder/ })).toBeInTheDocument()

    fireEvent.change(screen.getByTestId('attach-files'), { target: { files: [
      new File(['hello'], 'notes.txt', { type: 'text/plain' }),
      new File(['bye'], 'old.txt', { type: 'text/plain' }),
    ] } })
    fireEvent.change(screen.getByTestId('attach-folder'), { target: { files: [inFolder('reports/q3.csv', 'a,b')] } })
    const tags = screen.getByRole('list', { name: 'Attached' })
    expect(within(tags).getByText('notes.txt')).toBeInTheDocument()
    expect(within(tags).getByText('reports')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Remove old.txt' }))
    expect(within(tags).queryByText('old.txt')).not.toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText(/Ask Jizza/), { target: { value: 'What is in here?' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Send' })) })

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    const sent = vi.mocked(chatApi.send).mock.calls[0][0].message
    expect(sent).toMatch(/^What is in here\?\n\n<attachment kind="file" name="notes.txt">\nhello\n<\/attachment>/)
    expect(sent).toContain('<attachment kind="folder" name="reports">')
    expect(sent).not.toContain('old.txt')
    expect(screen.queryByRole('list', { name: 'Attached' })).not.toBeInTheDocument()
    // The sent bubble shows the tags, not the file contents.
    expect(screen.getByText('notes.txt')).toBeInTheDocument()
    expect(screen.queryByText(/hello/)).not.toBeInTheDocument()
  })

  it('open the slash commands from the menu', () => {
    wrap(<Conversation />)
    fireEvent.click(screen.getByRole('button', { name: 'Add files and more' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Slash commands/ }))
    expect(screen.getByRole('listbox', { name: 'Shortcuts' })).toBeInTheDocument()
  })
})
