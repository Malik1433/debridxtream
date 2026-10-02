import { describe, expect, it } from 'vitest'
import { cardText, cleanTitle } from './titles'

describe('titles (port of MediaTitleCleaner / parseVodCardText)', () => {
  it('strips provider tags, sites and escapes', () => {
    expect(cleanTitle('|MULTI| |EN| Dune Part Two')).toBe('Dune Part Two')
    expect(cleanTitle('[Bolly4u.limo] Jawan')).toBe('Jawan')
    expect(cleanTitle("Don\\'t Look Up |AR|")).toBe("Don't Look Up")
    expect(cleanTitle('www.1TamilBlasters.dad - Leo')).toBe('Leo')
  })
  it('reads the quality badge from a trailing or embedded tag', () => {
    expect(cardText('Oppenheimer [4K]')).toEqual({ title: 'Oppenheimer', quality: '4K' })
    expect(cardText('Barbie 1080p |EN')).toEqual({ title: 'Barbie', quality: 'FHD' })
    expect(cardText('|UHD| Wonka')).toEqual({ title: 'Wonka', quality: '4K' })
    expect(cardText('Plain Title')).toEqual({ title: 'Plain Title', quality: 'HD' })
  })
})
