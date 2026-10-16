import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { CheckCircle2, Circle } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { servicesApi } from '@/api/services'
import { SectionHeader, errorText } from '../shared'
import type { TabProps } from '../ServiceEditorPage'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'

export function OverviewTab({ detail, refresh }: TabProps) {
  const { service } = detail
  const [name, setName] = useState(service.name)
  const [description, setDescription] = useState(service.description ?? '')
  const [category, setCategory] = useState(service.category ?? '')
  const [baseUrl, setBaseUrl] = useState(service.baseUrl ?? '')
  const save = useMutation({
    mutationFn: () => servicesApi.update(service.id, { name, description, category, baseUrl }),
    onSuccess: refresh,
  })

  const checklist = [
    { done: !!service.baseUrl, label: 'Base URL set' },
    { done: service.authScheme === 'NO_AUTH' || service.authScheme === 'CUSTOM' || service.credentialPresent, label: 'Signed in, or a credential added' },
    { done: detail.apis.length > 0, label: 'At least one API' },
    { done: detail.actions.some(a => a.enabled) || detail.triggers.some(t => t.enabled), label: 'An enabled action or trigger' },
    { done: detail.actions.some(a => a.agentIds.length > 0), label: 'An action given to an agent (optional)' },
  ]

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <SectionHeader title="Ready to publish?" description="Agents only see a service's actions once it is Live." />
        <Card>
          <ul className="flex flex-col gap-2">
            {checklist.map(item => (
              <li key={item.label} className="flex items-center gap-2 text-sm">
                {item.done
                  ? <CheckCircle2 className="h-4 w-4 text-green-500" />
                  : <Circle className="h-4 w-4 text-gray-300 dark:text-gray-600" />}
                <span className={item.done ? 'text-gray-900 dark:text-gray-100' : 'text-gray-500 dark:text-gray-400'}>{item.label}</span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <form onSubmit={submit}>
        <SectionHeader title="Details" action={<Can permission={PERMISSIONS.CONNECTOR_MODIFY} mode="disable"><Button size="sm" type="submit" loading={save.isPending}>Save</Button></Can>} />
        <Card className="flex flex-col gap-4">
          <Input label="Name" required value={name} onChange={e => setName(e.target.value)} />
          <Field label="Description" hint="Agents read this to decide when to use the service.">
            <textarea className={fieldClass} rows={3} value={description} onChange={e => setDescription(e.target.value)} />
          </Field>
          <Input label="Category" value={category} onChange={e => setCategory(e.target.value)} />
          <Input label="Base URL" value={baseUrl} onChange={e => setBaseUrl(e.target.value)}
            placeholder="https://api.example.com/v1" hint="Must be https. May use variables, e.g. https://{{var.region}}.example.com" />
          {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
          {save.isSuccess && <p className="text-sm text-green-600">Saved.</p>}
        </Card>
      </form>
    </div>
  )
}
