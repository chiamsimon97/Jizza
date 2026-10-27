import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { EditorView } from '@codemirror/view'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { functionsApi, type ScriptFunction } from '@/api/functions'
import { FunctionsPage } from './FunctionsPage'

const page = (route: string) => renderWithSnapshot(
  <Routes><Route path="/functions/:functionId?" element={<FunctionsPage />} /></Routes>, makeSnapshot(), route)

const saved: ScriptFunction = {
  id: 'fn-1', name: 'withTax', description: null, inputs: [{ name: 'amount', type: 'number' }], script: 'amount * 1.1',
  returnType: 'number', returnFields: [], usedBy: ['Invoices'], createdAt: '', updatedAt: '1', scope: 'own',
}

describe('Functions page', () => {
  it('makes a function: inputs, a script that calls another, a test, and map keys taken from the test', async () => {
    vi.spyOn(functionsApi, 'list').mockResolvedValue([saved])
    const test = vi.spyOn(functionsApi, 'test').mockResolvedValue({ value: { total: 11, customer: { email: 'a@b.c' } }, type: 'map', error: null })
    const create = vi.spyOn(functionsApi, 'create').mockResolvedValue({ ...saved, id: 'fn-2', name: 'orderTotal' })
    page('/functions/new')

    fireEvent.change(await screen.findByRole('textbox', { name: 'Link name' }), { target: { value: 'orderTotal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add an input' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Name of input 1' }), { target: { value: 'lines' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'Type of input 1' }), { target: { value: 'list' } })
    const view = EditorView.findFromDOM(screen.getByRole('textbox', { name: 'Script' }))!
    const script = "var t = 0;\nfor (var l : lines) { t = t + l.price; }\nreturn {'total': fn.withTax(t), 'customer': {'email': 'a@b.c'}};"
    act(() => { view.dispatch({ changes: { from: 0, insert: script }, userEvent: 'input.type' }) })

    fireEvent.change(screen.getByRole('textbox', { name: 'Test value for lines' }), { target: { value: '[{"price": 10}]' } })
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    await waitFor(() => expect(test).toHaveBeenCalled())
    expect(test.mock.calls[0][1]).toEqual({ lines: [{ price: 10 }] })
    fireEvent.click(await screen.findByRole('button', { name: 'Use these keys' }))
    expect(screen.getByRole('combobox', { name: 'Return type' })).toHaveValue('map')
    expect(screen.getByRole('textbox', { name: 'Key 3' })).toHaveValue('customer.email')

    fireEvent.click(screen.getByRole('button', { name: 'Create function' }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toMatchObject({
      name: 'orderTotal', inputs: [{ name: 'lines', type: 'list' }], script, returnType: 'map',
      returnFields: [{ path: 'total', type: 'number' }, { path: 'customer', type: 'map' }, { path: 'customer.email', type: 'text' }],
    })
  })

  it('says which workflows use a function before deleting it', async () => {
    vi.spyOn(functionsApi, 'list').mockResolvedValue([saved])
    const remove = vi.spyOn(functionsApi, 'remove').mockResolvedValue(undefined)
    page('/functions/fn-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(screen.getByText(/"Invoices" run it/)).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Delete' }).at(-1)!)
    await waitFor(() => expect(remove).toHaveBeenCalledWith('fn-1'))
  })

  it('shows an installed public function to read and test, and copies it to change it', async () => {
    const slugify: ScriptFunction = {
      id: 'pub-1', name: 'slugify', description: 'Turns a title into a slug.', inputs: [{ name: 'text', type: 'text', required: true }],
      script: "return text.lower();", returnType: 'text', returnFields: [], usedBy: [], createdAt: '', updatedAt: '1', scope: 'public',
    }
    vi.spyOn(functionsApi, 'list').mockResolvedValue([saved, slugify])
    const create = vi.spyOn(functionsApi, 'create').mockResolvedValue({ ...slugify, id: 'fn-3', name: 'mySlugify', scope: 'own' })
    page('/functions/pub-1')

    expect(await screen.findByRole('list', { name: 'Installed' })).toHaveTextContent('fn.slugify')
    expect(screen.getByRole('list', { name: 'Your functions' })).toHaveTextContent('fn.withTax')
    expect(screen.getByText('Turns a title into a slug.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run test' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Make a copy' }))
    expect(await screen.findByRole('textbox', { name: 'Link name' })).toHaveValue('mySlugify')
    fireEvent.click(screen.getByRole('button', { name: 'Create function' }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toMatchObject({ name: 'mySlugify', script: 'return text.lower();' })
  })

  it('explores public functions and installs one', async () => {
    const pub = (id: string, name: string): ScriptFunction => ({
      id, name, description: `About ${name}.`, inputs: [{ name: 'text', type: 'text' }], script: 'return text;',
      returnType: 'text', returnFields: [], usedBy: [], createdAt: '', updatedAt: '1', scope: 'public',
    })
    vi.spyOn(functionsApi, 'list').mockResolvedValue([saved])
    vi.spyOn(functionsApi, 'explore').mockResolvedValue([{ function: pub('p1', 'slugify'), installed: true }, { function: pub('p2', 'maskEmail'), installed: false }])
    const install = vi.spyOn(functionsApi, 'install').mockResolvedValue({ message: 'Function installed.' })
    page('/functions')

    fireEvent.click(await screen.findByRole('button', { name: 'Explore public functions' }))
    expect(await screen.findByText('About maskEmail.')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Public functions' })).toHaveTextContent('fn.slugify')
    expect(screen.getByText('Installed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Install fn.slugify' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Install fn.maskEmail' }))
    await waitFor(() => expect(install).toHaveBeenCalledWith('p2'))
  })

  it('uninstalls an installed public function after saying what uses it', async () => {
    const slugify: ScriptFunction = {
      id: 'p1', name: 'slugify', description: null, inputs: [], script: 'return 1;', returnType: 'number',
      returnFields: [], usedBy: ['Blog'], createdAt: '', updatedAt: '1', scope: 'public',
    }
    vi.spyOn(functionsApi, 'list').mockResolvedValue([saved, slugify])
    const uninstall = vi.spyOn(functionsApi, 'uninstall').mockResolvedValue({ message: 'Function uninstalled.' })
    page('/functions/p1')
    fireEvent.click(await screen.findByRole('button', { name: 'Uninstall' }))
    expect(screen.getByText(/"Blog" run it/)).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Uninstall' }).at(-1)!)
    await waitFor(() => expect(uninstall).toHaveBeenCalledWith('p1'))
  })
})
