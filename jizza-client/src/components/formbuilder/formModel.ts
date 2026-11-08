/**
 * The form builder's model: field types, the palette, and the helpers that edit a form's list
 * of fields. What is saved is the list itself; the server checks it again (FormFields.java).
 *
 * A step layout ({@code type: 'step_layout'}) holds the fields made from one workflow step. Those
 * fields have a {@code binding} to the step setting they fill and stay {@code pending} until the
 * person accepts them; pending fields never reach the published form.
 */
import {
  AlignLeft, AtSign, Calendar, CalendarClock, CheckSquare, Circle, Clock, DollarSign, FileUp,
  FunctionSquare, Hash, Heading, Image as ImageIcon, Link2, ListChecks, ListOrdered, MapPin,
  Mic, PenLine, Percent, Phone, QrCode, Rows3, Search, SquareCheck, StickyNote, Type, User, Video,
  Baseline, ChevronDownSquare, type LucideIcon,
} from 'lucide-react'

export type FieldType =
  | 'name' | 'email' | 'address' | 'phone' | 'single_line' | 'multi_line' | 'number' | 'date' | 'time'
  | 'dropdown' | 'radio' | 'multi_select' | 'checkbox'
  | 'decision' | 'rich_text' | 'url' | 'image' | 'percent' | 'currency' | 'decimal' | 'datetime'
  | 'subform' | 'lookup' | 'signature' | 'file' | 'audio' | 'video' | 'auto_number' | 'formula' | 'qr_code'
  | 'section' | 'note' | 'step_layout'

/** How a field made from a step fills that step: a template setting, a formula setting, or a start input. */
export type BindingMode = 'template' | 'expression' | 'input'

export interface FieldBinding {
  workflowId: string
  nodeId: string
  /** The setting's path in the step's config, such as ["arguments", "query"]. Empty for a start input. */
  path: Array<string | number>
  mode: BindingMode
}

export interface SubformColumn { name: string; label: string; type: 'single_line' | 'multi_line' | 'number' | 'email' | 'date' | 'decision' }

export interface FormField {
  id: string
  type: FieldType
  /** The link name: the key the answer is saved under, and input.<name> in the workflow. */
  name?: string
  label: string
  placeholder?: string
  help?: string
  required?: boolean
  choices?: string[]
  min?: number
  max?: number
  maxLength?: number
  currency?: string
  decimals?: number
  formula?: string
  startAt?: number
  columns?: SubformColumn[]
  lookup?: { formId: string; field: string }
  /** A note's text, or a section's subtitle. */
  content?: string
  /** A field made from a workflow step. */
  binding?: FieldBinding
  /** What the step's setting holds now; shown in the builder only, never on the public form. */
  stepValue?: string
  /** Made from a step and not accepted yet. */
  pending?: boolean
  /** A step layout's fields. */
  children?: FormField[]
  /** A step layout's step. */
  workflowId?: string
  workflowName?: string
  nodeId?: string
  stepLabel?: string
}

export interface FormSettings {
  submitLabel?: string
  successMessage?: string
}

export interface FieldSpec {
  type: FieldType
  label: string
  icon: LucideIcon
  group: 'basic' | 'advanced' | 'layout'
  /** What a new one starts with. */
  defaults?: Partial<FormField>
}

const CHOICES = { choices: ['Option 1', 'Option 2', 'Option 3'] }

