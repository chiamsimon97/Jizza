import { api } from '@/lib/api'

/** A model the person can pick, from the platform (on Jizza's key) or a provider on their own key. */
export interface ModelInfo {
  ref: string
  provider: string
  label: string
  note?: string | null
  ownKey: boolean
  tools: boolean
  vision: boolean
  pdf: boolean
  contextTokens?: number | null
  inputPerMillion?: number | null
  outputPerMillion?: number | null
}

/** A provider that can be connected, and what it needs. */
export interface ProviderKind {
  provider: string
  name: string
  note?: string | null
  needsKey: boolean
  needsAddress: boolean
  defaultBaseUrl?: string | null
  keyLink?: string | null
}

export interface ProviderModel {
  id: string
  ref: string
  label: string
  tools: boolean
  vision: boolean
  pdf: boolean
  contextTokens?: number | null
  inputPerMillion?: number | null
  outputPerMillion?: number | null
  hidden: boolean
}

/** A connection, shown without its key: only the key's last four characters come back. */
export interface ProviderView {
  id: string
  provider: string
  name: string
  baseUrl?: string | null
  keyHint?: string | null
  enabled: boolean
  modelsRefreshedAt?: string | null
  lastError?: string | null
  models: ProviderModel[]
}

export interface AiModelsOverview {
  kinds: ProviderKind[]
  connected: ProviderView[]
  models: ModelInfo[]
}

export interface ModelPatch {
  hidden?: boolean
  inputPerMillion?: number | null
  outputPerMillion?: number | null
  tools?: boolean
  vision?: boolean
  pdf?: boolean
}

/** How hard a reply should think: quick and cheap, the usual, or as deep as it goes. */
export type Depth = 'quick' | 'normal' | 'deep'

/** A chat's picked model (none lets Jizza decide) and how hard it thinks. */
export interface ChatChoice {
  model?: string | null
  depth?: Depth | null
}

export const aiModelsApi = {
  overview: () => api.get<AiModelsOverview>('/api/ai-models'),

  models: () => api.get<ModelInfo[]>('/api/ai-models/models'),

  /** Connects a provider or replaces its key or address; a blank key keeps the saved one. */
  connect: (provider: string, body: { baseUrl?: string; apiKey?: string }) =>
    api.put<ProviderView>(`/api/ai-models/providers/${encodeURIComponent(provider)}`, body),

  refresh: (provider: string) =>
    api.post<ProviderView>(`/api/ai-models/providers/${encodeURIComponent(provider)}/refresh`, {}),

  setEnabled: (provider: string, enabled: boolean) =>
    api.patch<ProviderView>(`/api/ai-models/providers/${encodeURIComponent(provider)}`, { enabled }),

  remove: (provider: string) => api.delete<void>(`/api/ai-models/providers/${encodeURIComponent(provider)}`),

  updateModel: (provider: string, id: string, patch: ModelPatch) =>
    api.patch<ProviderView>(
      `/api/ai-models/providers/${encodeURIComponent(provider)}/model?id=${encodeURIComponent(id)}`, patch),
}

const PROVIDER_NAMES: Record<string, string> = {
  openai: 'OpenAI',
  anthropic: 'Anthropic',
  gemini: 'Google Gemini',
  openrouter: 'OpenRouter',
  ollama: 'Ollama',
  custom: 'Your own server',
}

/** The group a model is listed under in the picker. */
export function modelGroup(m: Pick<ModelInfo, 'ownKey' | 'provider'>): string {
  if (!m.ownKey) return 'Jizza'
  return `${PROVIDER_NAMES[m.provider] ?? m.provider} (your key)`
}

/** Models in picker order, grouped: Jizza's own first, then each provider as connected. */
export function groupModels(models: ModelInfo[]): Array<{ group: string; models: ModelInfo[] }> {
  const groups: Array<{ group: string; models: ModelInfo[] }> = []
  for (const m of models) {
    const name = modelGroup(m)
    let g = groups.find((x) => x.group === name)
    if (!g) groups.push((g = { group: name, models: [] }))
    g.models.push(m)
  }
  return groups
}

function dollars(n: number): string {
  const v = Number(n)
  if (v === 0) return '$0'
  if (v < 1) return `$${v.toFixed(2)}`
  return `$${Number.isInteger(v) ? v : v.toFixed(2)}`
}

/** A short price hint, such as "$3 in / $15 out per 1M tokens"; empty when the price isn't known. */
export function priceHint(m: Pick<ModelInfo, 'inputPerMillion' | 'outputPerMillion'>): string {
  if (m.inputPerMillion == null || m.outputPerMillion == null) return ''
  if (Number(m.inputPerMillion) === 0 && Number(m.outputPerMillion) === 0) return 'Free'
  return `${dollars(m.inputPerMillion)} in / ${dollars(m.outputPerMillion)} out per 1M tokens`
}

/** Can this model read what is attached: photos need vision, PDFs need PDF reading. */
export function canRead(m: Pick<ModelInfo, 'vision' | 'pdf'>, attachments: { image: boolean; pdf: boolean }): boolean {
  return (!attachments.image || m.vision) && (!attachments.pdf || m.pdf)
}

/** A cost in dollars as people read it: "<$0.01" for tiny ones. */
export function formatCost(cost: number | null | undefined): string {
  if (cost == null) return ''
  if (cost === 0) return '$0'
  if (cost < 0.01) return '<$0.01'
  return `$${cost.toFixed(2)}`
}
