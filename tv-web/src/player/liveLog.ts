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
  listeners.forEach((l) => l())
}

export function liveLogLines(): readonly string[] { return lines }

export function onLiveLog(cb: () => void): () => void { listeners.add(cb); return () => listeners.delete(cb) }
