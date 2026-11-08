import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Download, Inbox } from 'lucide-react'
import { formsApi, type FormDto, type FormSubmission } from '@/api/forms'
import { EmptyState, LoadError } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'
import { flatten, isInput, COMPUTED_TYPES, FILE_TYPES, type FormField } from './formModel'

/** What people sent, newest first, with the workflow runs each one started. */
export function FormSubmissions({ form }: { form: FormDto }) {
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['form-submissions', form.id], queryFn: () => formsApi.submissions(form.id) })
  const columns = flatten(form.fields).filter(f => !f.pending && (isInput(f) || COMPUTED_TYPES.has(f.type)) && f.name)
  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (isError) return <LoadError what="the submissions" onRetry={() => void refetch()} />
  if (!data?.length) return <EmptyState icon={Inbox} title="No submissions yet" description={form.status === 'published' ? 'Share the form’s link; what people send shows up here.' : 'Publish the form and share its link; what people send shows up here.'} />
  return (
    <div className="overflow-x-auto rounded-xl border border-gray-200 dark:border-gray-800">
      <table className="w-full min-w-[640px] text-left text-sm">
        <thead className="bg-gray-50 text-xs text-gray-500 dark:bg-gray-900">
          <tr>
            <th className="px-3 py-2 font-medium">#</th>
            <th className="px-3 py-2 font-medium">Sent</th>
            {columns.map(c => <th key={c.id} className="px-3 py-2 font-medium">{c.label}</th>)}
            <th className="px-3 py-2 font-medium">Workflows</th>
          </tr>
        </thead>
        <tbody>
          {data.map(s => (
            <tr key={s.id} className="border-t border-gray-100 align-top dark:border-gray-800">
              <td className="px-3 py-2 text-gray-500">{s.number}</td>
              <td className="whitespace-nowrap px-3 py-2 text-gray-500">{new Date(s.submittedAt).toLocaleString()}</td>
              {columns.map(c => <td key={c.id} className="max-w-xs px-3 py-2 text-gray-800 dark:text-gray-200"><Answer form={form} submission={s} field={c} /></td>)}
              <td className="px-3 py-2 text-xs">
                {s.runs.length === 0 && <span className="text-gray-400">None</span>}
                {s.runs.map(r => (
                  <div key={r.workflowId}>
                    {r.runId
                      ? <Link className="text-brand-600 hover:underline" to={`/runs?run=${r.runId}`}>{r.workflowName ?? 'Run'}</Link>
                      : <span className="text-amber-700 dark:text-amber-300" title={r.error}>{r.workflowName ?? 'Workflow'}: {r.error ?? 'not started'}</span>}
                  </div>
                ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Answer({ form, submission, field }: { form: FormDto; submission: FormSubmission; field: FormField }) {
  const v = submission.data[field.name!]
  if (v === undefined || v === null || v === '') return <span className="text-gray-300">—</span>
  if (FILE_TYPES.has(field.type) && typeof v === 'object') {
    const file = v as { name?: string; size?: number }
    const download = async () => {
      const blob = await formsApi.file(form.id, submission.id, field.name!)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = file.name ?? field.name!
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
    return <button type="button" onClick={() => void download()} className="inline-flex items-center gap-1 text-brand-600 hover:underline"><Download className="h-3.5 w-3.5" />{file.name ?? 'File'}</button>
  }
  return <span className="line-clamp-3 break-words">{display(v, field)}</span>
}

/** An answer as text. Rich text shows without its markup. */
export function display(v: unknown, field?: FormField): string {
  if (Array.isArray(v)) return v.map(x => (typeof x === 'object' ? Object.values(x as object).join(', ') : String(x))).join(field?.type === 'subform' ? '; ' : ', ')
  if (typeof v === 'boolean') return v ? 'Yes' : 'No'
  if (v && typeof v === 'object') return Object.values(v as object).filter(Boolean).join(' ')
  if (field?.type === 'rich_text') {
    // Parsed, never attached: a parsed document runs no script and loads nothing.
    return new DOMParser().parseFromString(String(v), 'text/html').body.textContent ?? ''
  }
  return String(v)
}
