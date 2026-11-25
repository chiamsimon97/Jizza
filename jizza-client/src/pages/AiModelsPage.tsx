import { type FormEvent, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Cpu, ExternalLink, Eye, EyeOff, RefreshCw, Trash2 } from 'lucide-react'
import {
  aiModelsApi, priceHint, type AiModelsOverview, type ProviderKind, type ProviderModel, type ProviderView,
} from '@/api/aiModels'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { Switch } from '@/components/ui/Switch'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

export const AI_MODELS_KEY = ['ai-models'] as const

/**
 * A choice of models: connect an AI provider with your own key, then pick its models in the chat
 * box or for an agent. A saved key is never shown again, only its last four characters.
 */
export function AiModelsPage() {
  const { data, isLoading, error } = useQuery({ queryKey: AI_MODELS_KEY, queryFn: aiModelsApi.overview })

  return (
    <div className="page">
      <PageHeader icon={Cpu} title="AI models"
        description="Connect OpenAI, Gemini, OpenRouter, Ollama or your own Anthropic key, then pick their models in the chat box or for an agent." />
      <div className="grid max-w-3xl grid-cols-[minmax(0,1fr)] gap-4">
        {isLoading && <Card padding="lg"><Spinner /></Card>}
        {error && <Card padding="lg"><p className="text-sm text-red-600">{errorText(error)}</p></Card>}
        {data && <Providers data={data} />}
      </div>
    </div>
  )
}

function Providers({ data }: { data: AiModelsOverview }) {
  const platform = data.models.filter((m) => !m.ownKey)
  return (
    <>
      <Card padding="lg">
        <h2 className="font-semibold text-gray-900 dark:text-gray-100">Jizza's models</h2>
        {platform.length === 0
          ? <p className="mt-1 text-sm text-gray-500">This server has no AI key of its own, so connect a provider below to chat.</p>
          : (
            <ul className="mt-2 space-y-1 text-sm">
              {platform.map((m) => (
                <li key={m.ref} className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium text-gray-800 dark:text-gray-200">{m.label}</span>
                  {m.note && <span className="text-gray-500">{m.note}</span>}
                  {priceHint(m) && <span className="text-xs text-gray-400">{priceHint(m)}</span>}
                </li>
              ))}
            </ul>
          )}
        <p className="mt-2 text-xs text-gray-500">
          Leave the chat box on Auto and Jizza picks one of these for each message.
        </p>
      </Card>
      {data.kinds.map((kind) => (
        <ProviderCard key={kind.provider} kind={kind}
          connection={data.connected.find((c) => c.provider === kind.provider)} />
      ))}
    </>
  )
}

