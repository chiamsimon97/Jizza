import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { KeepLookingField, pollOf } from './KeepLookingField'

describe('keep looking', () => {
  it('never asks a site more than once every 15 seconds', () => {
    expect(pollOf(false, '60', '30')).toBeNull()
    expect(pollOf(true, '5', '30')).toEqual({ everySec: 15, forMinutes: 30 })
    expect(pollOf(true, '', '')).toEqual({ everySec: 60, forMinutes: 30 })
  })

  it('turns on with a minute between looks for half an hour, and off again', () => {
    const onChange = vi.fn()
    render(<KeepLookingField onChange={onChange} />)
    fireEvent.click(screen.getByLabelText('Keep looking'))
    expect(onChange).toHaveBeenLastCalledWith({ everySec: 60, forMinutes: 30 })
    fireEvent.change(screen.getByLabelText('Minutes to keep looking'), { target: { value: '90' } })
    fireEvent.blur(screen.getByLabelText('Minutes to keep looking'))
    expect(onChange).toHaveBeenLastCalledWith({ everySec: 60, forMinutes: 90 })
  })

  it('stops looking again when turned off', () => {
    const onChange = vi.fn()
    render(<KeepLookingField poll={{ everySec: 60, forMinutes: 30 }} onChange={onChange} />)
    fireEvent.click(screen.getByLabelText('Keep looking'))
    expect(onChange).toHaveBeenLastCalledWith(null)
  })
})
