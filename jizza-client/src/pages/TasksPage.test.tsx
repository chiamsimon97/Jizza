import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { TasksPage } from './TasksPage'
import { tasksApi } from '@/api/tasks'
import { proceduresApi } from '@/api/procedures'
import { agentsApi } from '@/api/agents'

describe('Tasks page', () => {
  it('a failed load is an error with a retry, not "no tasks"', async () => {
    vi.spyOn(tasksApi, 'list').mockRejectedValue(new TypeError('Failed to fetch'))
    vi.spyOn(proceduresApi, 'list').mockResolvedValue([])
    vi.spyOn(agentsApi, 'list').mockResolvedValue([])
    renderWithSnapshot(<TasksPage />, makeSnapshot())
    expect(await screen.findByText("Couldn't load your tasks")).toBeInTheDocument()
    expect(screen.queryByText('No tasks yet')).toBeNull()
  })

  it('an empty account shows the empty state', async () => {
    vi.spyOn(tasksApi, 'list').mockResolvedValue([])
    vi.spyOn(proceduresApi, 'list').mockResolvedValue([])
    vi.spyOn(agentsApi, 'list').mockResolvedValue([])
    renderWithSnapshot(<TasksPage />, makeSnapshot())
    expect(await screen.findByText('No tasks yet')).toBeInTheDocument()
  })
})
