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

describe('library chunks (round 10)', () => {
  it('splits into 5,000-item JSON chunks that join back to the same list', async () => {
    const { CHUNK, encodeChunks } = await import('./catalogueStore')
    const items = Array.from({ length: CHUNK * 2 + 7 }, (_, i) => m(i))
    const { head, chunks } = encodeChunks('movies', [], items)
    expect(head).toMatchObject({ id: 'movies', chunks: 3, count: items.length })
    expect(chunks.map((c) => c.id)).toEqual(['movies#0', 'movies#1', 'movies#2'])
    expect(chunks.flatMap((c) => JSON.parse(c.json))).toEqual(items)
  })
})
