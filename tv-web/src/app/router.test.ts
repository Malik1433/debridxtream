import { describe, expect, it } from 'vitest'
import { Router } from './router'
import { appKey, spatialKeyMap } from '../keys'

describe('Router', () => {
  it('goes up to home and then asks to leave', () => {
    const r = new Router()
    r.open('live')
    expect(r.current).toBe('live')
    r.open('settings') // a sibling section replaces, it does not stack
    expect(r.back()).toBe(true)
    expect(r.current).toBe('home')
    expect(r.back()).toBe(false)
  })
})

describe('appKey', () => {
  it('reads each TV\'s own back key', () => {
    expect(appKey(10009, 'tizen')).toBe('back')
    expect(appKey(461, 'webos')).toBe('back')
    expect(appKey(8, 'vidaa')).toBe('back')
    expect(appKey(10009, 'webos')).toBe(null)
  })
  it('maps the arrows, OK and channel keys the same everywhere', () => {
    expect(appKey(38, 'tizen')).toBe('up')
    expect(appKey(13, 'vidaa')).toBe('enter')
    expect(appKey(427, 'tizen')).toBe('ch_up')
  })
  it('gives VIDAA a numpad D-pad, and nobody else', () => {
    expect(spatialKeyMap('vidaa').up).toEqual([38, 50])
    expect(spatialKeyMap('vidaa').enter).toEqual([13, 53])
    expect(spatialKeyMap('tizen').up).toEqual([38])
  })

  it('push opens a screen over the current one, and BACK returns to it', () => {
    const r = new Router()
    r.open('movies'); r.push('search')
    expect(r.current).toBe('search')
    expect(r.back()).toBe(true)
    expect(r.current).toBe('movies')
  })
})