/** The palette, in the order it shows: Basic Fields, then Advanced Fields and Layout. */
export const FIELD_SPECS: FieldSpec[] = [
  { type: 'name', label: 'Name', icon: User, group: 'basic' },
  { type: 'email', label: 'Email', icon: AtSign, group: 'basic' },
  { type: 'address', label: 'Address', icon: MapPin, group: 'basic' },
  { type: 'phone', label: 'Phone', icon: Phone, group: 'basic' },
  { type: 'single_line', label: 'Single Line', icon: Type, group: 'basic' },
  { type: 'multi_line', label: 'Multi Line', icon: AlignLeft, group: 'basic' },
  { type: 'number', label: 'Number', icon: Hash, group: 'basic' },
  { type: 'date', label: 'Date', icon: Calendar, group: 'basic' },
  { type: 'time', label: 'Time', icon: Clock, group: 'basic' },
  { type: 'dropdown', label: 'Drop Down', icon: ChevronDownSquare, group: 'basic', defaults: CHOICES },
  { type: 'radio', label: 'Radio', icon: Circle, group: 'basic', defaults: CHOICES },
  { type: 'multi_select', label: 'Multi Select', icon: ListChecks, group: 'basic', defaults: CHOICES },
  { type: 'checkbox', label: 'Checkbox', icon: CheckSquare, group: 'basic', defaults: CHOICES },

  { type: 'decision', label: 'Decision box', icon: SquareCheck, group: 'advanced', defaults: { label: 'I agree to the terms' } },
  { type: 'rich_text', label: 'Rich Text', icon: Baseline, group: 'advanced' },
  { type: 'url', label: 'Url', icon: Link2, group: 'advanced' },
  { type: 'image', label: 'Image', icon: ImageIcon, group: 'advanced' },
  { type: 'percent', label: 'Percent', icon: Percent, group: 'advanced' },
  { type: 'currency', label: 'Currency', icon: DollarSign, group: 'advanced', defaults: { currency: 'USD' } },
  { type: 'decimal', label: 'Decimal', icon: Hash, group: 'advanced', defaults: { decimals: 2 } },
  { type: 'datetime', label: 'Date-Time', icon: CalendarClock, group: 'advanced' },
  { type: 'subform', label: 'SubForm', icon: Rows3, group: 'advanced', defaults: { columns: [{ name: 'item', label: 'Item', type: 'single_line' }, { name: 'quantity', label: 'Quantity', type: 'number' }] } },
  { type: 'lookup', label: 'Lookup', icon: Search, group: 'advanced' },
  { type: 'signature', label: 'Signature', icon: PenLine, group: 'advanced' },
  { type: 'file', label: 'File upload', icon: FileUp, group: 'advanced' },
  { type: 'audio', label: 'Audio', icon: Mic, group: 'advanced' },
  { type: 'video', label: 'Video', icon: Video, group: 'advanced' },
  { type: 'auto_number', label: 'Auto Number', icon: ListOrdered, group: 'advanced', defaults: { startAt: 1 } },
  { type: 'formula', label: 'Formula', icon: FunctionSquare, group: 'advanced' },
  { type: 'qr_code', label: 'QR/Barcode', icon: QrCode, group: 'advanced' },

  { type: 'section', label: 'Section', icon: Heading, group: 'layout', defaults: { label: 'Section' } },
  { type: 'note', label: 'Add Notes', icon: StickyNote, group: 'layout', defaults: { label: 'Note', content: 'Add some text for the people filling in the form.' } },
]

export const SPEC_BY_TYPE = Object.fromEntries(FIELD_SPECS.map(s => [s.type, s])) as Record<FieldType, FieldSpec | undefined>

/** Types whose answer is picked from choices. */
export const CHOICE_TYPES = new Set<FieldType>(['dropdown', 'radio', 'multi_select', 'checkbox'])
/** Types Jizza fills in on submission. */
export const COMPUTED_TYPES = new Set<FieldType>(['auto_number', 'formula'])
/** Types that only lay the form out. */
export const LAYOUT_TYPES = new Set<FieldType>(['section', 'note', 'step_layout'])
/** Types whose answer is a file. */
export const FILE_TYPES = new Set<FieldType>(['image', 'signature', 'file', 'audio', 'video'])

export const DRAG_FIELD = 'application/x-jizza-form-field'
export const DRAG_STEP = 'application/x-jizza-form-step'
export const DRAG_MOVE = 'application/x-jizza-form-move'

