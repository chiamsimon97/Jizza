import { create } from 'zustand'
import type { JizzaForm } from '@/types'

interface FormRequest {
  form: JizzaForm
  fields: Record<string, unknown>
  /** True when Jizza filled the form in, so it can say so and highlight what it drafted. */
  byJizza: boolean
  nonce: number
}

interface FormState {
  request: FormRequest | null
  open: (form: JizzaForm, fields?: Record<string, unknown>, byJizza?: boolean) => void
  close: () => void
}

let nonce = 0

/** Which create form is open, and with what. Pages render the form; Jizza and buttons open it. */
export const useFormStore = create<FormState>((set) => ({
  request: null,
  open: (form, fields = {}, byJizza = false) => set({ request: { form, fields, byJizza, nonce: ++nonce } }),
  close: () => set({ request: null }),
}))
