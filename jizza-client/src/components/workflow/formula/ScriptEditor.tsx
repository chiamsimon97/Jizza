import { useEffect, useRef } from 'react'
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap, indentWithTab, toggleComment } from '@codemirror/commands'
import { bracketMatching, indentOnInput } from '@codemirror/language'
import { linter, lintGutter } from '@codemirror/lint'
import { Compartment, EditorState, Transaction } from '@codemirror/state'
import { EditorView, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers, placeholder, tooltips } from '@codemirror/view'
import { fieldClass } from '@/components/ui/SlideOver'
import { formulaTheme } from './editor'
import { scriptCompletions, scriptFunctionDocs, scriptHighlighting, scriptLanguage, scriptProblems, type ScriptInput } from './script'

const FRAME = fieldClass.replace(/\bfocus:/g, 'focus-within:')
const external = Transaction.userEvent.of('external')

const scriptTheme = EditorView.theme({
  '.cm-scroller': { fontFamily: "'JetBrains Mono', ui-monospace, monospace", lineHeight: '1.4rem', overflow: 'auto' },
  '.cm-gutters': { backgroundColor: 'transparent', border: 'none', color: 'rgb(var(--gray-400))' },
  '.cm-lineNumbers .cm-gutterElement': { padding: '0 8px 0 0', minWidth: '1.75rem' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'rgb(var(--brand-500) / .05)' },
  '.cm-content': { padding: '4px 0' },
  '.cm-matchingBracket': { backgroundColor: 'rgb(var(--brand-500) / .15)', outline: 'none' },
})

/**
 * The Script step's multi-line editor, on CodeMirror 6 like the formula fields: Jizza script colours,
 * line numbers, Tab to indent, Ctrl/Cmd+/ to comment, brackets that close themselves, suggestions of
 * the step's inputs, its own variables, keywords and functions with docs, and problems underlined
 * as you type. The engine checks the rest when the step runs, or on Test.
 */
export function ScriptEditor({ value, onChange, inputs, rows = 12, invalid, ...aria }: {
  value: string; onChange: (v: string) => void; inputs: ScriptInput[]; rows?: number; invalid?: boolean
  'aria-label'?: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const emit = useRef(onChange)
  emit.current = onChange
  const known = useRef(inputs)
  known.current = inputs
  const editable = useRef(new Compartment())

  useEffect(() => {
    if (!host.current) return
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), history(), indentOnInput(), bracketMatching(), closeBrackets(),
          scriptLanguage, scriptHighlighting, formulaTheme, scriptTheme, tooltips({ parent: document.body }),
          autocompletion({ override: [scriptCompletions(() => known.current)], icons: true }),
          scriptFunctionDocs,
          linter(v => scriptProblems(v.state.doc.toString(), known.current), { delay: 300 }), lintGutter(),
          placeholder("var total = 0;\nfor (var line : lines) {\n  total = total + line.amount;\n}\nreturn {'total': total};"),
          keymap.of([...closeBracketsKeymap, ...completionKeymap, indentWithTab, { key: 'Mod-/', run: toggleComment }, ...defaultKeymap, ...historyKeymap]),
          editable.current.of([]),
          EditorView.contentAttributes.of({ 'aria-label': aria['aria-label'] ?? 'Script', 'aria-multiline': 'true', spellcheck: 'false' }),
          EditorView.updateListener.of(u => {
            if (!u.docChanged || u.transactions.every(t => t.annotation(Transaction.userEvent) === 'external')) return
            emit.current(u.state.doc.toString())
          }),
        ],
      }),
    })
    view.current = v
    return () => { v.destroy(); view.current = null }
    // Made once; the value and inputs flow in through the refs and effects below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const v = view.current
    if (!v || v.state.doc.toString() === value) return
    v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value }, annotations: external })
  }, [value])

  // A disabled fieldset (a read-only workflow) makes the editor read-only too.
  useEffect(() => {
    const disabled = !!host.current?.closest('fieldset:disabled')
    view.current?.dispatch({ effects: editable.current.reconfigure(disabled ? [EditorView.editable.of(false), EditorState.readOnly.of(true)] : []) })
  })

  return (
    <div ref={host} style={{ height: `${rows * 1.4 + 0.75}rem` }}
      className={`jz-script ${FRAME} min-w-0 resize-y overflow-hidden !p-0 font-mono text-xs [&_.cm-editor]:h-full ${invalid ? '!border-red-400' : ''}`} />
  )
}