let seq = 0
export function newId(prefix = 'f'): string {
  seq += 1
  return `${prefix}${Date.now().toString(36)}${seq.toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

/** A link name from a label: letters, digits and _, starting with a letter. */
export function linkNameOf(label: string): string {
  const words = label.normalize('NFKD').replace(/[^A-Za-z0-9 _]/g, ' ').trim().split(/[\s_]+/).filter(Boolean)
  let name = words.map((w, i) => (i === 0 ? w.toLowerCase() : w[0].toUpperCase() + w.slice(1).toLowerCase())).join('')
  if (!name) name = 'field'
  if (!/^[A-Za-z]/.test(name)) name = `f${name}`
  return name.slice(0, 60)
}

/** Every field, with the step layouts' children, in order. */
export function flatten(fields: FormField[]): FormField[] {
  return fields.flatMap(f => (f.type === 'step_layout' ? f.children ?? [] : [f]))
}

/** A link name not used by any other field. */
export function uniqueName(fields: FormField[], wanted: string, exceptId?: string): string {
  const taken = new Set(flatten(fields).filter(f => f.id !== exceptId && f.name).map(f => f.name!))
  if (!taken.has(wanted)) return wanted
  for (let i = 2; ; i++) if (!taken.has(`${wanted}${i}`)) return `${wanted}${i}`
}

/** A new field of {@code type}, named so it doesn't clash with {@code fields}. */
export function newField(type: FieldType, fields: FormField[]): FormField {
  const spec = SPEC_BY_TYPE[type]
  const label = spec?.defaults?.label ?? spec?.label ?? 'Field'
  const field: FormField = { id: newId(), type, label, ...(spec?.defaults ?? {}) }
  field.label = label
  if (!LAYOUT_TYPES.has(type)) field.name = uniqueName(fields, linkNameOf(label))
  return field
}

/** Applies {@code patch} to the field with {@code id}, wherever it is. */
export function updateField(fields: FormField[], id: string, patch: Partial<FormField>): FormField[] {
  return fields.map(f => {
    if (f.id === id) return { ...f, ...patch }
    if (f.type === 'step_layout' && f.children?.some(c => c.id === id)) {
      return { ...f, children: f.children.map(c => (c.id === id ? { ...c, ...patch } : c)) }
    }
    return f
  })
}

/** Takes out the field with {@code id}; a step layout left empty goes too. */
export function removeField(fields: FormField[], id: string): FormField[] {
  return fields.flatMap(f => {
    if (f.id === id) return []
    if (f.type === 'step_layout' && f.children?.some(c => c.id === id)) {
      const children = f.children.filter(c => c.id !== id)
      return children.length ? [{ ...f, children }] : []
    }
    return [f]
  })
}

export function findField(fields: FormField[], id: string | null | undefined): FormField | undefined {
  if (!id) return undefined
  for (const f of fields) {
    if (f.id === id) return f
    const child = f.children?.find(c => c.id === id)
    if (child) return child
  }
  return undefined
}

/** Puts {@code field} at {@code index} of the top-level list. */
export function insertAt(fields: FormField[], index: number, field: FormField): FormField[] {
  const next = [...fields]
  next.splice(Math.max(0, Math.min(index, next.length)), 0, field)
  return next
}

/**
 * Moves the field with {@code id} to {@code index} of the top level, or within its step layout
 * when it is one of a layout's fields (fields made from a step stay with their step).
 */
export function moveField(fields: FormField[], id: string, index: number): FormField[] {
  const at = fields.findIndex(f => f.id === id)
  if (at >= 0) {
    const next = [...fields]
    const [moved] = next.splice(at, 1)
    next.splice(Math.max(0, Math.min(index > at ? index - 1 : index, next.length)), 0, moved)
    return next
  }
  return fields
}

export function moveWithin(fields: FormField[], layoutId: string, id: string, index: number): FormField[] {
  return fields.map(f => {
    if (f.id !== layoutId || !f.children) return f
    const at = f.children.findIndex(c => c.id === id)
    if (at < 0) return f
    const children = [...f.children]
    const [moved] = children.splice(at, 1)
    children.splice(Math.max(0, Math.min(index > at ? index - 1 : index, children.length)), 0, moved)
    return { ...f, children }
  })
}

/** Accepts every pending field of a step layout. */
export function acceptAll(fields: FormField[], layoutId: string): FormField[] {
  return fields.map(f => (f.id === layoutId ? { ...f, children: f.children?.map(c => ({ ...c, pending: undefined })) } : f))
}

/** The fields still waiting to be accepted. */
export function pendingCount(fields: FormField[]): number {
  return flatten(fields).filter(f => f.pending).length
}

/** Problems that stop a save, by field id. */
export function problemsOf(fields: FormField[]): Map<string, string> {
  const problems = new Map<string, string>()
  const seen = new Map<string, string>()
  for (const f of flatten(fields)) {
    if (LAYOUT_TYPES.has(f.type)) continue
    if (!f.name || !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(f.name)) problems.set(f.id, 'Give it a link name of letters, digits and _, starting with a letter.')
    else if (seen.has(f.name)) problems.set(f.id, `Another field already has the link name ${f.name}.`)
    else seen.set(f.name, f.id)
    if (CHOICE_TYPES.has(f.type) && !(f.choices ?? []).some(c => c.trim())) problems.set(f.id, 'Add at least one choice.')
    if (f.type === 'subform' && !(f.columns ?? []).length) problems.set(f.id, 'Add at least one column.')
  }
  return problems
}

/** What the server is sent: blank choices dropped. */
export function cleanFields(fields: FormField[]): FormField[] {
  const clean = (f: FormField): FormField => ({
    ...f,
    ...(f.choices ? { choices: f.choices.map(c => c.trim()).filter(Boolean) } : {}),
    ...(f.children ? { children: f.children.map(clean) } : {}),
  })
  return fields.map(clean)
}

/** Whether people fill this field in (not layout, not computed). */
export function isInput(f: FormField): boolean {
  return !LAYOUT_TYPES.has(f.type) && !COMPUTED_TYPES.has(f.type)
}
