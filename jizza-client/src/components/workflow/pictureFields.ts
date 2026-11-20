/**
 * Which lists of a "Choose from a list" step have a picture taken of each item, for the approval
 * card to show beside the question (`config.screenshots`).
 *
 * A step can ask for the item it chooses and, when it chooses inside a card of another list (a
 * class inside a train), for the cards too. Each is its own field with its own switch:
 *
 * - `true`: every list of the step, which is what the one switch every step had meant, so a
 *   step saved before there were two switches keeps working as it was;
 * - an object of the fields that ask: `{ item: true }`, `{ 'within.item': true }` or both;
 * - nothing: no pictures.
 */

/** The field names the run and the approval card use: the card (`within.item`), and the item. */
export type PictureField = 'item' | 'within.item'

export type Pictures = true | Partial<Record<PictureField, true>>

/** The fields a step has: the card only when it chooses inside one. */
export function pictureFields(inCard: boolean): PictureField[] {
  return inCard ? ['within.item', 'item'] : ['item']
}

/** Whether `field` has a picture taken of each of its items. */
export function wantsPictures(screenshots: unknown, field: PictureField): boolean {
  if (screenshots === true) return true
  if (typeof screenshots !== 'object' || screenshots === null) return false
  return (screenshots as Record<string, unknown>)[field] === true
}

/**
 * `config.screenshots` after `field` is switched on or off, kept as short as it can be: nothing
 * when no field asks, `true` when every field of the step does, the fields that do otherwise.
 */
export function withPictures(screenshots: unknown, field: PictureField, on: boolean, fields: readonly PictureField[]): Pictures | undefined {
  const asking = fields.filter(each => each === field ? on : wantsPictures(screenshots, each))
  if (asking.length === 0) return undefined
  if (asking.length === fields.length) return true
  return Object.fromEntries(asking.map(each => [each, true])) as Pictures
}
