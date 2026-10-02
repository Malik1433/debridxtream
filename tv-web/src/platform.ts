/* eslint-disable @typescript-eslint/no-explicit-any */
declare const tizen: any
declare const webapis: any

export type Platform = 'tizen' | 'vidaa' | 'webos' | 'browser'

export function detectPlatform(): Platform {
  if (typeof tizen !== 'undefined') return 'tizen'
  const ua = navigator.userAgent
  // W1 QA: this Hisense's browser UA says only "X11; Linux armv7l ... Chrome/111" - no VIDAA,
  // no Hisense - so the UA test alone made it 'browser' and the numpad D-pad never applied.
  // The VIDAA-only global is the reliable tell.
  if (typeof (window as any).Hisense_GetDeviceID === 'function') return 'vidaa'
  if (/VIDAA|Hisense/i.test(ua)) return 'vidaa'
  if (/Web0S|webOS/i.test(ua)) return 'webos'
  return 'browser'
}

/** The TV's own permanent id - the licence key's future source (design §6). */
export function deviceId(p: Platform): string | null {
  try {
    if (p === 'tizen') return webapis.productinfo.getDuid()
    const w = window as any
    if (p === 'vidaa' && typeof w.Hisense_GetDeviceID === 'function') return w.Hisense_GetDeviceID()
  } catch {
    return null
  }
  return null
}

/** Samsung delivers only a few keys unless the app registers the rest. */
export function registerKeys(p: Platform): string {
  if (p !== 'tizen') return 'not needed'
  const wanted = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'MediaPlayPause', 'MediaPlay', 'MediaPause',
    'MediaStop', 'MediaFastForward', 'MediaRewind', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ChannelUp', 'ChannelDown']
  const done: string[] = []
  for (const k of wanted) {
    try { tizen.tvinputdevice.registerKey(k); done.push(k) } catch { /* not on this model */ }
  }
  return `${done.length}/${wanted.length} registered`
}

export function exitApp(p: Platform): void {
  try {
    if (p === 'tizen') tizen.application.getCurrentApplication().exit()
    else window.close()
  } catch { /* stay */ }
}


/**
 * The TV's screensaver must not cover a film (Samsung). Off while something plays, back on after.
 * Elsewhere a playing <video> already keeps the screen awake.
 */
export function setScreenSaver(p: Platform, on: boolean): void {
  if (p !== 'tizen') return
  try {
    const ac = webapis.appcommon
    ac.setScreenSaver(on ? ac.AppCommonScreenSaverState.SCREEN_SAVER_ON : ac.AppCommonScreenSaverState.SCREEN_SAVER_OFF)
  } catch { /* no appcommon on this model */ }
}
