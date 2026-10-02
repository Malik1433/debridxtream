import { describe, expect, it } from 'vitest'
import { appKey } from './keys'

describe('appKey', () => {
  it('reads each TV\'s own BACK', () => {
    expect(appKey(10009, 'tizen')).toBe('back')
    expect(appKey(461, 'webos')).toBe('back')
    expect(appKey(8, 'vidaa')).toBe('back')
    expect(appKey(10009, 'webos')).toBeNull()
  })

  it('reads the VIDAA numpad D-pad, and only where the arrows are taken', () => {
    expect(appKey(50, 'vidaa')).toBe('up')
    expect(appKey(56, 'vidaa')).toBe('down')
    expect(appKey(53, 'browser')).toBe('enter')
    expect(appKey(56, 'tizen')).toBeNull()
  })

  it('reads the yellow key as favourite and the channel keys as zap', () => {
    expect(appKey(405, 'tizen')).toBe('favourite')
    expect(appKey(427, 'tizen')).toBe('ch_up')
    expect(appKey(34, 'browser')).toBe('ch_down')
  })

  it('opens the debug panel on green, and on 0 where the TV hands the page only digits', () => {
    expect(appKey(404, 'tizen')).toBe('debug')
    expect(appKey(48, 'vidaa')).toBe('debug')
    expect(appKey(48, 'tizen')).toBeNull()
  })

  it('reads red as the A/B switch', () => {
    expect(appKey(403, 'tizen')).toBe('red')
  })
})
