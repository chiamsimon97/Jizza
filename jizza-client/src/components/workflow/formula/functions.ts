/**
 * The functions a formula can call, mirroring the engine's Formatters. Each takes the value it is
 * chained on as its first argument, so input.name.upper() and upper(input.name) are the same call.
 */

/** What a value is, as far as the editor can tell. Dates are text in ISO form, so text works as a date. */
export type ValueType = 'text' | 'number' | 'boolean' | 'date' | 'datetime' | 'list' | 'map' | 'any'

export type FunctionCategory = 'Text' | 'Number' | 'Date' | 'List' | 'Map' | 'Check'

export interface FormulaFunction {
  name: string
  category: FunctionCategory
  /** The value the function works on, which it can be chained on; absent for now() and today(). */
  subject?: { name: string; type: ValueType }
  params: Array<{ name: string; type: ValueType; optional?: boolean }>
  returns: ValueType
  description: string
  example: string
}

const text = (name = 'text') => ({ name, type: 'text' as const })
const num = (name = 'number') => ({ name, type: 'number' as const })
const date = (name = 'date') => ({ name, type: 'date' as const })
const list = (name = 'list') => ({ name, type: 'list' as const })
const any = (name = 'value') => ({ name, type: 'any' as const })
const map = (name = 'map') => ({ name, type: 'map' as const })

