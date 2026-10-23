/**
 * The formula language a mapped field speaks: one line of script over the run's data, in the
 * spirit of Zoho Flow's formula editor. The grammar, from loosest to tightest:
 *
 *   formula        := ternary
 *   ternary        := coalesce ( '?' formula ':' formula )?
 *   coalesce       := or ( '??' or )*
 *   or             := and ( ('||' | 'or') and )*
 *   and            := equality ( ('&&' | 'and') equality )*
 *   equality       := relational ( ('==' | '!=' | '=~' | '!~') relational )*
 *   relational     := additive ( ('<' | '<=' | '>' | '>=') additive )*
 *   additive       := multiplicative ( ('+' | '-') multiplicative )*
 *   multiplicative := unary ( ('*' | '/' | '%') unary )*
 *   unary          := ('!' | '-' | 'not') unary | postfix
 *   postfix        := primary ( '.' NAME args? | '[' formula ']' )*
 *   primary        := NUMBER | STRING | 'true' | 'false' | 'null'
 *                   | NAME args? | NAME ':' NAME args        (fmt:upper(x), as older formulas have it)
 *                   | '(' formula ')' | '[' items ']' | '{' entries '}'
 *   args           := '(' ( formula ( ',' formula )* )? ')'
 *
 * References are the data from earlier steps: input…, nodes.<step>…, vars.<name>…, with dot and
 * index paths. Functions are chained on a value (input.name.trim().upper()) or called plainly
 * (upper(input.name)); the engine runs both. The language is JEXL-shaped, so what the engine ran
 * before still parses.
 */

export type TokenKind = 'number' | 'string' | 'name' | 'keyword' | 'op' | 'punct' | 'error'

export interface Token { kind: TokenKind; text: string; from: number; to: number; open?: boolean }

const KEYWORDS = new Set(['true', 'false', 'null', 'and', 'or', 'not'])
const OPERATORS = ['==', '!=', '<=', '>=', '&&', '||', '??', '=~', '!~', '<', '>', '+', '-', '*', '/', '%', '!', '?', ':']
const PUNCT = new Set(['(', ')', '[', ']', '{', '}', '.', ','])

/** Splits a formula into tokens. Never fails: what it cannot read becomes an error token. */
export function tokenize(src: string, offset = 0): Token[] {
  const out: Token[] = []
  let i = 0
  while (i < src.length) {
    const c = src[i]
    const start = i
    if (/\s/.test(c)) { i++; continue }
    if (c === '"' || c === "'") {
      i++
      while (i < src.length && src[i] !== c) i += src[i] === '\\' ? 2 : 1
      const open = i >= src.length
      i = Math.min(src.length, i + 1)
      out.push({ kind: 'string', text: src.slice(start, i), from: start + offset, to: i + offset, open })
      continue
    }
    if (/[A-Za-z_$]/.test(c)) {
      while (i < src.length && /[\w$]/.test(src[i])) i++
      const text = src.slice(start, i)
      out.push({ kind: KEYWORDS.has(text) ? 'keyword' : 'name', text, from: start + offset, to: i + offset })
      continue
    }
    if (/\d/.test(c)) {
      while (i < src.length && (/[\d_]/.test(src[i]) || (src[i] === '.' && /\d/.test(src[i + 1] ?? '')))) i++
      out.push({ kind: 'number', text: src.slice(start, i), from: start + offset, to: i + offset })
      continue
    }
    if (PUNCT.has(c)) {
      i++
      out.push({ kind: 'punct', text: c, from: start + offset, to: i + offset })
      continue
    }
    const op = OPERATORS.find(o => src.startsWith(o, i))
    if (op) {
      i += op.length
      out.push({ kind: 'op', text: op, from: start + offset, to: i + offset })
      continue
    }
    i++
    out.push({ kind: 'error', text: c, from: start + offset, to: i + offset })
  }
  return out
}

export type Node =
  | { kind: 'literal'; type: 'number' | 'text' | 'boolean' | 'null'; value: string; from: number; to: number }
  | { kind: 'name'; name: string; from: number; to: number }
  | { kind: 'member'; object: Node; name: string; nameFrom: number; from: number; to: number }
  | { kind: 'index'; object: Node; index: Node; from: number; to: number }
  | { kind: 'call'; name: string; ns?: string; receiver?: Node; args: Node[]; nameFrom: number; nameTo: number; from: number; to: number }
  | { kind: 'unary'; op: string; arg: Node; from: number; to: number }
  | { kind: 'binary'; op: string; left: Node; right: Node; from: number; to: number }
  | { kind: 'ternary'; test: Node; yes: Node; no: Node; from: number; to: number }
  | { kind: 'list'; items: Node[]; from: number; to: number }
  | { kind: 'map'; entries: Array<{ key: Node; value: Node }>; from: number; to: number }
  | { kind: 'group'; inner: Node; from: number; to: number }

