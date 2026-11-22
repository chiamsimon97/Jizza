import { useState } from 'react'
import { ArrowDown, ArrowUp, Loader2, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { webApi, type BrowserAgent } from '@/api/web'
import {
  dropLayerButton, layerOf, nextLayerId, orderLayerFields, updateNode,
  type CanvasGraph, type CanvasNode, type LayerButton, type LayerField, type LayerGroup,
} from './canvasModel'
import { MappedField, type MappingContext } from './MappedField'
import { FoldSection, MoreOptions } from './MoreOptions'
import { ElementField, Labelled, ListValueField, LoadFromPage, Section } from './CanvasInspector'

const KINDS: { kind: LayerField['kind']; label: string }[] = [
  { kind: 'text', label: 'Text or text area' },
  { kind: 'select', label: 'Dropdown' },
  { kind: 'checkbox', label: 'Checkbox' },
  { kind: 'checkboxes', label: 'Checkbox group' },
  { kind: 'radio', label: 'Radio buttons' },
  { kind: 'date', label: 'Date or date and time' },
  { kind: 'datepicker', label: 'Calendar the site draws' },
  { kind: 'combobox', label: 'Search and choose' },
  { kind: 'list', label: 'Choose from a list' },
]

const BUTTON_KINDS: { kind: NonNullable<LayerButton['kind']>; label: string }[] = [
  { kind: 'submit', label: 'Sends the form' },
  { kind: 'cancel', label: 'Cancels' },
  { kind: 'other', label: 'Other' },
]

const fieldName = (f: LayerField, i: number) =>
  f.label?.trim() || (f.element as { label?: string } | undefined)?.label?.trim() || `Field ${i + 1}`

/** The name an element was picked with, such as "Submit button". */
const rootLabel = (e: unknown) => {
  const label = e && typeof e === 'object' ? (e as { label?: unknown }).label : undefined
  return typeof label === 'string' && label.trim() ? label.trim() : undefined
}

/** Same test as the step's check before saving: the element was picked (it has selectors). */
const hasElement = (e: unknown) => !!e && typeof e === 'object' && Array.isArray((e as { strategies?: unknown }).strategies)

const valueText = (v: unknown) => (Array.isArray(v) ? v.join(', ') : v == null ? '' : String(v))

/** What "Scan this area" reads from the page: the area's fields, in order, and its buttons. */
interface Scanned {
  fields?: LayerField[]
  buttons?: LayerButton[]
  groups?: LayerGroup[]
}

/**
 * A device layer's settings: the area of the page, its fixed values, the values for each run
 * in their groups (with which field follows which), and its buttons. Each button is a way out of
 * the step on the canvas; removing one whose path has steps asks where those steps go.
 */
export function LayerFields({ node, graph, browsers, deviceId, mapping, isMissing, onGraph }: {
  node: CanvasNode; graph: CanvasGraph; browsers?: BrowserAgent[]; deviceId?: string
  mapping: MappingContext; isMissing: (field: string) => boolean
  onGraph: (graph: CanvasGraph) => void
}) {
  const { fields, groups: savedGroups, buttons } = layerOf(node.config)
  const groups = savedGroups.length > 0 ? savedGroups : [{ id: 'g1', title: 'Details' }]
  const inputs = (node.config.inputs && typeof node.config.inputs === 'object' ? node.config.inputs : {}) as Record<string, unknown>
  const [loaded, setLoaded] = useState<Record<string, string[]>>({})
  const [removing, setRemoving] = useState<string>()
  const [moveTo, setMoveTo] = useState('')
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState('')

  const write = (patch: Record<string, unknown>, g: CanvasGraph = graph) => {
    const next = { ...inputs, ...patch }
    for (const k of Object.keys(next)) if (next[k] === undefined) delete next[k]
    onGraph(updateNode(g, node.id, { config: { ...node.config, inputs: next } }))
  }
  const setFields = (next: LayerField[], g?: CanvasGraph) =>
    write({ fields: orderLayerFields(next, groups), groups }, g)
  const setField = (id: string, patch: Partial<LayerField>) => setFields(fields.map(f => {
    if (f.id !== id) return f
    const merged = { ...f, ...patch } as LayerField
    for (const k of Object.keys(merged)) if (merged[k] === undefined || merged[k] === '') delete merged[k]
    return merged
  }))
  const addField = (section: 'static' | 'config', group?: string) => {
    const id = nextLayerId('f', fields.map(f => f.id))
    setFields([...fields, { id, kind: 'text', section, ...(section === 'config' ? { group: group ?? groups[0].id } : {}) }])
  }
  const removeField = (id: string) =>
    setFields(fields.filter(f => f.id !== id).map(f => (f.dependsOn === id ? { ...f, dependsOn: undefined } : f)))
  const move = (id: string, by: -1 | 1) => {
    const at = fields.findIndex(f => f.id === id)
    const to = at + by
    if (at < 0 || to < 0 || to >= fields.length) return
    const next = [...fields]
    ;[next[at], next[to]] = [next[to], next[at]]
    setFields(next)
  }
  const toSection = (f: LayerField, section: 'static' | 'config') => setFields(fields.map(x => x.id !== f.id ? x
    : section === 'static'
      ? { ...x, section, group: undefined, dependsOn: undefined, required: undefined }
      : { ...x, section, group: groups[0].id }))

  const setGroups = (next: LayerGroup[]) => write({ groups: next, fields: orderLayerFields(fields, next) })
  const addGroup = () => setGroups([...groups, { id: nextLayerId('g', groups.map(g => g.id)), title: `Group ${groups.length + 1}` }])

  const setButtons = (next: LayerButton[], g?: CanvasGraph) => write({ buttons: next }, g)
  const setButton = (id: string, patch: Partial<LayerButton>) => setButtons(buttons.map(b => (b.id === id ? { ...b, ...patch } : b)))
  const addButton = () => setButtons([...buttons, { id: nextLayerId('b', buttons.map(b => b.id)), name: '', kind: 'other' }])
  const pathOf = (id: string) => graph.edges.find(e => e.from === node.id && e.branch === id)
  const dropButton = (id: string, target?: string) => {
    const kept = buttons.filter(b => b.id !== id)
    const g = dropLayerButton(graph, node.id, id, target)
    const chosen = typeof inputs.button === 'string' && inputs.button === id ? undefined : inputs.button
    const next = { ...inputs, buttons: kept, button: chosen }
    if (next.button === undefined) delete next.button
    onGraph(updateNode(g, node.id, { config: { ...node.config, inputs: next } }))
    setRemoving(undefined)
    setMoveTo('')
  }

  const scan = async () => {
    if (!deviceId) return
    setScanning(true); setScanError('')
    try {
      const result = await webApi.testStep({ deviceId, action: 'browser.scanLayer', version: 1, inputs: inputs.root ? { root: inputs.root } : {} })
      const out = result.output as Scanned | undefined
      if (result.status !== 'succeeded' || !out) {
        setScanError(result.error?.detail ?? result.message ?? 'The area could not be read.')
        return
      }
      // Buttons keep their ids (and paths) when their names match ones the step has.
      const found = (out.buttons ?? []).map(b => {
        const same = buttons.find(x => x.name.trim().toLowerCase() === b.name.trim().toLowerCase())
        return same ? { ...b, id: same.id } : b
      })
      let ids = buttons.map(b => b.id)
      const placed = found.map(b => {
        if (buttons.some(x => x.id === b.id)) return b
        const id = nextLayerId('b', ids)
        ids = [...ids, id]
        return { ...b, id }
      })
      const scannedGroups = out.groups && out.groups.length > 0 ? out.groups : groups
      let g = graph
      for (const b of buttons) if (!placed.some(x => x.id === b.id)) g = dropLayerButton(g, node.id, b.id)
      const next = { ...inputs, groups: scannedGroups, fields: orderLayerFields(out.fields ?? [], scannedGroups), buttons: placed }
      onGraph(updateNode(g, node.id, { config: { ...node.config, inputs: next } }))
    } catch (e) {
      const problem = (e as { problem?: { detail?: string; title?: string } }).problem
      setScanError(problem?.detail ?? problem?.title ?? (e instanceof Error ? e.message : 'The area could not be read.'))
    } finally {
      setScanning(false)
    }
  }

  const fixed = fields.filter(f => f.section === 'static')
  const perRun = fields.filter(f => f.section !== 'static')
  const chosenButton = typeof inputs.button === 'string' ? inputs.button : ''
  const chosenName = buttons.find(b => b.id === chosenButton)?.name ?? chosenButton

  const row = (f: LayerField) => {
    const i = fields.indexOf(f)
    const name = fieldName(f, i)
    const isFixed = f.section === 'static'
    const above = perRun.slice(0, perRun.indexOf(f)).filter(x => x.id !== f.id)
    const parent = fields.find(x => x.id === f.dependsOn)
    const choices = loaded[f.id] ?? (Array.isArray(f.options) ? f.options : [])
    const choosing = f.kind === 'select' || f.kind === 'radio' || f.kind === 'list'
    const kindLabel = KINDS.find(k => k.kind === f.kind)?.label ?? f.kind
    const groupTitle = groups.find(g => g.id === (f.group ?? groups[0].id))?.title
    const missing = isMissing(`inputs.fields.${f.id}`)
    const summary = [kindLabel, !isFixed && groups.length > 1 ? groupTitle : undefined,
      parent ? `follows ${fieldName(parent, fields.indexOf(parent))}` : undefined].filter(Boolean).join(' · ')
    return (
      <div key={f.id} className="space-y-2 rounded-lg border border-gray-200 p-2 dark:border-gray-800" data-testid={`layer-field-${f.id}`}>
        <div className="flex items-center gap-1">
          <input aria-label={`Name of ${name}`} className={`${fieldClass} flex-1 text-xs font-semibold`} value={f.label ?? ''} placeholder={name}
            onChange={e => setField(f.id, { label: e.target.value })} />
          {!isFixed && <label className="flex shrink-0 items-center gap-1 px-1 text-[11px] text-gray-600 dark:text-gray-300"
            title="Mandatory: ask me when it comes out empty">
            <input type="checkbox" aria-label={`${name} is mandatory: ask me when it comes out empty`} checked={f.required === true}
              onChange={e => setField(f.id, { required: e.target.checked || undefined })} />
            Mandatory
          </label>}
          <button type="button" aria-label={`Move ${name} up`} className="p-1 text-gray-400 hover:text-gray-700" onClick={() => move(f.id, -1)}><ArrowUp className="h-3.5 w-3.5" /></button>
          <button type="button" aria-label={`Move ${name} down`} className="p-1 text-gray-400 hover:text-gray-700" onClick={() => move(f.id, 1)}><ArrowDown className="h-3.5 w-3.5" /></button>
          <button type="button" aria-label={`Remove ${name}`} className="p-1 text-gray-400 hover:text-red-600" onClick={() => removeField(f.id)}><Trash2 className="h-3.5 w-3.5" /></button>
        </div>
        {parent
          ? <p className="text-[11px] text-gray-500" data-testid="layer-follows">Loads after {fieldName(parent, fields.indexOf(parent))} is chosen. Leave it empty to choose from what the page lists then.</p>
          : null}
        {choosing && !parent
          ? <ListValueField label={isFixed ? 'Value' : 'Value for each run'} choices={choices} value={valueText(f.value)} mapping={mapping}
              onChange={v => setField(f.id, { value: v })} />
          : <Labelled group label={isFixed ? 'Value' : 'Value for each run'}>
              <MappedField aria-label={`Value of ${name}`} mode="template" context={mapping} value={valueText(f.value)}
                onChange={v => setField(f.id, { value: v })} />
            </Labelled>}
        <MoreOptions summary={summary} hasError={missing}>
          <ElementField label="Field on the page" value={f.element} deviceId={deviceId} browsers={browsers} hasEarlierOpen mapping={mapping}
            required missing={missing} onChange={element => setField(f.id, { element })} />
          <Labelled label="Kind">
            <select aria-label={`Kind of ${name}`} className={fieldClass} value={f.kind}
              onChange={e => setField(f.id, { kind: e.target.value as LayerField['kind'] })}>
              {KINDS.map(k => <option key={k.kind} value={k.kind}>{k.label}</option>)}
            </select>
          </Labelled>
          {f.kind === 'select' && f.element !== undefined && (
            <LoadFromPage what="items" deviceId={deviceId} action="browser.listOptions" list={{ element: f.element }}
              blocked={parent ? `Choose ${fieldName(parent, fields.indexOf(parent))} on the page first, then load these.` : undefined}
              onLoaded={labels => { setLoaded(l => ({ ...l, [f.id]: labels })); if (!parent) setField(f.id, { options: labels }) }} />
          )}
          {!isFixed && <div className="grid grid-cols-2 gap-2">
            <Labelled label="Group">
              <select aria-label={`Group of ${name}`} className={fieldClass} value={f.group ?? groups[0].id}
                onChange={e => setField(f.id, { group: e.target.value })}>
                {groups.map(g => <option key={g.id} value={g.id}>{g.title || g.id}</option>)}
              </select>
            </Labelled>
            <Labelled label="Follows" hint="Its choices load after that field is set.">
              <select aria-label={`${name} follows`} className={fieldClass} value={f.dependsOn ?? ''}
                onChange={e => setField(f.id, { dependsOn: e.target.value || undefined })}>
                <option value="">Nothing</option>
                {above.map(x => <option key={x.id} value={x.id}>{fieldName(x, fields.indexOf(x))}</option>)}
              </select>
            </Labelled>
          </div>}
          <p className="text-[11px] text-gray-400">{isFixed ? 'Fixed: the same every run.' : choosing && !parent ? 'Choose one, or map it from earlier steps.' : 'Map it from earlier steps, or type it.'}</p>
          <button type="button" className="self-start text-[11px] text-brand-600 hover:underline dark:text-brand-300"
            onClick={() => toSection(f, isFixed ? 'config' : 'static')}>
            {isFixed ? 'Move to the values for each run' : 'Make it a fixed value'}
          </button>
        </MoreOptions>
      </div>
    )
  }

  return <>
    <FoldSection title="Area of the page" defaultOpen={fields.length === 0} hasError={!!scanError}
      summary={rootLabel(inputs.root) ?? 'the whole page'}>
      <ElementField label="The box that holds the form (optional)" value={inputs.root} deviceId={deviceId} browsers={browsers} hasEarlierOpen
        mapping={mapping} onChange={root => write({ root })} />
      <div className="flex items-center gap-2">
        <Button type="button" size="sm" variant="secondary" disabled={!deviceId || scanning} onClick={() => void scan()}>
          {scanning && <Loader2 className="h-3.5 w-3.5 animate-spin" />} Scan this area
        </Button>
        <span className="text-[11px] text-gray-500">{deviceId
          ? fields.length > 0 ? 'Reads the fields and buttons again; fields you set up here are replaced.' : 'Reads its fields, their order and its buttons from the page.'
          : 'Choose a browser above to scan its page.'}</span>
      </div>
      {scanError && <p className="text-xs text-red-600">{scanError}</p>}
    </FoldSection>

    <Section title="Fixed values">
      <p className="text-[11px] text-gray-500">Filled first, the same on every run.</p>
      {fixed.map(row)}
      <Button type="button" size="sm" variant="secondary" onClick={() => addField('static')}><Plus className="h-3.5 w-3.5" /> Add fixed value</Button>
    </Section>

    <Section title="Values for each run">
      <p className="text-[11px] text-gray-500">Filled after the fixed values, group by group, in order. Map them from earlier steps; an empty mandatory one is asked when the run gets here.</p>
      {groups.map((g, gi) => {
        const inGroup = perRun.filter(f => (f.group ?? groups[0].id) === g.id)
        return (
          <div key={g.id} className="space-y-2 rounded-lg border border-dashed border-gray-300 p-2 dark:border-gray-700" data-testid={`layer-group-${g.id}`}>
            <div className="flex items-center gap-1">
              <input aria-label={`Name of group ${gi + 1}`} className={`${fieldClass} flex-1 text-xs`} value={g.title ?? ''}
                onChange={e => setGroups(groups.map(x => (x.id === g.id ? { ...x, title: e.target.value } : x)))} />
              {groups.length > 1 && inGroup.length === 0 && (
                <button type="button" aria-label={`Remove group ${g.title || gi + 1}`} className="p-1 text-gray-400 hover:text-red-600"
                  onClick={() => setGroups(groups.filter(x => x.id !== g.id))}><Trash2 className="h-3.5 w-3.5" /></button>
              )}
            </div>
            {inGroup.map(row)}
            <Button type="button" size="sm" variant="secondary" onClick={() => addField('config', g.id)}><Plus className="h-3.5 w-3.5" /> Fill or click one thing</Button>
          </div>
        )
      })}
      <button type="button" className="self-start text-xs text-brand-600 hover:underline dark:text-brand-300" onClick={addGroup}>Add group</button>
    </Section>

    <Section title="Buttons">
      <p className="text-[11px] text-gray-500">Each button is its own path out of this step.</p>
      {isMissing('inputs.buttons') && <p className="text-xs text-red-600">Add the buttons of the area, each with a name and its place on the page.</p>}
      {buttons.map((b, i) => {
        const path = pathOf(b.id)
        const next = path ? graph.nodes.find(n => n.id === path.to) : undefined
        const others = buttons.filter(x => x.id !== b.id && !pathOf(x.id))
        return (
          <div key={b.id} className="space-y-2 rounded-lg border border-gray-200 p-2 dark:border-gray-800" data-testid={`layer-button-${b.id}`}>
            <div className="flex items-center gap-1">
              <input aria-label={`Name of button ${i + 1}`} className={`${fieldClass} flex-1 text-xs font-semibold`} value={b.name} placeholder="Such as Submit"
                onChange={e => setButton(b.id, { name: e.target.value })} />
              <select aria-label={`What button ${i + 1} does`} className={`${fieldClass} w-36 text-xs`} value={b.kind ?? 'other'}
                onChange={e => setButton(b.id, { kind: e.target.value as LayerButton['kind'] })}>
                {BUTTON_KINDS.map(k => <option key={k.kind} value={k.kind}>{k.label}</option>)}
              </select>
              <button type="button" aria-label={`Remove button ${b.name || i + 1}`} className="p-1 text-gray-400 hover:text-red-600"
                onClick={() => (path ? setRemoving(b.id) : dropButton(b.id))}><Trash2 className="h-3.5 w-3.5" /></button>
            </div>
            <MoreOptions summary={rootLabel(b.element) ? `on the page: ${rootLabel(b.element)}` : 'not picked on the page yet'}
              hasError={!hasElement(b.element)}>
              <ElementField label="Button on the page" value={b.element} deviceId={deviceId} browsers={browsers} hasEarlierOpen mapping={mapping}
                required missing={!hasElement(b.element)} onChange={element => setButton(b.id, { element })} />
            </MoreOptions>
            {removing === b.id && (
              <div role="alertdialog" aria-label={`Remove ${b.name || 'button'}`} className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-2 text-xs dark:border-amber-700 dark:bg-amber-950/40">
                <p className="text-gray-800 dark:text-gray-100">"{b.name || 'This button'}" leads to "{next?.label ?? 'a step'}". Where should those steps go?</p>
                {others.length > 0 && <div className="flex items-center gap-2">
                  <select aria-label="Move its steps to" className={`${fieldClass} flex-1 text-xs`} value={moveTo} onChange={e => setMoveTo(e.target.value)}>
                    <option value="">Move them to another button…</option>
                    {others.map(x => <option key={x.id} value={x.id}>{x.name || x.id}</option>)}
                  </select>
                  <Button type="button" size="sm" variant="secondary" disabled={!moveTo} onClick={() => dropButton(b.id, moveTo)}>Move</Button>
                </div>}
                <div className="flex items-center gap-2">
                  <Button type="button" size="sm" variant="secondary" onClick={() => dropButton(b.id)}>Disconnect them</Button>
                  <Button type="button" size="sm" variant="ghost" onClick={() => { setRemoving(undefined); setMoveTo('') }}>Keep the button</Button>
                </div>
                <p className="text-[11px] text-gray-500">Disconnected steps stay in the workflow, marked as never reached, until you connect or delete them.</p>
              </div>
            )}
          </div>
        )
      })}
      <Button type="button" size="sm" variant="secondary" onClick={addButton}><Plus className="h-3.5 w-3.5" /> Add button</Button>
    </Section>

    <Section title="Button to click">
      <ListValueField label="Then click" choices={buttons.map(b => b.name).filter(Boolean)} value={chosenName} mapping={mapping}
        hint="Leave it empty to choose on the card each run, or map it from earlier steps."
        onChange={v => write({ button: buttons.find(b => b.name === v)?.id ?? (v || undefined) })} />
    </Section>
  </>
}
