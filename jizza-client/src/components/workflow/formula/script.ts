/**
 * Jizza script: what a Script step runs. The formula language with a few lines of logic around it
 * (var, if/else, for over a list, return), run by the engine's sandbox (JexlSandbox.runScript). The
 * script reads its inputs by name and returns a map holding its outputs.
 *
 * This is the CodeMirror side: colours through a StreamLanguage, suggestions (inputs, the script's
 * own variables, functions, keywords), docs on hover, and checks as you type.
 */
import type { Completion, CompletionContext, CompletionResult } from '@codemirror/autocomplete'
import { snippetCompletion } from '@codemirror/autocomplete'
import { HighlightStyle, StreamLanguage, syntaxHighlighting, type StringStream } from '@codemirror/language'
import type { Diagnostic } from '@codemirror/lint'
import { hoverTooltip, type EditorView } from '@codemirror/view'
import { tags } from '@lezer/highlight'
import { FORMULA_FUNCTIONS, functionNamed, signature, type ValueType } from './functions'

/** Words with a meaning of their own in a script. */
export const SCRIPT_KEYWORDS = ['var', 'if', 'else', 'for', 'return', 'and', 'or', 'not', 'true', 'false', 'null', 'empty', 'size']

/** Words the engine refuses in a script. */
const REFUSED = new Set(['while', 'do', 'function', 'new'])

const KEYWORDS = new Set(SCRIPT_KEYWORDS.filter(k => !['true', 'false', 'null'].includes(k)))
const ATOMS = new Set(['true', 'false', 'null'])

interface State { inComment: boolean; afterDot: boolean }

const parser = {
  name: 'jizza-script',
  startState: (): State => ({ inComment: false, afterDot: false }),
  token(stream: StringStream, state: State): string | null {
    if (state.inComment) {
      if (stream.skipTo('*/')) { stream.next(); stream.next(); state.inComment = false } else stream.skipToEnd()
      return 'comment'
    }
    if (stream.eatSpace()) return null
    if (stream.match('//') || stream.match('##')) { stream.skipToEnd(); return 'comment' }
    if (stream.match('/*')) { state.inComment = true; return 'comment' }
    const afterDot = state.afterDot
    state.afterDot = false
    const ch = stream.peek()
    if (ch === '"' || ch === "'") {
      stream.next()
      let escaped = false
      for (let c = stream.next(); c != null; c = stream.next()) {
        if (c === ch && !escaped) break
        escaped = !escaped && c === '\\'
      }
      return 'string'
    }
    if (stream.match(/^\d[\d_]*(\.\d+)?/)) return 'number'
    if (stream.match(/^[A-Za-z_$][\w$]*/)) {
      const word = stream.current()
      const call = /^\s*\(/.test(stream.string.slice(stream.pos))
      if (call && functionNamed(word)) return 'builtin'
      if (afterDot) return 'propertyName'
      if (ATOMS.has(word)) return 'atom'
      if (KEYWORDS.has(word) || REFUSED.has(word)) return 'keyword'
      return 'variableName'
    }
    if (stream.eat('.')) { state.afterDot = true; return 'punctuation' }
    if (stream.match(/^(==|!=|<=|>=|&&|\|\||\?\?|=~|!~|[-+*/%<>=!?:])/)) return 'operator'
    stream.next()
    return 'punctuation'
  },
  languageData: { commentTokens: { line: '//', block: { open: '/*', close: '*/' } }, closeBrackets: { brackets: ['(', '[', '{', "'", '"'] } },
}

export const scriptLanguage = StreamLanguage.define(parser)

const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: '#7c3aed', fontWeight: '600' },
  { tag: tags.string, color: '#047857' },
  { tag: tags.number, color: '#b45309' },
  { tag: tags.atom, color: '#be185d' },
  { tag: tags.comment, color: 'rgb(var(--gray-400))', fontStyle: 'italic' },
  { tag: tags.standard(tags.variableName), color: '#7c3aed' },
  { tag: tags.propertyName, color: 'rgb(var(--brand-600))' },
  { tag: tags.variableName, color: 'rgb(var(--fx-strong))' },
  { tag: tags.operator, color: 'rgb(var(--gray-500))' },
])

export const scriptHighlighting = syntaxHighlighting(highlight)

/** A value the script reads: one of its inputs, by name. */
export interface ScriptInput { name: string; type: ValueType }

/** One of the person's saved functions, which a script calls as fn.<name>(…), its inputs in order. */
export interface ScriptFunctionInfo { name: string; inputs: ScriptInput[]; returnType: ValueType; description?: string }

