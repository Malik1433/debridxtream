/* eslint-disable @typescript-eslint/no-explicit-any */
declare const tizen: any
declare const webapis: any

export type Platform = 'tizen' | 'vidaa' | 'webos' | 'browser'

export function detectPlatform(): Platform {
  if (typeof tizen !== 'undefined') return 'tizen'
  const ua = navigator.userAgent
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
  const wanted = ['0', '1', '2', '9', 'MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop',
    'ColorF0Red', 'ColorF1Green', 'ChannelUp', 'ChannelDown']
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
