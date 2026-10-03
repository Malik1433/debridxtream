import { describe, expect, it } from 'vitest'
import { decodeLibrary, encodeLibrary } from './catalogueStore'
import type { Movie } from './vodApi'

const m = (i: number): Movie => ({ id: String(i), name: `Film ${i}`, categoryId: '7', poster: '', rating: 7.1, added: 1700000000 + i, ext: 'mkv', order: i })
describe('library record', () => {
  it('round-trips the items through one JSON string', () => {
    const items = [m(1), m(2)]
    const rec = encodeLibrary('movies', [{ id: '7', name: 'Action' }], items)
    expect(typeof rec.itemsJson).toBe('string')
    expect(decodeLibrary<Movie>(rec)).toEqual({ categories: [{ id: '7', name: 'Action' }], items })
  })
  it('still reads the record written before round 8 (items as objects)', () => {
    const old = { id: 'movies' as const, categories: [], items: [m(3)] }
    expect(decodeLibrary<Movie>(old).items).toEqual([m(3)])
  })
})