/** How a saved function reads: fn.orderTotal(lines, rate) → map. */
export const functionSignature = (f: ScriptFunctionInfo) => `fn.${f.name}(${f.inputs.map(i => i.name).join(', ')}) → ${f.returnType}`

/** How many arguments a call passes: commas at the top level of the brackets after {@code open}. */
function argumentCount(code: string, open: number): number {
  let depth = 0
  let count = 0
  let any = false
  for (let i = open; i < code.length; i++) {
    const c = code[i]
    if (c === '(' || c === '[' || c === '{') { depth++; continue }
    if (c === ')' || c === ']' || c === '}') {
      if (--depth === 0) return any ? count + 1 : 0
      continue
    }
    if (depth === 1 && c === ',') count++
    else if (depth === 1 && !/\s/.test(c)) any = true
  }
  return any ? count + 1 : 0
}

/** The script with comments and the inside of strings blanked out, same length, for checks. */
function codeOnly(doc: string): string {
  return doc.replace(/\/\*[\s\S]*?(\*\/|$)|\/\/[^\n]*|##[^\n]*|'(?:[^'\\\n]|\\.)*'?|"(?:[^"\\\n]|\\.)*"?/g,
    m => (m.startsWith('/') || m.startsWith('#') ? ' '.repeat(m.length) : m[0] + ' '.repeat(Math.max(0, m.length - 2)) + (m.length > 1 ? m[m.length - 1] : '')))
}

/** The variables a script declares with var. */
export function localsOf(doc: string): string[] {
  return [...new Set([...codeOnly(doc).matchAll(/\bvar\s+([A-Za-z_$][\w$]*)/g)].map(m => m[1]))]
}

/** Problems a script has before it runs: refused loops, unknown functions, brackets that don't close. */
export function scriptProblems(doc: string, inputs: ScriptInput[], functions?: ScriptFunctionInfo[]): Diagnostic[] {
  const code = codeOnly(doc)
  const out: Diagnostic[] = []
  for (const m of code.matchAll(/\b(while|do|function|new)\b/g)) {
    out.push({ from: m.index!, to: m.index! + m[0].length, severity: 'error',
      message: m[1] === 'while' || m[1] === 'do' ? 'Loop over a list with for (var item : list) { … }; while and do are not allowed' : `${m[1]} is not allowed in a script` })
  }
  const known = new Set([...inputs.map(i => i.name), ...localsOf(doc)])
  for (const m of code.matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)) {
    const name = m[1]
    const from = m.index!
    if (/\bfn\s*\.\s*$/.test(code.slice(0, from))) {
      // fn.<name>(…): one of the person's own functions, checked when the list of them is known.
      const f = functions?.find(x => x.name === name)
      if (functions && !f) out.push({ from, to: from + name.length, severity: 'error', message: `There is no function called fn.${name}` })
      if (f) {
        const given = argumentCount(code, code.indexOf('(', from + name.length))
        if (given > f.inputs.length) {
          out.push({ from, to: from + name.length, severity: 'error', message: `fn.${name} takes ${f.inputs.length} input${f.inputs.length === 1 ? '' : 's'}${f.inputs.length ? ` (${f.inputs.map(i => i.name).join(', ')})` : ''} but is given ${given}` })
        }
      }
      continue
    }
    if (['if', 'for', 'while', 'function', 'return', 'empty', 'size'].includes(name) || functionNamed(name)) continue
    out.push({ from, to: from + name.length, severity: known.has(name) ? 'warning' : 'error', message: `There is no function called ${name}` })
  }
  const stack: Array<{ ch: string; at: number }> = []
  const pairs: Record<string, string> = { ')': '(', ']': '[', '}': '{' }
  for (let i = 0; i < code.length; i++) {
    const c = code[i]
    if (c === '(' || c === '[' || c === '{') stack.push({ ch: c, at: i })
    else if (c in pairs) {
      const top = stack.pop()
      if (!top || top.ch !== pairs[c]) { out.push({ from: i, to: i + 1, severity: 'error', message: `This ${c} has nothing to close` }); break }
    }
  }
  for (const open of stack) out.push({ from: open.at, to: open.at + 1, severity: 'error', message: `This ${open.ch} is never closed` })
  if (!/\breturn\b/.test(code) && code.trim()) {
    out.push({ from: 0, to: 0, severity: 'warning', message: "End with return {'name': value} to give the step's outputs" })
  }
  return out
}