export const FORMULA_FUNCTIONS: FormulaFunction[] = [
  // text
  { name: 'upper', category: 'Text', subject: text(), params: [], returns: 'text', description: 'The text in capital letters.', example: "'hello'.upper() → HELLO" },
  { name: 'lower', category: 'Text', subject: text(), params: [], returns: 'text', description: 'The text in small letters.', example: "'Hello'.lower() → hello" },
  { name: 'trim', category: 'Text', subject: text(), params: [], returns: 'text', description: 'The text without spaces at either end.', example: "'  hi '.trim() → hi" },
  { name: 'titleCase', category: 'Text', subject: text(), params: [], returns: 'text', description: 'Each word starting with a capital letter.', example: "'asha rao'.titleCase() → Asha Rao" },
  { name: 'length', category: 'Text', subject: any(), params: [], returns: 'number', description: 'How many characters the text has, or how many items a list has.', example: "'hello'.length() → 5" },
  { name: 'contains', category: 'Text', subject: text(), params: [text('part')], returns: 'boolean', description: 'Whether the text has the part in it.', example: "'Gold rate'.contains('Gold') → true" },
  { name: 'startsWith', category: 'Text', subject: text(), params: [text('part')], returns: 'boolean', description: 'Whether the text begins with the part.', example: "'INV-12'.startsWith('INV') → true" },
  { name: 'endsWith', category: 'Text', subject: text(), params: [text('part')], returns: 'boolean', description: 'Whether the text ends with the part.', example: "'report.pdf'.endsWith('.pdf') → true" },
  { name: 'indexOf', category: 'Text', subject: text(), params: [text('part')], returns: 'number', description: 'Where the part first appears, counting from 0, or -1.', example: "'a-b'.indexOf('-') → 1" },
  { name: 'substring', category: 'Text', subject: text(), params: [num('start'), { ...num('end'), optional: true }], returns: 'text', description: 'Part of the text, from start (counting from 0) up to but not including end.', example: "'Jizza'.substring(0, 3) → Jiz" },
  { name: 'truncate', category: 'Text', subject: text(), params: [num('length')], returns: 'text', description: 'The text cut to at most this many characters.', example: "'Hello world'.truncate(5) → Hello" },
  { name: 'replace', category: 'Text', subject: text(), params: [text('find'), text('with')], returns: 'text', description: 'The text with every copy of one part replaced by another.', example: "'a-b-c'.replace('-', '/') → a/b/c" },
  { name: 'split', category: 'Text', subject: text(), params: [text('separator')], returns: 'list', description: 'The text cut into a list at each separator.', example: "'a, b'.split(',') → [a, b]" },
  { name: 'extractEmail', category: 'Text', subject: text(), params: [], returns: 'text', description: 'The first email address in the text.', example: "'Mail asha@x.in today'.extractEmail() → asha@x.in" },
  { name: 'extractNumber', category: 'Text', subject: text(), params: [], returns: 'number', description: 'The first number in the text.', example: "'Total ₹ 1,250'.extractNumber() → 1250" },
  { name: 'defaultIfEmpty', category: 'Text', subject: any(), params: [any('fallback')], returns: 'text', description: 'The value, or the fallback when it is blank.', example: "''.defaultIfEmpty('n/a') → n/a" },
  { name: 'toText', category: 'Text', subject: any(), params: [], returns: 'text', description: 'Any value as text.', example: '42.toText() → "42"' },
  // numbers
  { name: 'toNumber', category: 'Number', subject: any(), params: [], returns: 'number', description: 'Reads a number out of text such as "₹ 1,234.50".', example: "'₹ 1,234.50'.toNumber() → 1234.5" },
  { name: 'round', category: 'Number', subject: num(), params: [num('places')], returns: 'number', description: 'The number rounded to this many decimal places.', example: '3.14159.round(2) → 3.14' },
  { name: 'formatNumber', category: 'Number', subject: num(), params: [num('places')], returns: 'text', description: 'The number with grouped digits.', example: '123456.5.formatNumber(2) → 1,23,456.50' },
  { name: 'formatCurrency', category: 'Number', subject: num(), params: [text('currency')], returns: 'text', description: 'The number as money in a currency, such as INR or USD.', example: "1500.formatCurrency('INR') → ₹1,500.00" },
  { name: 'abs', category: 'Number', subject: num(), params: [], returns: 'number', description: 'The number without its sign.', example: '-4.abs() → 4' },
  { name: 'ceil', category: 'Number', subject: num(), params: [], returns: 'number', description: 'The number rounded up.', example: '4.2.ceil() → 5' },
  { name: 'floor', category: 'Number', subject: num(), params: [], returns: 'number', description: 'The number rounded down.', example: '4.8.floor() → 4' },
  { name: 'min', category: 'Number', subject: num('a'), params: [num('b')], returns: 'number', description: 'The smaller of two numbers.', example: 'min(3, 7) → 3' },
  { name: 'max', category: 'Number', subject: num('a'), params: [num('b')], returns: 'number', description: 'The larger of two numbers.', example: 'max(3, 7) → 7' },
  // dates
  { name: 'now', category: 'Date', params: [], returns: 'date', description: 'The current date and time.', example: 'now() → 2026-10-22T09:30+05:30' },
  { name: 'today', category: 'Date', params: [], returns: 'date', description: "Today's date.", example: 'today() → 2026-10-22' },
  { name: 'formatDate', category: 'Date', subject: date(), params: [text('pattern'), { ...text('timeZone'), optional: true }], returns: 'text', description: 'The date written in a pattern, such as dd MMM yyyy or HH:mm.', example: "today().formatDate('dd MMM yyyy') → 22 Oct 2026" },
  { name: 'parseDate', category: 'Date', subject: text(), params: [text('pattern')], returns: 'date', description: 'Reads a date written in a pattern.', example: "'22/10/2026'.parseDate('dd/MM/yyyy')" },
  { name: 'addDays', category: 'Date', subject: date(), params: [num('days')], returns: 'date', description: 'The date this many days later.', example: 'today().addDays(7)' },
  { name: 'subtractDays', category: 'Date', subject: date(), params: [num('days')], returns: 'date', description: 'The date this many days earlier.', example: 'today().subtractDays(1)' },
  { name: 'addMonths', category: 'Date', subject: date(), params: [num('months')], returns: 'date', description: 'The date this many months later.', example: 'today().addMonths(1)' },
  { name: 'addHours', category: 'Date', subject: date(), params: [num('hours')], returns: 'date', description: 'The time this many hours later.', example: 'now().addHours(2)' },
  { name: 'addMinutes', category: 'Date', subject: date(), params: [num('minutes')], returns: 'date', description: 'The time this many minutes later.', example: 'now().addMinutes(30)' },
  { name: 'addTime', category: 'Date', subject: date(), params: [any('amount')], returns: 'date', description: 'The date moved by an ISO duration such as P3D or PT2H, or by days.', example: "now().addTime('PT2H')" },
  { name: 'daysBetween', category: 'Date', subject: date('from'), params: [date('to')], returns: 'number', description: 'Whole days from one date to another.', example: "'2026-10-01'.daysBetween('2026-10-22') → 21" },
  // lists
  { name: 'count', category: 'List', subject: list(), params: [], returns: 'number', description: 'How many items the list has.', example: '[1, 2, 3].count() → 3' },
  { name: 'first', category: 'List', subject: list(), params: [], returns: 'any', description: 'The first item.', example: '[1, 2, 3].first() → 1' },
  { name: 'last', category: 'List', subject: list(), params: [], returns: 'any', description: 'The last item.', example: '[1, 2, 3].last() → 3' },
  { name: 'join', category: 'List', subject: list(), params: [text('separator')], returns: 'text', description: 'The items as text with a separator between them.', example: "['a', 'b'].join(', ') → a, b" },
  { name: 'sum', category: 'List', subject: list(), params: [], returns: 'number', description: 'The items added up.', example: '[1, 2, 3].sum() → 6' },
  { name: 'pluck', category: 'List', subject: list(), params: [text('field')], returns: 'list', description: 'One field from every item, such as every line item\'s price.', example: "items.pluck('price')" },
  { name: 'unique', category: 'List', subject: list(), params: [], returns: 'list', description: 'The items without repeats.', example: '[1, 1, 2].unique() → [1, 2]' },
  { name: 'sort', category: 'List', subject: list(), params: [], returns: 'list', description: 'The items in order.', example: '[3, 1, 2].sort() → [1, 2, 3]' },
  { name: 'reverse', category: 'List', subject: list(), params: [], returns: 'list', description: 'The items back to front.', example: '[1, 2, 3].reverse() → [3, 2, 1]' },
  { name: 'where', category: 'List', subject: list(), params: [text('field'), any('value')], returns: 'list', description: 'The items whose field has this value, such as the paid invoices.', example: "items.where('status', 'paid')" },
  { name: 'sortBy', category: 'List', subject: list(), params: [text('field')], returns: 'list', description: 'The items in order of one field. Add .reverse() for largest first.', example: "items.sortBy('amount')" },
  { name: 'sumOf', category: 'List', subject: list(), params: [text('field')], returns: 'number', description: 'One field of every item added up.', example: "items.sumOf('amount') → 52.5" },
  { name: 'groupBy', category: 'List', subject: list(), params: [text('field')], returns: 'map', description: 'The items in groups by one field, as a map from each value to its items.', example: "items.groupBy('status')" },
  // maps
  { name: 'pick', category: 'Map', subject: map(), params: [any('keys')], returns: 'map', description: 'Only some keys of a map: a list, or names with commas.', example: "customer.pick('name, email')" },
  { name: 'merge', category: 'Map', subject: map('a'), params: [map('b')], returns: 'map', description: 'Two maps as one. Where both have a key, the second wins.', example: "merge(customer, {'vip': true})" },
  { name: 'keys', category: 'Map', subject: map(), params: [], returns: 'list', description: 'The names of the keys in a map.', example: "{'a': 1, 'b': 2}.keys() → [a, b]" },
  { name: 'values', category: 'Map', subject: map(), params: [], returns: 'list', description: 'The values in a map.', example: "{'a': 1, 'b': 2}.values() → [1, 2]" },
  { name: 'lookup', category: 'Map', subject: { name: 'table', type: 'map' }, params: [text('key'), any('fallback')], returns: 'any', description: 'The value for a key in a table, or the fallback.', example: "{'IN': 'India'}.lookup('IN', '?') → India" },
  // checks
  { name: 'isEmpty', category: 'Check', subject: any(), params: [], returns: 'boolean', description: 'Whether the value is missing, blank or an empty list.', example: "''.isEmpty() → true" },
  { name: 'isNotEmpty', category: 'Check', subject: any(), params: [], returns: 'boolean', description: 'Whether the value has something in it.', example: "'x'.isNotEmpty() → true" },
]

