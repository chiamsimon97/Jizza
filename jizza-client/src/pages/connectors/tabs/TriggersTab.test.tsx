import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { servicesApi } from '@/api/services'
import { workflowsApi } from '@/api/workflows'
import type { ServiceDetail, ServiceTrigger, WorkflowDto } from '@/types'
import { TriggersTab } from './TriggersTab'
import { WorkflowTriggerControls } from '@/components/workflow/WorkflowTriggerControls'
import { formsApi } from '@/api/forms'

const instant: ServiceTrigger = {
  id: 't1', serviceId: 's1', name: 'homework_set', label: 'Homework set', apiId: null,
  kind: 'webhook', pollIntervalMinutes: 15, idField: 'id', enabled: true,
  webhookUrl: 'https://jizza.test/api/webhooks/services/tok', signed: true,
  signatureHeader: 'X-Signature', lastReceivedAt: null, workflowIds: ['w1'],
}

const detail = {
  service: { id: 's1', name: 'School portal' },
  apis: [], resources: [], actions: [], variables: [], triggers: [instant], oauthRedirectUri: '',
} as unknown as ServiceDetail

afterEach(() => vi.restoreAllMocks())

describe('Instant triggers', () => {
  it('can be made without any list API, and shows the address to give the service', async () => {
    renderWithSnapshot(<TriggersTab detail={detail} refresh={() => {}} />, makeSnapshot('PERSONAL', 'OWNER'))
    expect(screen.getByText('Instant')).toBeInTheDocument()
    expect(screen.getByText('Starts 1 workflow')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /New trigger/ })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Edit Homework set' }))
    expect(await screen.findByTitle(instant.webhookUrl!)).toBeInTheDocument()
    expect(screen.getByLabelText(/Signing secret \(set/)).toHaveValue('')
  })

  it('sends the kind and a new secret, and no API, when saved', async () => {
    const save = vi.spyOn(servicesApi, 'saveTrigger').mockResolvedValue(instant)
    renderWithSnapshot(<TriggersTab detail={detail} refresh={() => {}} />, makeSnapshot('PERSONAL', 'OWNER'))
    fireEvent.click(screen.getByRole('button', { name: /New trigger/ }))
    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Mark posted' } })
    fireEvent.change(screen.getByLabelText(/Signing secret/), { target: { value: 'long-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save trigger' }))
    await waitFor(() => expect(save).toHaveBeenCalled())
    expect(save.mock.calls[0][2]).toMatchObject({
      kind: 'webhook', apiId: null, name: 'mark_posted', signingSecret: 'long-secret',
    })
  })
})

describe('A workflow started by a service event', () => {
  it('picks the trigger it listens to', async () => {
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([{ id: 'w2', name: 'File homework', enabled: false } as WorkflowDto])
    vi.spyOn(formsApi, 'list').mockResolvedValue([])
    vi.spyOn(workflowsApi, 'serviceTriggers').mockResolvedValue([{
      serviceId: 's1', serviceName: 'School portal', triggerId: 't1', label: 'Homework set',
      kind: 'webhook', enabled: true, live: true, workflowIds: [],
    }])
    const set = vi.spyOn(workflowsApi, 'setServiceTrigger').mockResolvedValue({ id: 'w2', name: 'File homework' } as WorkflowDto)
    renderWithSnapshot(<WorkflowTriggerControls workflowId="w2" />, makeSnapshot('PERSONAL', 'OWNER'))
    const select = await screen.findByLabelText('Service event that starts this workflow')
    fireEvent.change(select, { target: { value: 't1' } })
    await waitFor(() => expect(set).toHaveBeenCalledWith('w2', 't1'))
  })
})