const KEYWORD_SNIPPETS: Completion[] = [
  snippetCompletion('var ${name} = ${value};', { label: 'var', detail: 'a variable', type: 'keyword' }),
  snippetCompletion('if (${condition}) {\n\t${}\n}', { label: 'if', detail: 'if … { }', type: 'keyword' }),
  snippetCompletion('if (${condition}) {\n\t${}\n} else {\n\t\n}', { label: 'if else', detail: 'if … else', type: 'keyword' }),
  snippetCompletion('for (var ${item} : ${list}) {\n\t${}\n}', { label: 'for', detail: 'each item of a list', type: 'keyword' }),
  snippetCompletion("return {'${name}': ${value}};", { label: 'return', detail: 'give the outputs', type: 'keyword' }),
]

const TYPE_RANK: Record<string, number> = { variable: 3, keyword: 1, function: 0 }

/** Suggestions: after a dot, functions to chain; elsewhere inputs, the script's variables, keywords and functions. */
export function scriptCompletions(inputs: () => ScriptInput[], functions: () => ScriptFunctionInfo[] = () => []) {
  return (ctx: CompletionContext): CompletionResult | null => {
    const word = ctx.matchBefore(/[A-Za-z_$][\w$]*/)
    const line = ctx.state.doc.lineAt(ctx.pos)
    const before = line.text.slice(0, (word?.from ?? ctx.pos) - line.from)
    if (/\/\/|##/.test(before) || (((before.match(/'/g) ?? []).length % 2) === 1)) return null
    if (/(^|[^\w$.])fn\s*\.\s*$/.test(before)) {
      return {
        from: word?.from ?? ctx.pos,
        options: functions().map(f => ({
          label: f.name, type: 'function', detail: `→ ${f.returnType}`, info: [functionSignature(f), f.description].filter(Boolean).join('\n'),
          apply: (view: EditorView, _c: Completion, from: number, to: number) => view.dispatch({
            changes: { from, to, insert: `${f.name}()` }, selection: { anchor: from + f.name.length + 1 }, userEvent: 'input.complete',
          }),
        })),
        validFor: /^[\w$]*$/,
      }
    }
    const chained = /\.\s*$/.test(before)
    if (!word && !chained && !ctx.explicit) return null
    const from = word?.from ?? ctx.pos
    const fns: Completion[] = FORMULA_FUNCTIONS.filter(f => !chained || f.subject).map(f => ({
      label: f.name, type: 'function', detail: f.returns, info: `${signature(f)}\n${f.description}\n${f.example}`,
      apply: (view: EditorView, _c: Completion, from: number, to: number) => view.dispatch({
        changes: { from, to, insert: `${f.name}()` }, selection: { anchor: from + f.name.length + 1 }, userEvent: 'input.complete',
      }),
    }))
    if (chained) return { from, options: fns, validFor: /^[\w$]*$/ }
    const doc = ctx.state.doc.toString()
    const vars: Completion[] = [
      ...inputs().map(i => ({ label: i.name, type: 'variable', detail: `input · ${i.type}`, boost: 2 })),
      ...localsOf(doc).filter(v => !inputs().some(i => i.name === v)).map(v => ({ label: v, type: 'variable', detail: 'var', boost: 1 })),
      ...(functions().length ? [{ label: 'fn', type: 'keyword', detail: 'your functions', info: 'Call one of your functions: fn.name(inputs…)' }] : []),
    ]
    return {
      from,
      options: [...vars, ...KEYWORD_SNIPPETS, ...fns].sort((a, b) => (TYPE_RANK[b.type ?? ''] ?? 0) - (TYPE_RANK[a.type ?? ''] ?? 0)),
      validFor: /^[\w$]*$/,
    }
  }
}

/** The function named under {@code pos}, when it is called there. */
export function scriptFunctionAt(doc: string, pos: number): { from: number; to: number; name: string } | null {
  const code = codeOnly(doc)
  let from = pos
  let to = pos
  while (from > 0 && /[\w$]/.test(code[from - 1])) from--
  while (to < code.length && /[\w$]/.test(code[to])) to++
  const name = code.slice(from, to)
  if (!name || !/^\s*\(/.test(code.slice(to)) || !functionNamed(name)) return null
  return { from, to, name }
}

export const scriptFunctionDocs = hoverTooltip((view: EditorView, pos: number) => {
  const hit = scriptFunctionAt(view.state.doc.toString(), pos)
  const f = hit && functionNamed(hit.name)
  if (!hit || !f) return null
  return {
    pos: hit.from, end: hit.to, above: true,
    create: () => {
      const dom = document.createElement('div')
      dom.className = 'cm-fx-doc'
      for (const [cls, text, tag] of [['cm-fx-doc-sig', signature(f), 'code'], ['cm-fx-doc-text', f.description, 'p'], ['cm-fx-doc-example', f.example, 'code']]) {
        const el = document.createElement(tag)
        el.className = cls
        el.textContent = text
        dom.append(el)
      }
      return { dom }
    },
  }
}, { hoverTime: 350 })
