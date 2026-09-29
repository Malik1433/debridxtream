import { describe, expect, it } from 'vitest'
import { Router } from './router'
import { appKey } from '../keys'

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
})
