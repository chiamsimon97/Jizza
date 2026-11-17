import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { FitPictureField, fitOf } from './FitPictureField'

describe('fitting an uploaded picture', () => {
  it('asks only for what the boxes say', () => {
    expect(fitOf('', '', '', '')).toBeNull()
    expect(fitOf('50', '200', '', 'jpeg')).toEqual({ maxKb: 50, width: 200, format: 'jpeg' })
    expect(fitOf('0', '99999', '230', '')).toEqual({ height: 230 })
  })

  it('sends the fit when a box is left, and nothing when it did not change', () => {
    const onChange = vi.fn()
    render(<FitPictureField fit={{ maxKb: 50 }} onChange={onChange} />)
    fireEvent.blur(screen.getByLabelText('Largest size in KB'))
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Width in pixels'), { target: { value: '200px' } })
    fireEvent.blur(screen.getByLabelText('Width in pixels'))
    expect(onChange).toHaveBeenCalledWith({ maxKb: 50, width: 200 })
    fireEvent.change(screen.getByLabelText('Picture format'), { target: { value: 'jpeg' } })
    expect(onChange).toHaveBeenLastCalledWith({ maxKb: 50, width: 200, format: 'jpeg' })
  })
})
