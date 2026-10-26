/**
 * Functions, the separate module: what the editor works out on its own before the server checks
 * again — a draft's problems, test arguments read from text, and a map's keys from a test result.
 */
import type { FunctionInput, ReturnField, ScriptFunction, ScriptFunctionDraft } from '@/api/functions'
import { FORMULA_FUNCTIONS } from '@/components/workflow/formula/functions'
import type { ScriptFunctionInfo } from '@/components/workflow/formula/script'
import { typeOfValue } from '@/components/workflow/dataMapping'
import { FIELD_NAME } from '@/components/workflow/moduleContract'

/** Names a function or an input can't have: the script's own words and the built-in functions. */
const RESERVED = new Set(['fn', 'call', 'fmt', 'var', 'if', 'else', 'for', 'while', 'do', 'return', 'function', 'new',
  'true', 'false', 'null', 'empty', 'size', 'and', 'or', 'not', 'eq', 'ne', 'lt', 'gt', 'le', 'ge', 'div', 'mod',
  'break', 'continue', 'input', 'nodes', 'vars'])
const BUILT_IN = new Set(FORMULA_FUNCTIONS.map(f => f.name))

export const KEY_PATH = /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){0,4}$/

export const emptyDraft = (): ScriptFunctionDraft => ({
  name: '', description: '', inputs: [], script: '', returnType: 'any', returnFields: [],
})

/** A copy of a public function to change as the person's own: the same, under a name of its own. */
export const copyOf = (f: ScriptFunction, taken: string[]): ScriptFunctionDraft => {
  const base = `my${f.name.charAt(0).toUpperCase()}${f.name.slice(1)}`
  let name = base
  for (let n = 2; taken.includes(name); n++) name = `${base}${n}`
  return { ...draftOf(f), name, description: f.description ?? '' }
}

export const draftOf = (f: ScriptFunction): ScriptFunctionDraft => ({
  name: f.name, description: f.description ?? '', inputs: f.inputs, script: f.script, returnType: f.returnType, returnFields: f.returnFields ?? [],
})

/** What a script needs to know to call a saved function. */
export const infoOf = (f: Pick<ScriptFunction, 'name' | 'inputs' | 'returnType' | 'description'>): ScriptFunctionInfo => ({
  name: f.name, inputs: f.inputs.map(i => ({ name: i.name, type: i.type })), returnType: f.returnType, description: f.description ?? undefined,
})

/** Problems with a draft, by field: name, inputs.<i>.name, script, returnFields.<i>.path. */
export function draftIssues(d: ScriptFunctionDraft, taken: string[] = [], publicNames: string[] = []): Record<string, string> {
  const out: Record<string, string> = {}
  const name = d.name.trim()
  if (!FIELD_NAME.test(name)) out.name = 'Use letters, digits and _, starting with a letter.'
  else if (RESERVED.has(name) || BUILT_IN.has(name)) out.name = `${name} is a built-in name; choose another.`
  else if (publicNames.includes(name)) out.name = `fn.${name} is a public function; choose another name.`
  else if (taken.includes(name)) out.name = `You already have a function called ${name}.`
  const seen = new Set<string>()
  d.inputs.forEach((i, n) => {
    if (!FIELD_NAME.test(i.name)) out[`inputs.${n}.name`] = 'Letters, digits and _, starting with a letter.'
    else if (RESERVED.has(i.name)) out[`inputs.${n}.name`] = `An input can't be called ${i.name}.`
    else if (seen.has(i.name)) out[`inputs.${n}.name`] = `Two inputs are called ${i.name}.`
    seen.add(i.name)
  })
  if (!d.script.trim()) out.script = 'Write the script.'
  if (d.returnType === 'map') {
    const keys = new Set<string>()
    d.returnFields.forEach((f, n) => {
      if (!KEY_PATH.test(f.path)) out[`returnFields.${n}.path`] = 'Letters, digits and _, with dots between nested keys.'
      else if (keys.has(f.path)) out[`returnFields.${n}.path`] = `${f.path} is listed twice.`
      keys.add(f.path)
    })
  }
  return out
}

/** What a draft sends: names trimmed, a map's keys only when it returns a map. */
export function cleanDraft(d: ScriptFunctionDraft): ScriptFunctionDraft {
  return {
    ...d,
    name: d.name.trim(),
    description: d.description?.trim() || null,
    inputs: d.inputs.map(i => ({ ...i, name: i.name.trim() })),
    returnFields: d.returnType === 'map' ? d.returnFields.map(f => ({ ...f, path: f.path.trim() })) : [],
  }
}

/**
 * A test argument typed into the editor, as the function will read it: JSON where it parses
 * (numbers, true/false, lists, maps), the text itself otherwise. Empty means not given.
 */
export function argOf(text: string, input: FunctionInput): unknown {
  const t = text.trim()
  if (!t) return undefined
  if (input.type === 'text' || input.type === 'date') return text
  try {
    return JSON.parse(t)
  } catch {
    return text
  }
}

/** A map's keys, nested ones dotted, three levels deep, with their types: what "Use these keys" fills in. */
export function keysOf(value: unknown, depth = 3, prefix = ''): ReturnField[] {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth === 0) return []
  return Object.entries(value as Record<string, unknown>).slice(0, 50).flatMap(([k, v]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) return []
    const path = prefix ? `${prefix}.${k}` : k
    return [{ path, type: typeOfValue(v) }, ...keysOf(v, depth - 1, path)]
  })
}

/** Keys from a test merged into the ones listed: new keys added, the types of listed ones kept. */
export function mergeKeys(listed: ReturnField[], found: ReturnField[]): ReturnField[] {
  const have = new Set(listed.map(f => f.path))
  return [...listed, ...found.filter(f => !have.has(f.path))]
}

/** A function's result as later workflow steps map it: a map's keys, or result. */
export function resultFields(f: Pick<ScriptFunction, 'returnType' | 'returnFields'>): ReturnField[] {
  return f.returnType === 'map' ? f.returnFields : [{ path: 'result', type: f.returnType }]
}
