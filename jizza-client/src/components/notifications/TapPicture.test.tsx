import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TapPicture, parseTap, tapAt } from './TapPicture'

describe('tapping on a picture', () => {
  it('keeps taps as fractions of the picture, inside it', () => {
    expect(tapAt(0.123456, 0.5)).toBe('0.1235,0.5')
    expect(tapAt(-1, 2)).toBe('0,1')
    expect(parseTap('0.25,0.75')).toEqual({ x: 0.25, y: 0.75 })
    expect(parseTap('nonsense')).toBeUndefined()
  })

  it('adds a tap where the picture is clicked, and takes one away when its number is', () => {
    const onChange = vi.fn()
    render(<TapPicture src="/x.png" label="Seat map" taps={['0.1,0.1']} onChange={onChange} />)
    const picture = screen.getByTestId('tap-picture')
    picture.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 100, right: 200, bottom: 100, x: 0, y: 0, toJSON: () => ({}) })
    fireEvent.click(picture, { clientX: 50, clientY: 50 })
    expect(onChange).toHaveBeenLastCalledWith(['0.1,0.1', '0.25,0.5'])
    fireEvent.click(screen.getByRole('button', { name: 'Take away tap 1' }))
    expect(onChange).toHaveBeenLastCalledWith([])
  })
})
