import { api } from '@/lib/api'
import type { ValueType } from '@/components/workflow/formula/functions'
import type { FormulaPreview } from './formula'

/** One input of a function: what a call passes, in this order. */
export interface FunctionInput { name: string; type: ValueType; required?: boolean; description?: string; default?: unknown }

/** One key of a map a function returns; nested keys are dotted, such as customer.email. */
export interface ReturnField { path: string; type: ValueType }

/** A function under Functions, which workflows run from a Script step and other functions call as fn.<name>(…). */
export interface ScriptFunction {
  id: string
  name: string
  description: string | null
  inputs: FunctionInput[]
  script: string
  returnType: ValueType
  returnFields: ReturnField[]
  /** The workflows that run it. */
  usedBy: string[]
  createdAt: string
  updatedAt: string
}

export type ScriptFunctionDraft = Pick<ScriptFunction, 'name' | 'inputs' | 'script' | 'returnType' | 'returnFields'> & { description?: string | null }

export const functionsApi = {
  list: () => api.get<ScriptFunction[]>('/api/functions'),
  get: (id: string) => api.get<ScriptFunction>(`/api/functions/${id}`),
  create: (body: ScriptFunctionDraft) => api.post<ScriptFunction>('/api/functions', body),
  update: (id: string, body: ScriptFunctionDraft) => api.put<ScriptFunction>(`/api/functions/${id}`, body),
  remove: (id: string) => api.delete<void>(`/api/functions/${id}`),
  /** Runs the function as the editor holds it, saved or not, with its inputs by name. */
  test: (fn: ScriptFunctionDraft, args: Record<string, unknown>) =>
    api.post<FormulaPreview>('/api/functions/test', { function: fn, args }),
}
