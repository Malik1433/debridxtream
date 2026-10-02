import { describe, expect, it } from 'vitest'
import type { PlayerError } from './PlayerAdapter'
import { LiveMediaSwitch, type FallbackMedia, type PrimaryMedia } from './liveMediaSwitch'

class Fake {
  loads: string[] = []; open = false; err: (e: PlayerError) => void = () => undefined
  info: (i: { video?: string; audio?: string }) => void = () => undefined
  constructor(readonly name: string) {}
  load(url: string) { this.loads.push(url); this.open = true }
  play() {} pause() {}
  stop() { this.open = false }
  positionMs() { return 0 }
  bufferedAheadMs() { return 1000 }
  setSpeed() { return true }
  onError(cb: (e: PlayerError) => void) { this.err = cb }
  onMediaInfo(cb: (i: { video?: string; audio?: string }) => void) { this.info = cb }
  mediaInfo() { return 'avc1 / mp3' }
}

const aacOnly = (c: string | undefined) => !c || c.startsWith('mp4a')

function rig(withFallback = true, playable: (c: string | undefined) => boolean = aacOnly) {
  const mse = new Fake('mpegts.js'), av = new Fake('AVPlay')
  const logs: string[] = [], players: string[] = [], errors: PlayerError[] = [], learned: Array<string | undefined> = []
  const sw = new LiveMediaSwitch(mse as PrimaryMedia, withFallback ? (av as FallbackMedia) : null, playable, (l) => logs.push(l), (n) => players.push(n), (c) => learned.push(c))
  sw.onError((e) => errors.push(e))
  return { mse, av, sw, logs, players, errors, learned }
}

describe('LiveMediaSwitch', () => {
  it('keeps an AAC channel on mpegts.js', () => {
    const r = rig()
    r.sw.load('u1'); r.mse.info({ video: 'avc1', audio: 'mp4a.40.2' })
    expect(r.sw.playerName()).toBe('mpegts.js')
    expect(r.sw.reportsBuffer()).toBe(true)
    expect(r.av.loads).toEqual([])
  })

  it('moves an MP3 channel to AVPlay at once, closing mpegts.js first, and remembers it', () => {
    const r = rig()
    r.sw.load('u1'); r.mse.info({ video: 'avc1', audio: 'mp3' })
    expect(r.mse.open).toBe(false)
    expect(r.av.loads).toEqual(['u1'])
    expect(r.sw.reportsBuffer()).toBe(false)
    expect(r.sw.bufferedAheadMs()).toBeNull()
    expect(r.players).toEqual(['AVPlay'])
    r.sw.load('u2')
    expect(r.sw.playerName()).toBe('mpegts.js')
    r.sw.load('u1')
    expect(r.mse.loads).toEqual(['u1', 'u2']) // straight to AVPlay the second time
    expect(r.av.loads).toEqual(['u1', 'u1'])
  })

  it('only the active player may report an error', () => {
    const r = rig()
    r.sw.load('u1'); r.mse.info({ audio: 'ac-3' })
    r.mse.err({ network: true, message: 'late mpegts error' })
    expect(r.errors).toEqual([])
    r.av.err({ network: true, message: 'AVPlay PLAYER_ERROR_CONNECTION_FAILED' })
    expect(r.errors.map((e) => e.message)).toEqual(['AVPlay PLAYER_ERROR_CONNECTION_FAILED'])
  })

  it('tries the fallback once when the connect timeout finds no picture', () => {
    const r = rig()
    r.sw.load('u1')
    expect(r.sw.tryAlternative()).toBe(true)
    expect(r.av.loads).toEqual(['u1'])
    expect(r.sw.tryAlternative()).toBe(false)
  })

  it('without a fallback, unplayable audio fails fast and says why', () => {
    const r = rig(false)
    r.sw.load('u1'); r.mse.info({ audio: 'mp3' })
    expect(r.errors).toEqual([{ network: false, message: 'audio mp3 is not supported on this TV' }])
    expect(r.sw.tryAlternative()).toBe(false)
  })

  it('when MSE claimed it could take the audio and nothing played: prefer the other player, and learn the codec', () => {
    const r = rig(true, () => true) // the Samsung answer for audio/mpeg
    r.sw.load('u1'); r.mse.info({ video: 'avc1', audio: 'mp3' })
    expect(r.sw.playerName()).toBe('mpegts.js')
    expect(r.sw.prefersAlternative()).toBe(true)
    expect(r.sw.tryAlternative()).toBe(true)
    expect(r.learned).toEqual(['mp3'])
    expect(r.sw.prefersAlternative()).toBe(false)
    r.sw.load('u2'); r.mse.info({ video: 'avc1', audio: 'mp4a.40.2' })
    expect(r.sw.prefersAlternative()).toBe(false) // AAC is never pushed off mpegts.js
  })
})
