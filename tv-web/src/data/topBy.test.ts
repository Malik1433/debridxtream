import { describe, expect, it } from 'vitest'
import { topBy } from './topBy'

describe('topBy', () => {
  it('equals a stable full sort cut to k, ties in catalogue order', () => {
    const xs = Array.from({ length: 5000 }, (_, i) => ({ id: i, added: (i * 7919) % 97 }))
    const want = [...xs].sort((a, b) => b.added - a.added).slice(0, 10)
    expect(topBy(xs, 10, (x) => x.added)).toEqual(want)
  })
  it('handles fewer items than k, and k = 0', () => {
    expect(topBy([{ a: 1 }, { a: 3 }], 10, (x) => x.a)).toEqual([{ a: 3 }, { a: 1 }])
    expect(topBy([{ a: 1 }], 0, (x) => x.a)).toEqual([])
  })
})