/** The engine's own built-ins, which formulas may call but the editor doesn't offer. */
const BUILT_INS: FormulaFunction[] = [
  { name: 'size', category: 'List', subject: any(), params: [], returns: 'number', description: 'How many items or characters.', example: 'size(list)' },
  { name: 'empty', category: 'Check', subject: any(), params: [], returns: 'boolean', description: 'Whether the value is empty.', example: 'empty(value)' },
]

const BY_NAME = new Map([...FORMULA_FUNCTIONS, ...BUILT_INS].map(f => [f.name, f]))

export function functionNamed(name: string): FormulaFunction | undefined {
  return BY_NAME.get(name)
}

/** Whether a value of type {@code actual} fits where {@code expected} is wanted. */
export function fits(expected: ValueType, actual: ValueType): boolean {
  if (expected === 'any' || actual === 'any' || expected === actual) return true
  // Dates are ISO text, and a date is fine wherever text is.
  const dated = (t: ValueType) => t === 'date' || t === 'datetime'
  if ((dated(expected) && (actual === 'text' || dated(actual))) || (expected === 'text' && dated(actual))) return true
  return false
}

const TYPE_NAMES: Record<ValueType, string> = { text: 'text', number: 'a number', boolean: 'true/false', date: 'a date', datetime: 'a date and time', list: 'a list', map: 'a map', any: 'anything' }

export function typeName(t: ValueType): string {
  return TYPE_NAMES[t]
}

/** How a function reads, chained: text.substring(start, end?) → text. */
export function signature(f: FormulaFunction): string {
  const params = f.params.map(p => `${p.name}${p.optional ? '?' : ''}`).join(', ')
  return `${f.subject ? `${f.subject.name}.` : ''}${f.name}(${params}) → ${f.returns}`
}
