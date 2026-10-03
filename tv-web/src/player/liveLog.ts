/**
 * The `[live]` lines, kept in memory as well as on the console. A retail Samsung gives no logs at
 * all (W3 QA: `sdb shell` silent, no inspector port), so the on-screen debug panel is the only way
 * a QA run - or a customer on the phone with us - can read what the player did.
 */
const MAX_LINES = 40
const lines: string[] = []
const listeners = new Set<() => void>()

export function liveLog(line: string): void {
  const d = new Date()
  const stamp = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
  lines.push(`${stamp} ${line}`)
  if (lines.length > MAX_LINES) lines.splice(0, lines.length - MAX_LINES)
  console.info(`[live] ${line}`)
  if (KEEP.test(line)) keep(`${stamp} ${line}`)
  listeners.forEach((l) => l())
}

/**
 * W4 QA round 6: "Reconnecting" over and over. The lines that tell a broken stream from a frozen
 * picture we reopened ourselves are also KEPT in storage - they survive the screen, a restart and a
 * kill, so nobody has to photograph the panel at the right second.
 */
const KEEP = /^(live error|live: (first picture|picture back|picture frozen|picture still frozen|no picture|picture still on)|picture: |player: |interruptions )/
const KEPT_KEY = 'dx.livelog'
export const KEPT_MAX = 40

function keep(line: string): void {
  // Never an address in storage: an Xtream URL carries the account (CLAUDE.md).
  const safe = line.replace(/https?:\/\/\S+/g, '<url>')
  try { localStorage.setItem(KEPT_KEY, JSON.stringify([...keptLines(), safe].slice(-KEPT_MAX))) } catch { /* no storage: the panel still has it */ }
}

export function keptLines(): string[] {
  try { const v = JSON.parse(localStorage.getItem(KEPT_KEY) ?? '[]'); return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [] } catch { return [] }
}

export function liveLogLines(): readonly string[] { return lines }

export function onLiveLog(cb: () => void): () => void { listeners.add(cb); return () => listeners.delete(cb) }
