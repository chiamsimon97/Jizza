import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { CheckCircle2 } from 'lucide-react'
import { formsApi } from '@/api/forms'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { FieldBlock } from '@/components/formbuilder/FieldInput'
import { flatten, isInput, type FormField } from '@/components/formbuilder/formModel'
import { JizzaLogo } from '@/components/brand/JizzaLogo'

/** A published form at its public link. Needs no account. */
export function PublicFormPage() {
  const { token } = useParams()
  const { data: form, isLoading, isError } = useQuery({ queryKey: ['public-form', token], queryFn: () => formsApi.publicForm(token!), retry: false })
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})
  const send = useMutation({ mutationFn: () => formsApi.submit(token!, values) })

  if (isLoading) return <Shell><div className="flex justify-center py-16"><Spinner /></div></Shell>
  if (isError || !form) {
    return <Shell><div className="py-16 text-center"><p className="text-lg font-semibold text-gray-900 dark:text-gray-100">This form isn’t available</p><p className="mt-1 text-sm text-gray-500">The link may be wrong, or the form is no longer published.</p></div></Shell>
  }
  if (send.isSuccess) {
    return (
      <Shell>
        <div className="py-12 text-center">
          <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-500" />
          <p className="mt-3 text-lg font-semibold text-gray-900 dark:text-gray-100">{send.data.message}</p>
          <button type="button" className="mt-4 text-sm text-brand-600 underline" onClick={() => { setValues({}); send.reset() }}>Send another response</button>
        </div>
      </Shell>
    )
  }

  const required = (f: FormField) => f.required && isInput(f)
  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    for (const f of flatten(form.fields)) {
      const v = values[f.name ?? '']
      const empty = v === undefined || v === null || v === '' || v === false || (Array.isArray(v) && !v.length)
        || (typeof v === 'object' && !Array.isArray(v) && v !== null && !('dataUrl' in v) && !Object.values(v).some(Boolean))
      if (required(f) && f.name && empty) missing[f.name] = 'This is required.'
    }
    setErrors(missing)
    if (Object.keys(missing).length) return
    send.mutate()
  }
  const block = (f: FormField) => (
    <FieldBlock key={f.id} field={f} value={values[f.name ?? f.id]} error={f.name ? errors[f.name] : undefined}
      onChange={v => { setValues(s => ({ ...s, [f.name ?? f.id]: v })); if (f.name && errors[f.name]) setErrors(({ [f.name!]: _, ...rest }) => rest) }} />
  )
  return (
    <Shell>
      <form onSubmit={submit} noValidate>
        <h1 className="text-2xl font-semibold text-gray-900 dark:text-gray-100">{form.name}</h1>
        {form.description && <p className="mt-1 whitespace-pre-wrap text-sm text-gray-500">{form.description}</p>}
        <div className="mt-6 space-y-5">
          {form.fields.map(f => (f.type === 'step_layout' ? <div key={f.id} className="space-y-5">{(f.children ?? []).map(block)}</div> : block(f)))}
        </div>
        {send.isError && <p role="alert" className="mt-4 whitespace-pre-wrap rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-300">{(send.error as Error).message}</p>}
        <Button type="submit" className="mt-6" loading={send.isPending}>{form.settings?.submitLabel || 'Submit'}</Button>
      </form>
    </Shell>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-gray-50 px-4 py-8 dark:bg-gray-950">
      <div className="mx-auto max-w-2xl rounded-2xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-800 dark:bg-gray-900 sm:p-8">{children}</div>
      <p className="mt-4 flex items-center justify-center gap-1.5 text-xs text-gray-400">Made with <JizzaLogo size={14} /></p>
    </div>
  )
}
