import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { OtpInput } from './OtpInput'

function Harness({ onComplete }: { onComplete?: (code: string) => void }) {
  const [value, setValue] = useState('')
  return (
    <>
      <OtpInput label="Code" autoFocus value={value} onChange={setValue} onComplete={onComplete} />
      <output>{value}</output>
    </>
  )
}

const boxes = () => screen.getAllByRole('textbox') as HTMLInputElement[]

describe('OtpInput', () => {
  it('shows six labelled boxes in a group and focuses the first', () => {
    render(<Harness />)
    expect(screen.getByRole('group', { name: 'Code' })).toBeInTheDocument()
    expect(boxes()).toHaveLength(6)
    expect(screen.getByLabelText('Digit 1 of 6')).toHaveFocus()
    expect(boxes()[0]).toHaveAttribute('autocomplete', 'one-time-code')
  })

  it('moves to the next box as digits are typed and completes on the sixth', () => {
    const onComplete = vi.fn()
    render(<Harness onComplete={onComplete} />)
    '123456'.split('').forEach((d, i) => fireEvent.change(boxes()[i], { target: { value: d } }))
    expect(screen.getByRole('status')).toHaveTextContent('123456')
    expect(onComplete).toHaveBeenCalledWith('123456')
  })

  it('ignores letters', () => {
    render(<Harness />)
    fireEvent.change(boxes()[0], { target: { value: 'a' } })
    expect(boxes()[0].value).toBe('')
    fireEvent.change(boxes()[0], { target: { value: '7' } })
    expect(boxes()[1]).toHaveFocus()
  })

  it('fills every box from a pasted code, wherever it is pasted', () => {
    const onComplete = vi.fn()
    render(<Harness onComplete={onComplete} />)
    fireEvent.paste(boxes()[0], { clipboardData: { getData: () => '987 654' } })
    expect(boxes().map(b => b.value).join('')).toBe('987654')
    expect(onComplete).toHaveBeenCalledWith('987654')
  })

  it('fills every box when autofill drops the code into the first one', () => {
    render(<Harness />)
    fireEvent.change(boxes()[0], { target: { value: '246810' } })
    expect(screen.getByRole('status')).toHaveTextContent('246810')
  })

  it('goes back and clears the previous digit on Backspace in an empty box', () => {
    render(<Harness />)
    fireEvent.change(boxes()[0], { target: { value: '1' } })
    fireEvent.change(boxes()[1], { target: { value: '2' } })
    expect(boxes()[2]).toHaveFocus()
    fireEvent.keyDown(boxes()[2], { key: 'Backspace' })
    expect(boxes()[1]).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent(/^1$/)
  })
})
