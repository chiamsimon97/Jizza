import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { RowsInput, rowsColumnOf } from './RowsInput'

describe('RowsInput', () => {
  it('offers a dropdown column\'s choices and keeps a value the page no longer lists', () => {
    const onChange = vi.fn()
    render(<RowsInput label="Passengers" onChange={onChange}
      columns={[{ key: 'name', label: 'Name' }, { key: 'gender', label: 'Gender', options: ['Male', 'Female'] }]}
      value={JSON.stringify([{ name: 'Asha', gender: 'Other' }])} />)
    expect(screen.getByLabelText('Name 1').tagName).toBe('INPUT')
    const gender = screen.getByLabelText('Gender 1') as HTMLSelectElement
    expect(gender.tagName).toBe('SELECT')
    expect(Array.from(gender.options).map(o => o.value)).toEqual(['', 'Other', 'Male', 'Female'])
    fireEvent.change(gender, { target: { value: 'Female' } })
    expect(onChange).toHaveBeenCalledWith(JSON.stringify([{ name: 'Asha', gender: 'Female' }]))
  })

  it('reads columns leniently', () => {
    expect(rowsColumnOf({ name: 'age' })).toEqual({ key: 'age', label: 'age' })
    expect(rowsColumnOf({ key: 'g', label: 'Gender', options: ['Male', 1] })).toEqual({ key: 'g', label: 'Gender', options: ['Male', '1'] })
    expect(rowsColumnOf({ label: 'x' })).toBeNull()
  })
})
