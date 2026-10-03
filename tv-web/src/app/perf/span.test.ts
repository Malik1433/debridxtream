import { describe, expect, it } from 'vitest'
import { label, span } from './span'

describe('span', () => {
  it('names the work, not its data', () => {
    expect(label('memo:lib-sorted-movies-__all-recent')).toBe('memo:lib-sorted-movies-__all-recent')
    expect(label('memo:home-top-movies:69536')).toBe('memo:home-top-movies')
    expect(label('memo:lib-rows-movies:1,2,3')).toBe('memo:lib-rows-movies')
    expect(label('load:lib-items-movies')).toBe('load:lib-items-movies')
  })
  it('returns the value and records a measure', () => {
    expect(span('memo:x', () => 42)).toBe(42)
    expect(performance.getEntriesByName('dx:memo:x').length).toBeGreaterThan(0)
  })
})
