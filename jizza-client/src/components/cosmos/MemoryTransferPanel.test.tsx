import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'

const importText = vi.fn()
vi.mock('@/api/memory', () => ({
  memoryTransferApi: {
    export: vi.fn(),
    importPrompt: () => Promise.resolve({ prompt: 'List everything you remember about me.' }),
    importText: (text: string, provider?: string) => importText(text, provider),
    importFile: vi.fn(),
  },
}))

import { MemoryTransferPanel } from './MemoryTransferPanel'

describe('MemoryTransferPanel', () => {
  beforeEach(() => importText.mockReset())

  it('offers import and export, like other assistants’ memory settings', () => {
    renderWithSnapshot(<MemoryTransferPanel open onClose={() => {}} />, makeSnapshot())
    expect(screen.getByText('Import memory from other AI providers')).toBeInTheDocument()
    expect(screen.getByText('Export memory')).toBeInTheDocument()
  })

  it('gives the prompt to ask with, then imports the pasted answer and says what came in', async () => {
    importText.mockResolvedValue({ created: 2, duplicates: 1, pending: 0, notes: 0, skipped: 0, problems: [] })
    renderWithSnapshot(<MemoryTransferPanel open onClose={() => {}} />, makeSnapshot())

    fireEvent.click(screen.getByRole('button', { name: 'Start import' }))
    expect(await screen.findByText('List everything you remember about me.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('What the other assistant remembers'),
      { target: { value: '- Likes tea\n- Lives in Penang' } })
    fireEvent.click(screen.getByRole('button', { name: 'Import' }))

    await waitFor(() => expect(importText).toHaveBeenCalledWith('- Likes tea\n- Lives in Penang', ''))
    expect(await screen.findByText(/2 new facts, 1 already known/)).toBeInTheDocument()
  })
})
