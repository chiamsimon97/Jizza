import { api } from '@/lib/api'

/** What a formula gave against sample data, or why it failed. */
export interface FormulaPreview { value: unknown; type: 'text' | 'number' | 'boolean' | 'list' | 'map' | 'empty' | null; error: string | null }

export const formulaApi = {
  /** Runs a formula in the engine's sandbox over sample run data ({ input, nodes, vars }). */
  preview: (formula: string, template: boolean, data: Record<string, unknown>) =>
    api.post<FormulaPreview>('/api/formula/preview', { formula, template, data }),
}
