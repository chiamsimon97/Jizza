import { describe, expect, it } from 'vitest'
import { pictureFields, wantsPictures, withPictures } from './pictureFields'

describe('which lists of a step have pictures', () => {
  it('a step has the card field only when it chooses inside a card', () => {
    expect(pictureFields(false)).toEqual(['item'])
    expect(pictureFields(true)).toEqual(['within.item', 'item'])
  })

  it('true is every list: the one switch a step had before there were two', () => {
    expect(wantsPictures(true, 'item')).toBe(true)
    expect(wantsPictures(true, 'within.item')).toBe(true)
  })

  it('an object is the fields that ask, and nothing else', () => {
    expect(wantsPictures({ item: true }, 'item')).toBe(true)
    expect(wantsPictures({ item: true }, 'within.item')).toBe(false)
    expect(wantsPictures({ 'within.item': true }, 'item')).toBe(false)
  })

  it('nothing, false and anything else is no pictures', () => {
    for (const none of [undefined, null, false, 'yes', 1, {}, { item: false }]) {
      expect(wantsPictures(none, 'item')).toBe(false)
      expect(wantsPictures(none, 'within.item')).toBe(false)
    }
  })
})

describe('switching one field', () => {
  const both = pictureFields(true)

  it('turns one on, leaving the other as it was', () => {
    expect(withPictures(undefined, 'item', true, both)).toEqual({ item: true })
    expect(withPictures(undefined, 'within.item', true, both)).toEqual({ 'within.item': true })
  })

  it('turns every field on as true', () => {
    expect(withPictures({ item: true }, 'within.item', true, both)).toBe(true)
  })

  it('turns the last one off as nothing', () => {
    expect(withPictures({ item: true }, 'item', false, both)).toBeUndefined()
  })

  it('splits true into the fields left when one is turned off', () => {
    expect(withPictures(true, 'within.item', false, both)).toEqual({ item: true })
    expect(withPictures(true, 'item', false, both)).toEqual({ 'within.item': true })
  })

  it('a step with one field keeps the single true it always had', () => {
    expect(withPictures(undefined, 'item', true, ['item'])).toBe(true)
    expect(withPictures(true, 'item', false, ['item'])).toBeUndefined()
  })

  it('turning on what is on, or off what is off, changes nothing', () => {
    expect(withPictures({ item: true }, 'item', true, both)).toEqual({ item: true })
    expect(withPictures(undefined, 'item', false, both)).toBeUndefined()
  })
})
