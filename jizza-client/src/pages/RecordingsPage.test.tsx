import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
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
})