export interface SyntaxError { from: number; to: number; message: string }

export type ParseResult = { ok: true; node: Node | null } | { ok: false; error: SyntaxError }

class Fail extends Error { constructor(readonly at: SyntaxError) { super(at.message) } }

const BINARY: Array<{ ops: string[] }> = [
  { ops: ['??'] }, { ops: ['||', 'or'] }, { ops: ['&&', 'and'] }, { ops: ['==', '!=', '=~', '!~'] },
  { ops: ['<', '<=', '>', '>='] }, { ops: ['+', '-'] }, { ops: ['*', '/', '%'] },
]

/** Parses a formula; {@code offset} is where it starts in the field, for positions. */
export function parseFormula(src: string, offset = 0): ParseResult {
  const tokens = tokenize(src, offset)
  const end = offset + src.length
  if (!tokens.length) return { ok: true, node: null }
  let at = 0
  const peek = () => tokens[at] as Token | undefined
  const is = (text: string) => { const t = peek(); return !!t && (t.kind === 'op' || t.kind === 'punct' || t.kind === 'keyword') && t.text === text }
  const fail = (message: string, t = peek()): never => {
    throw new Fail(t ? { from: t.from, to: t.to, message } : { from: Math.max(offset, end - 1), to: end, message })
  }
  const expect = (text: string, what: string) => {
    if (!is(text)) fail(peek() ? `Expected ${what} but found “${peek()!.text}”` : `Expected ${what}`)
    return tokens[at++]
  }

  const formula = (): Node => {
    const test = binary(0)
    if (!is('?')) return test
    at++
    const yes = formula()
    expect(':', '“:” for the other case')
    const no = formula()
    return { kind: 'ternary', test, yes, no, from: test.from, to: no.to }
  }

  const binary = (level: number): Node => {
    if (level === BINARY.length) return unary()
    let left = binary(level + 1)
    while (peek() && BINARY[level].ops.includes(peek()!.text) && peek()!.kind !== 'string' && peek()!.kind !== 'name') {
      const op = tokens[at++].text
      const right = binary(level + 1)
      left = { kind: 'binary', op, left, right, from: left.from, to: right.to }
    }
    return left
  }

  const unary = (): Node => {
    const t = peek()
    if (t && (is('!') || is('-') || is('not'))) {
      at++
      const arg = unary()
      return { kind: 'unary', op: t.text, arg, from: t.from, to: arg.to }
    }
    return postfix(primary())
  }

  const args = (): { args: Node[]; to: number } => {
    expect('(', '“(”')
    const list: Node[] = []
    if (!is(')')) {
      list.push(formula())
      while (is(',')) { at++; list.push(formula()) }
    }
    const close = expect(')', '“)” to close the call')
    return { args: list, to: close.to }
  }

  const postfix = (node: Node): Node => {
    for (;;) {
      if (is('.')) {
        at++
        const name = peek()
        if (!name || (name.kind !== 'name' && name.kind !== 'keyword' && name.kind !== 'number')) fail('Expected a field or function name after “.”')
        at++
        if (is('(')) {
          const a = args()
          node = { kind: 'call', name: name!.text, receiver: node, args: a.args, nameFrom: name!.from, nameTo: name!.to, from: node.from, to: a.to }
        } else {
          node = { kind: 'member', object: node, name: name!.text, nameFrom: name!.from, from: node.from, to: name!.to }
        }
        continue
      }
      if (is('[')) {
        at++
        const index = formula()
        const close = expect(']', '“]”')
        node = { kind: 'index', object: node, index, from: node.from, to: close.to }
        continue
      }
      return node
    }
  }

  const primary = (): Node => {
    const t = peek()
    if (!t) return fail('The formula ends too soon')
    if (t.kind === 'number') { at++; return { kind: 'literal', type: 'number', value: t.text, from: t.from, to: t.to } }
    if (t.kind === 'string') {
      if (t.open) fail('This text is missing its closing quote', t)
      at++
      return { kind: 'literal', type: 'text', value: t.text.slice(1, -1), from: t.from, to: t.to }
    }
    if (t.kind === 'keyword' && (t.text === 'true' || t.text === 'false')) { at++; return { kind: 'literal', type: 'boolean', value: t.text, from: t.from, to: t.to } }
    if (t.kind === 'keyword' && t.text === 'null') { at++; return { kind: 'literal', type: 'null', value: t.text, from: t.from, to: t.to } }
    if (t.kind === 'name') {
      at++
      // fmt:upper(x): a namespace, with nothing between the parts.
      const colon = peek()
      const after = tokens[at + 1]
      if (colon?.text === ':' && colon.from === t.to && after?.kind === 'name' && after.from === colon.to) {
        at += 2
        const a = args()
        return { kind: 'call', name: after.text, ns: t.text, args: a.args, nameFrom: t.from, nameTo: after.to, from: t.from, to: a.to }
      }
      if (is('(')) {
        const a = args()
        return { kind: 'call', name: t.text, args: a.args, nameFrom: t.from, nameTo: t.to, from: t.from, to: a.to }
      }
      return { kind: 'name', name: t.text, from: t.from, to: t.to }
    }
    if (is('(')) {
      at++
      const inner = formula()
      const close = expect(')', '“)”')
      return { kind: 'group', inner, from: t.from, to: close.to }
    }
    if (is('[')) {
      at++
      const items: Node[] = []
      if (!is(']')) {
        items.push(formula())
        while (is(',')) { at++; items.push(formula()) }
      }
      const close = expect(']', '“]” to close the list')
      return { kind: 'list', items, from: t.from, to: close.to }
    }
    if (is('{')) {
      at++
      const entries: Array<{ key: Node; value: Node }> = []
      if (!is('}')) {
        do {
          if (is(',')) at++
          const key = formula()
          expect(':', '“:” after the key')
          entries.push({ key, value: formula() })
        } while (is(','))
      }
      const close = expect('}', '“}” to close the map')
      return { kind: 'map', entries, from: t.from, to: close.to }
    }
    if (t.kind === 'error') return fail(`“${t.text}” can't be used in a formula`)
    return fail(`Unexpected “${t.text}”`)
  }

  try {
    const node = formula()
    if (at < tokens.length) fail(`Unexpected “${tokens[at].text}”; join values with an operator such as + or &&`)
    return { ok: true, node }
  } catch (e) {
    if (e instanceof Fail) return { ok: false, error: e.at }
    throw e
  }
}