function ProviderCard({ kind, connection }: { kind: ProviderKind; connection?: ProviderView }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [key, setKey] = useState('')
  const [address, setAddress] = useState(connection?.baseUrl ?? kind.defaultBaseUrl ?? '')
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [showModels, setShowModels] = useState(false)

  const refresh = () => {
    qc.invalidateQueries({ queryKey: AI_MODELS_KEY })
    qc.invalidateQueries({ queryKey: ['ai-models', 'picker'] })
  }

  async function run(what: string, action: () => Promise<unknown>) {
    setBusy(what)
    setMessage('')
    try {
      await action()
      refresh()
      return true
    } catch (err) {
      setMessage(errorText(err))
      return false
    } finally {
      setBusy(null)
    }
  }

  const asksAddress = kind.needsAddress || kind.provider === 'ollama' || kind.provider === 'custom'

  async function save(e: FormEvent) {
    e.preventDefault()
    const body: { baseUrl?: string; apiKey?: string } = {}
    if (asksAddress && address.trim()) body.baseUrl = address.trim()
    if (key.trim()) body.apiKey = key.trim()
    if (await run('save', () => aiModelsApi.connect(kind.provider, body))) {
      setKey('')
      setEditing(false)
      setShowModels(true)
    }
  }

  const connected = connection != null

  return (
    <Card padding="lg">
      <div className="flex flex-wrap items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-semibold text-gray-900 dark:text-gray-100">{kind.name}</h2>
            {connected && (connection.enabled
              ? <Badge variant="success">Connected</Badge>
              : <Badge>Off</Badge>)}
            {connection?.lastError && <Badge variant="warning">Needs attention</Badge>}
          </div>
          {kind.note && <p className="mt-0.5 text-sm text-gray-500">{kind.note}</p>}
          {connected && (
            <p className="mt-1 text-xs text-gray-500">
              {connection.keyHint && <>Key {connection.keyHint} · </>}
              {connection.baseUrl && <>{connection.baseUrl} · </>}
              {connection.models.length} model{connection.models.length === 1 ? '' : 's'}
            </p>
          )}
          {connection?.lastError && <p className="mt-1 text-sm text-amber-700 dark:text-amber-400">{connection.lastError}</p>}
        </div>
        {connected && (
          <Switch checked={connection.enabled} label={`${kind.name} on`} disabled={busy != null}
            onChange={(on) => run('enable', () => aiModelsApi.setEnabled(kind.provider, on))} />
        )}
      </div>

      {!connected && !editing && (
        <div className="mt-3">
          <Button size="sm" onClick={() => setEditing(true)}>Connect</Button>
        </div>
      )}

      {editing && (
        <form onSubmit={save} className="mt-3 space-y-3">
          {kind.needsKey && (
            <Input label={connected ? 'New API key (leave empty to keep the saved one)' : 'API key'} type="password"
              autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)}
              hint="Saved encrypted and never shown again." />
          )}
          {asksAddress && (
            <Input label="Address" value={address} onChange={(e) => setAddress(e.target.value)}
              placeholder={kind.defaultBaseUrl ?? 'https://…/v1'}
              hint={kind.provider === 'ollama' ? 'Where Ollama answers, as the Jizza server sees it.' : undefined} />
          )}
          {kind.keyLink && (
            <a href={kind.keyLink} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 text-sm text-brand-600 hover:underline">
              Get a key <ExternalLink className="h-3.5 w-3.5" />
            </a>
          )}
          <div className="flex gap-2">
            <Button type="submit" size="sm" loading={busy === 'save'}
              disabled={kind.needsKey && !connected && !key.trim()}>
              {connected ? 'Save' : 'Check and connect'}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => { setEditing(false); setKey(''); setMessage('') }}>
              Cancel
            </Button>
          </div>
        </form>
      )}

      {connected && !editing && (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" onClick={() => setShowModels((v) => !v)}>
            {showModels ? 'Hide models' : 'Models'}
          </Button>
          <Button size="sm" variant="secondary" loading={busy === 'refresh'}
            onClick={() => run('refresh', () => aiModelsApi.refresh(kind.provider))}>
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>Change key</Button>
          <Button size="sm" variant="ghost" loading={busy === 'remove'}
            onClick={() => run('remove', () => aiModelsApi.remove(kind.provider))}>
            <Trash2 className="h-3.5 w-3.5" /> Remove
          </Button>
        </div>
      )}

      {message && <p role="alert" className="mt-2 text-sm text-red-600">{message}</p>}

      {connected && showModels && !editing && (
        <ModelList provider={kind.provider} models={connection.models}
          onChange={(id, hidden) => run('model', () => aiModelsApi.updateModel(kind.provider, id, { hidden }))} />
      )}
    </Card>
  )
}

function ModelList({ provider, models, onChange }: {
  provider: string
  models: ProviderModel[]
  onChange: (id: string, hidden: boolean) => void
}) {
  if (models.length === 0) {
    return <p className="mt-3 text-sm text-gray-500">No chat models came back. Try Refresh.</p>
  }
  return (
    <ul className="mt-3 divide-y divide-gray-100 rounded-lg border border-gray-100 dark:divide-gray-800 dark:border-gray-800"
      aria-label={`${provider} models`}>
      {models.map((m) => (
        <li key={m.id} className={`flex items-center gap-3 px-3 py-2 text-sm ${m.hidden ? 'opacity-60' : ''}`}>
          <div className="min-w-0 flex-1">
            <div className="font-medium text-gray-800 dark:text-gray-200">{m.label}</div>
            <div className="truncate text-xs text-gray-500">
              {[m.id, !m.tools && 'no tools', m.vision && 'photos', m.pdf && 'PDFs', priceHint(m)]
                .filter(Boolean).join(' · ')}
            </div>
          </div>
          <button type="button" className="rounded p-1 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"
            aria-label={m.hidden ? `Show ${m.label} in the picker` : `Hide ${m.label} from the picker`}
            title={m.hidden ? 'Show in the picker' : 'Hide from the picker'}
            onClick={() => onChange(m.id, !m.hidden)}>
            {m.hidden ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        </li>
      ))}
    </ul>
  )
}
