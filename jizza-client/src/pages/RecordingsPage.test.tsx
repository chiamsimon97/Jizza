import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { RecordingsPage } from './RecordingsPage'
import { webApi } from '@/api/web'
import { workflowsApi } from '@/api/workflows'
import type { WorkflowDto } from '@/types'

const wf = (over: Partial<WorkflowDto>): WorkflowDto =>
  ({ id: 'w1', name: 'Download invoices', graph: {}, origin: 'RECORDED', status: 'DRAFT', version: 1, createdAt: '2026-01-01T00:00:00Z', ...over })

describe('RecordingsPage', () => {
  it('lists saved recordings and drafts, and leaves other workflows out', async () => {
    vi.spyOn(webApi, 'listRecordings').mockResolvedValue([
      { id: 'r1', name: 'Pay bills', status: 'review', deviceId: 'd1', startUrl: 'https://bank.example.com', stepCount: 4, updatedAt: '2026-01-01T00:00:00Z' },
    ])
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([wf({}), wf({ id: 'w2', name: 'Built by hand', origin: 'USER_BUILT' })])
    renderWithSnapshot(<RecordingsPage />, makeSnapshot())
    expect(await screen.findByText('Download invoices')).toBeInTheDocument()
    expect(screen.getByText('Pay bills')).toBeInTheDocument()
    expect(screen.getByText('Ready to review')).toBeInTheDocument()
    expect(screen.queryByText('Built by hand')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open in builder' })).toHaveAttribute('href', '/workflows?open=w1')
  })

  it('opens one recording\'s review when chat links to it', async () => {
    vi.spyOn(webApi, 'listRecordings').mockResolvedValue([])
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([])
    const get = vi.spyOn(webApi, 'getRecording').mockResolvedValue(
      { id: 'r9', name: 'Pay bills', status: 'review', deviceId: 'd1', startUrl: 'https://bank.example.com', stepCount: 2, updatedAt: '2026-01-01T00:00:00Z', steps: [] } as never)
    renderWithSnapshot(<RecordingsPage />, makeSnapshot(), '/recordings?review=r9')
    expect(await screen.findByText('Record a task in Chrome')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('r9')
  })

  it('shows a Windows recording by its app and PC', async () => {
    vi.spyOn(webApi, 'listRecordings').mockResolvedValue([
      { id: 'r2', name: 'Notepad task', status: 'recording', deviceId: 'd2', deviceName: 'Office PC', kind: 'desktop', app: 'Notepad', startUrl: null, stepCount: 3, updatedAt: '2026-01-01T00:00:00Z' },
    ])
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([])
    renderWithSnapshot(<RecordingsPage />, makeSnapshot())
    expect(await screen.findByText('Notepad on Office PC, 3 steps')).toBeInTheDocument()
  })

  it('deletes a saved recording after asking, and the list drops it', async () => {
    vi.spyOn(webApi, 'listRecordings').mockResolvedValue([])
    const list = vi.spyOn(workflowsApi, 'list').mockResolvedValueOnce([wf({})]).mockResolvedValue([])
    const del = vi.spyOn(workflowsApi, 'delete').mockResolvedValue(undefined)
    renderWithSnapshot(<RecordingsPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Download invoices' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete Download invoices?' })
    expect(del).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete recording' }))
    await waitFor(() => expect(del).toHaveBeenCalledWith('w1'))
    expect(await screen.findByText('No recordings yet')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(2)
  })

  it('discards a draft only after the confirm', async () => {
    vi.spyOn(webApi, 'listRecordings').mockResolvedValue([
      { id: 'r1', name: 'Pay bills', status: 'review', deviceId: 'd1', startUrl: 'https://bank.example.com', stepCount: 4, updatedAt: '2026-01-01T00:00:00Z' },
    ])
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([])
    const discard = vi.spyOn(webApi, 'discardRecording').mockResolvedValue(undefined)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    renderWithSnapshot(<RecordingsPage />, makeSnapshot())
    const button = await screen.findByRole('button', { name: 'Discard Pay bills' })
    fireEvent.click(button)
    expect(discard).not.toHaveBeenCalled()
    fireEvent.click(button)
    await waitFor(() => expect(discard).toHaveBeenCalledWith('r1'))
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('edits a saved recording in a chosen browser with test values from its inputs', async () => {
    vi.spyOn(webApi, 'listRecordings').mockResolvedValue([])
    const graph = { nodes: [{ id: 'start', type: 'TRIGGER', config: { inputs: [{ name: 'movie', type: 'text', default: 'Ramayana' }] } }] }
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([wf({ graph })])
    vi.spyOn(webApi, 'browsers').mockResolvedValue([
      { id: 'b1', workspaceId: null, name: 'Chromium browser', online: true, family: 'chrome', version: '0.10.0', allowedSites: [], sessions: 0 } as never,
    ])
    const edit = vi.spyOn(webApi, 'editSavedRecording').mockResolvedValue(
      { id: 'r5', name: 'Download invoices', status: 'recording', deviceId: 'b1', startUrl: 'https://x.example.com', stepCount: 2, updatedAt: '2026-01-01T00:00:00Z', sourceWorkflowId: 'w1', steps: [] } as never)
    vi.spyOn(webApi, 'getRecording').mockResolvedValue(
      { id: 'r5', name: 'Download invoices', status: 'recording', deviceId: 'b1', deviceName: 'Chromium browser', startUrl: 'https://x.example.com', stepCount: 2, updatedAt: '2026-01-01T00:00:00Z', sourceWorkflowId: 'w1', steps: [] } as never)
    vi.spyOn(workflowsApi, 'get').mockResolvedValue(wf({ graph }))
    renderWithSnapshot(<RecordingsPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit "Download invoices"' })
    expect(within(dialog).getByLabelText('Test value for movie')).toHaveValue('Ramayana')
    fireEvent.change(within(dialog).getByLabelText('Test value for movie'), { target: { value: 'Kantara' } })
    await within(dialog).findByRole('option', { name: 'Chromium browser' })
    fireEvent.change(within(dialog).getByLabelText('Browser agent'), { target: { value: 'b1' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Edit in browser' }))
    await waitFor(() => expect(edit).toHaveBeenCalledWith({ workflowId: 'w1', deviceId: 'b1', testValues: { movie: 'Kantara' } }))
    expect(await screen.findByText('Editing Download invoices')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Finish editing' })).toBeInTheDocument()
  })
})
