import type { Platform } from './platform'

/** What a remote key means to the app, whichever TV sent it. */
export type AppKey = 'up' | 'down' | 'left' | 'right' | 'enter' | 'back' | 'play_pause' | 'ch_up' | 'ch_down' | null

/** Back differs per TV: Samsung 10009, LG 461, VIDAA 8 (and Escape on a PC browser). */
const BACK: Record<Platform, number[]> = {
  tizen: [10009],
  webos: [461],
  vidaa: [8, 27],
  browser: [8, 27],
}

/** Pure: keyCode (+ platform) -> meaning. Arrows/Enter are the same everywhere. */
export function appKey(keyCode: number, platform: Platform): AppKey {
  if (BACK[platform].includes(keyCode)) return 'back'
  switch (keyCode) {
    case 37: return 'left'
    case 38: return 'up'
    case 39: return 'right'
    case 40: return 'down'
    case 13: return 'enter'
    case 10252: case 415: case 19: case 179: return 'play_pause' // Samsung / LG play, pause, media
    case 427: case 33: return 'ch_up'
    case 428: case 34: return 'ch_down'
    default: return null
  }
}

/**
 * The spatial-navigation key map (arrows + OK). VIDAA's browser keeps the arrows for itself and
 * hands the page only digits (W0), so there 2/8/4/6/5 also move and select - a numpad D-pad.
 */
export function spatialKeyMap(platform: Platform) {
  const map = { left: [37], up: [38], right: [39], down: [40], enter: [13] }
  // W1 QA: the Hisense browser's UA identifies nothing, so it can arrive here as 'browser'.
  // Digits cost nothing on a real PC browser and are the only keys that TV hands the page.
  if (platform === 'vidaa' || platform === 'browser') {
    map.up.push(50); map.down.push(56); map.left.push(52); map.right.push(54); map.enter.push(53)
  }
  return map
}
