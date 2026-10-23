/**
 * Checks a formula the way Zoho Flow's editor does while you type: syntax, unknown functions and
 * data, the number of arguments, and arguments of the wrong type. Syntax and unknown names are
 * errors (the step would fail); a type mismatch is a warning, since the engine converts values.
 */
import { fits, functionNamed, typeName, type ValueType } from './functions'
import { parseFormula, ROOTS, type Node } from './parser'

/** What the editor knows about the data a formula can read. */
export interface FormulaScope {
  /** The type of a reference such as nodes.fetch.body.price; undefined when it isn't known. */
  typeOf(ref: string): ValueType | undefined
  /** Whether nodes.<id> is a step before this one. */
  hasStep(id: string): boolean
  /** Whether vars.<name> is set before this step; undefined when the variables aren't known. */
  hasVariable(name: string): boolean | undefined
}

export interface Problem { from: number; to: number; severity: 'error' | 'warning'; message: string }

export interface Analysis { problems: Problem[]; type: ValueType }

export function analyze(src: string, scope: FormulaScope, offset = 0): Analysis {
  const parsed = parseFormula(src, offset)
  if (!parsed.ok) return { problems: [{ ...parsed.error, severity: 'error' }], type: 'any' }
  if (!parsed.node) return { problems: [], type: 'any' }
  const problems: Problem[] = []
  const type = typeOfNode(parsed.node, scope, problems)
  return { problems, type }
}

/** The path of a reference node (input.a[0].b) as text, or null when the node is anything else. */
export function referenceText(node: Node): string | null {
  if (node.kind === 'name') return (ROOTS as readonly string[]).includes(node.name) ? node.name : null
  if (node.kind === 'member') {
    const base = referenceText(node.object)
    return base === null ? null : `${base}.${node.name}`
  }
  if (node.kind === 'index' && node.index.kind === 'literal' && (node.index.type === 'number' || node.index.type === 'text')) {
    const base = referenceText(node.object)
    if (base === null) return null
    return node.index.type === 'number' ? `${base}[${node.index.value}]` : `${base}['${node.index.value}']`
  }
  return null
}

function typeOfNode(node: Node, scope: FormulaScope, problems: Problem[]): ValueType {
  switch (node.kind) {
    case 'literal':
      return node.type === 'null' ? 'any' : node.type
    case 'name':
    case 'member':
    case 'index': {
      const ref = referenceText(node)
      if (ref !== null) return checkReference(ref, node, scope, problems)
      if (node.kind === 'name') {
        problems.push({ from: node.from, to: node.to, severity: 'error', message: `Unknown name “${node.name}”. Data comes from input, nodes, vars or item` })
        return 'any'
      }
      // A field of something computed, such as a call's result: fine, but its type is unknown.
      typeOfNode(node.object, scope, problems)
      if (node.kind === 'index') typeOfNode(node.index, scope, problems)
      return 'any'
    }
    case 'call': {
      const fn = functionNamed(node.name)
      const args = node.receiver ? [node.receiver, ...node.args] : node.args
      const types = args.map(a => typeOfNode(a, scope, problems))
      if (node.ns && node.ns !== 'fmt') {
        problems.push({ from: node.nameFrom, to: node.nameTo, severity: 'error', message: `Unknown function group “${node.ns}:”; use fmt: or call the function directly` })
        return 'any'
      }
      if (!fn) {
        problems.push({ from: node.nameFrom, to: node.nameTo, severity: 'error', message: `Unknown function “${node.name}”` })
        return 'any'
      }
      if (node.receiver && !fn.subject) {
        problems.push({ from: node.nameFrom, to: node.to, severity: 'error', message: `“${fn.name}” can't be chained on a value; write ${fn.name}()` })
        return fn.returns
      }
      const wanted = [...(fn.subject ? [fn.subject] : []), ...fn.params]
      // Counted as written in the brackets, where a chained call's subject isn't.
      const shown = node.receiver ? wanted.slice(1) : wanted
      const most = shown.length
      const least = shown.filter(p => !('optional' in p && p.optional)).length
      const given = node.args.length
      if (given < least || given > most) {
        const takes = least === most ? `${most}` : `${least} to ${most}`
        problems.push({ from: node.nameFrom, to: node.to, severity: 'error', message: `“${fn.name}” takes ${takes} ${takes === '1' ? 'value' : 'values'} in brackets, not ${given}` })
        return fn.returns
      }
      args.forEach((arg, i) => {
        const expected = wanted[i].type
        if (!fits(expected, types[i])) {
          problems.push({ from: arg.from, to: arg.to, severity: 'warning', message: `“${fn.name}” expects ${typeName(expected)} for ${wanted[i].name}, but this is ${typeName(types[i])}` })
        }
      })
      return fn.returns
    }
    case 'unary': {
      const t = typeOfNode(node.arg, scope, problems)
      if (node.op === '-') {
        if (!fits('number', t)) problems.push({ from: node.from, to: node.to, severity: 'warning', message: `“-” needs a number, but this is ${typeName(t)}` })
        return 'number'
      }
      return 'boolean'
    }
    case 'binary': {
      const l = typeOfNode(node.left, scope, problems)
      const r = typeOfNode(node.right, scope, problems)
      switch (node.op) {
        case '+':
          return l === 'number' && r === 'number' ? 'number' : l === 'any' || r === 'any' ? 'any' : 'text'
        case '-': case '*': case '/': case '%':
          for (const [side, t] of [[node.left, l], [node.right, r]] as const) {
            if (!fits('number', t)) problems.push({ from: side.from, to: side.to, severity: 'warning', message: `“${node.op}” works on numbers, but this is ${typeName(t)}` })
          }
          return 'number'
        case '??':
          return l === r ? l : 'any'
        default:
          return 'boolean'
      }
    }
    case 'ternary': {
      typeOfNode(node.test, scope, problems)
      const yes = typeOfNode(node.yes, scope, problems)
      const no = typeOfNode(node.no, scope, problems)
      return yes === no ? yes : 'any'
    }
    case 'list':
      node.items.forEach(i => typeOfNode(i, scope, problems))
      return 'list'
    case 'map':
      node.entries.forEach(e => { typeOfNode(e.key, scope, problems); typeOfNode(e.value, scope, problems) })
      return 'map'
    case 'group':
      return typeOfNode(node.inner, scope, problems)
  }
}

function checkReference(ref: string, node: Node, scope: FormulaScope, problems: Problem[]): ValueType {
  const step = /^nodes\.([A-Za-z0-9_]+)/.exec(ref)
  if (ref === 'nodes') {
    problems.push({ from: node.from, to: node.to, severity: 'warning', message: 'Pick a step after “nodes.”' })
  } else if (step && !scope.hasStep(step[1])) {
    problems.push({ from: node.from, to: node.to, severity: 'error', message: `No step “${step[1]}” runs before this one` })
    return 'any'
  }
  const variable = /^vars\.([A-Za-z_][A-Za-z0-9_]*)/.exec(ref)
  if (variable && scope.hasVariable(variable[1]) === false) {
    problems.push({ from: node.from, to: node.to, severity: 'warning', message: `No “Set variable” step before this one sets “${variable[1]}”` })
  }
  return scope.typeOf(ref) ?? 'any'
}