/** The roots a formula can read: the run's input, earlier steps, variables, and a loop's item. */
export const ROOTS = ['input', 'nodes', 'vars', 'instructions', 'item', 'index'] as const

/** A reference to data: a root and a path of fields and fixed indexes, such as nodes.fetch.body.items[0]. */
export interface Reference { text: string; from: number; to: number }

/**
 * Where the references are, from tokens alone so it works while the formula is half typed: a root
 * followed by .field and [0] steps, stopping before a function call.
 */
export function referencesIn(tokens: Token[]): Reference[] {
  const out: Reference[] = []
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i]
    if (t.kind !== 'name' || !(ROOTS as readonly string[]).includes(t.text)) continue
    const prev = tokens[i - 1]
    if (prev && (prev.text === '.' || (prev.text === ':' && prev.to === t.from))) continue
    if (tokens[i + 1]?.text === '(' || (tokens[i + 1]?.text === ':' && tokens[i + 1].from === t.to)) continue
    let j = i + 1
    let end = i
    for (;;) {
      const a = tokens[j], b = tokens[j + 1], c = tokens[j + 2]
      if (a?.text === '.' && b && (b.kind === 'name' || b.kind === 'keyword' || b.kind === 'number') && c?.text !== '(') {
        end = j + 1
        j += 2
        continue
      }
      if (a?.text === '[' && b && (b.kind === 'number' || (b.kind === 'string' && !b.open)) && c?.text === ']') {
        end = j + 2
        j += 3
        continue
      }
      break
    }
    // nodes on its own, or nodes.<id> with nothing after, still points at something
    out.push({ text: tokens.slice(i, end + 1).map(x => x.text).join(''), from: t.from, to: tokens[end].to })
    i = end
  }
  return out
}

/** A {{ … }} placeholder in a template field, with where its formula starts and ends. */
export interface Placeholder { from: number; to: number; innerFrom: number; innerTo: number; closed: boolean }

/** The {{ … }} placeholders of a template, including one still being typed. */
export function placeholdersIn(text: string): Placeholder[] {
  const out: Placeholder[] = []
  let at = 0
  for (;;) {
    const open = text.indexOf('{{', at)
    if (open < 0) return out
    const close = text.indexOf('}}', open + 2)
    const next = text.indexOf('{{', open + 2)
    if (close < 0 || (next >= 0 && next < close)) {
      const innerTo = next >= 0 ? next : text.length
      out.push({ from: open, to: innerTo, innerFrom: open + 2, innerTo, closed: false })
      if (next < 0) return out
      at = next
      continue
    }
    out.push({ from: open, to: close + 2, innerFrom: open + 2, innerTo: close, closed: true })
    at = close + 2
  }
}
