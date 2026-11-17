import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

const uploadFile = vi.fn()
vi.mock('@/api/chat', () => ({ chatApi: { uploadFile: (f: File) => uploadFile(f) } }))

import { FileValueInput } from './FileValueInput'

describe('FileValueInput', () => {
  beforeEach(() => uploadFile.mockReset())

  it('stores the file the person adds and gives the input its id', async () => {
    uploadFile.mockResolvedValue({ id: 'f-1', name: 'photo.jpg', contentType: 'image/jpeg', sizeBytes: 3, text: '' })
    const onChange = vi.fn()
    render(<FileValueInput label="Photo" value="" onChange={onChange} />)
    expect(screen.getByRole('button', { name: 'Add a file' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Photo'), { target: { files: [new File(['abc'], 'photo.jpg', { type: 'image/jpeg' })] } })
    await waitFor(() => expect(onChange).toHaveBeenCalledWith('f-1'))
  })

  it('refuses a file over 10 MB without sending it', async () => {
    const onChange = vi.fn()
    render(<FileValueInput label="Photo" value="" onChange={onChange} />)
    const big = new File(['x'], 'film.mov')
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 })
    fireEvent.change(screen.getByLabelText('Photo'), { target: { files: [big] } })
    expect((await screen.findByRole('alert')).textContent).toContain('over 10 MB')
    expect(uploadFile).not.toHaveBeenCalled()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('shows the file added and lets the person remove it', () => {
    const onChange = vi.fn()
    render(<FileValueInput label="Photo" value="f-1" onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', { name: 'Remove File added' }))
    expect(onChange).toHaveBeenCalledWith('')
  })
})
