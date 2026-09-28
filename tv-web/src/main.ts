import { detectPlatform, deviceId, exitApp, registerKeys } from './platform'
import { PlayMetrics } from './metrics'
import { MpegtsPlayer, nativePlayer, type TestPlayer } from './players'
import { apiUrl, liveUrl, redact, type W0Config } from './xtream'

/**
 * W0 (docs/reports/WEB_TV_APP_DESIGN.md §8): one page that answers, on the real TV, the questions
 * the design cannot answer from a desk - can we reach the provider (CORS), can the TV's own player
 * and mpegts.js play a live `.ts`, does Firebase sign in, what is the device id, which keys arrive.
 * Everything shown is also logged with a "W0 " prefix. No credential or host ever reaches the screen.
 */
const platform = detectPlatform()
const rowsEl = document.getElementById('rows') as HTMLElement
const playerEl = document.getElementById('player') as HTMLElement
const keysEl = document.getElementById('keys') as HTMLElement
const panel = document.getElementById('panel') as HTMLElement

let config: W0Config | null = null
const safe = (s: string) => (config ? redact(s, config) : s)

function row(label: string, state: 'ok' | 'bad' | 'wait', text: string): void {
  const id = `row-${label.replace(/\W/g, '')}`
  let el = document.getElementById(id)
  if (!el) {
    el = document.createElement('div')
    el.id = id
    rowsEl.appendChild(el)
  }
  const mark = state === 'ok' ? '✔' : state === 'bad' ? '✘' : '…'
  el.innerHTML = `<span class="${state}">${mark}</span> <b>${label}:</b> ${escapeHtml(safe(text))}`
  console.log(`W0 ${label}: ${state} ${safe(text)}`)
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] as string))
}

async function loadConfig(): Promise<W0Config | null> {
  try {
    const r = await fetch('./w0.local.json', { cache: 'no-store' })
    if (!r.ok) throw new Error(`HTTP ${r.status}`)
    return (await r.json()) as W0Config
  } catch (e) {
    row('Test account', 'bad', `w0.local.json not found (${String(e)})`)
    return null
  }
}

async function probeProvider(c: W0Config): Promise<string[]> {
  row('Provider API (CORS)', 'wait', 'asking player_api.php…')
  try {
    const t0 = Date.now()
    const r = await fetch(apiUrl(c))
    const body = await r.json()
    const ui = body?.user_info ?? {}
    row('Provider API (CORS)', r.ok ? 'ok' : 'bad',
      `HTTP ${r.status} in ${Date.now() - t0} ms · status=${ui.status} · max_connections=${ui.max_connections} · formats=${JSON.stringify(ui.allowed_output_formats)}`)
  } catch (e) {
    row('Provider API (CORS)', 'bad', `blocked or failed: ${String(e)} (a TypeError here is the CORS block)`)
    return c.streamIds ?? []
  }
  if (c.streamIds?.length) return c.streamIds
  try {
    const list = (await (await fetch(apiUrl(c, 'get_live_streams'))).json()) as Array<{ stream_id: number }>
    row('Live list', 'ok', `${list.length} channels`)
    return list.slice(0, 20).map((s) => String(s.stream_id))
  } catch (e) {
    row('Live list', 'bad', String(e))
    return []
  }
}

async function probeFirebase(): Promise<void> {
  row('Firebase', 'wait', 'anonymous sign-in…')
  try {
    const { initializeApp } = await import('firebase/app')
    const { getAuth, signInAnonymously } = await import('firebase/auth')
    const app = initializeApp({
      apiKey: 'AIzaSyDpBUBq_GowUtJVEsV61lX60804DBt7V4A',
      authDomain: 'debridxtream-new.firebaseapp.com',
      projectId: 'debridxtream-new',
      appId: '1:617090864361:web:e6876bf871a578d5c54c78',
    })
    const cred = await signInAnonymously(getAuth(app))
    row('Firebase', 'ok', `signed in, uid ${cred.user.uid.slice(0, 6)}…`)
  } catch (e) {
    row('Firebase', 'bad', String(e))
  }
}

// ── players ──
const metrics = new PlayMetrics(() => Date.now())
const report = (msg: string) => row('Player event', 'bad', msg)
const players: Record<string, () => TestPlayer> = {
  native: () => nativePlayer(platform, metrics, report),
  mpegts: () => new MpegtsPlayer(metrics, report),
}
let current: TestPlayer | null = null
let channels: string[] = []
let index = 0
let speedTried = false

function start(kind: 'native' | 'mpegts'): void {
  if (!config || !channels.length) return
  current?.stop()
  current = players[kind]()
  speedTried = false
  current.play(liveUrl(config, channels[index]))
  console.log(`W0 play ${current.name} channel #${index + 1} (${channels[index]})`)
}

function zap(step: number): void {
  if (!current || !channels.length) return
  index = (index + step + channels.length) % channels.length
  const kind = current instanceof MpegtsPlayer ? 'mpegts' : 'native'
  start(kind)
}

setInterval(() => {
  if (!current) { playerEl.textContent = 'Player: stopped'; return }
  const ahead = current.bufferedAheadMs()
  playerEl.innerHTML = `<b>${current.name}</b> · channel ${index + 1}/${channels.length} (id ${channels[index]})<br>` +
    `${metrics.summary()} · buffer ahead ${ahead === null ? 'n/a' : `${(ahead / 1000).toFixed(1)} s`}`
  // Slow-fill check (design §5): after 20 s of playing, ask for 0.97x once, then put 1.0x back.
  if (!speedTried && metrics.playedMs > 20_000) {
    speedTried = true
    row('Slow-fill 0.97x', 'wait', current.trySpeed(0.97))
    setTimeout(() => current && row('Slow-fill back to 1.0x', 'ok', current.trySpeed(1)), 5000)
  }
}, 1000)

document.addEventListener('keydown', (e) => {
  keysEl.textContent = `Last key: code=${e.keyCode} key=${e.key}`
  console.log(`W0 key ${e.keyCode} ${e.key}`)
  switch (e.keyCode) {
    case 49: case 403: start('native'); break // 1 / red
    case 50: case 404: start('mpegts'); break // 2 / green
    case 48: current?.stop(); current = null; break // 0
    case 57: panel.style.display = panel.style.display === 'none' ? 'block' : 'none'; break // 9
    case 38: case 427: zap(1); break // up / channel up
    case 40: case 428: zap(-1); break // down / channel down
    case 10009: case 461: case 8: // back: Samsung / LG / VIDAA
      if (current) { current.stop(); current = null } else exitApp(platform)
      e.preventDefault()
      break
  }
})

async function run(): Promise<void> {
  row('Platform', 'ok', `${platform} · ${navigator.userAgent}`)
  const id = deviceId(platform)
  row('Device id', id ? 'ok' : 'bad', id ? `${id.slice(0, 6)}… (${id.length} chars)` : 'not available')
  row('Remote keys', 'ok', registerKeys(platform))
  row('MSE (for mpegts.js)', typeof MediaSource !== 'undefined' ? 'ok' : 'bad',
    typeof MediaSource !== 'undefined' ? `ts in MSE: ${MediaSource.isTypeSupported('video/mp4; codecs="avc1.640028"')}` : 'no MediaSource')
  config = await loadConfig()
  if (!config) return
  row('Test account', 'ok', 'loaded')
  channels = await probeProvider(config)
  void probeFirebase()
  row('Ready', channels.length ? 'ok' : 'bad', channels.length ? 'press 1 (TV player) or 2 (mpegts.js)' : 'no channels to play')
}

void run()
