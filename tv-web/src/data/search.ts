/**
 * Search across Live, Movies and Series by name. Local - the lists are already on the TV - so it is
 * instant and costs the provider nothing. Accent- and case-blind, every word must appear, and a name
 * that STARTS with the query ranks first.
 */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
}

export function searchByName<T extends { name: string }>(items: T[], query: string, limit = 60): T[] {
  const q = fold(query).trim()
  if (q.length < 2) return []
  const words = q.split(/\s+/)
  const starts: T[] = []
  const rest: T[] = []
  for (const it of items) {
    const n = fold(it.name)
    if (!words.every((w) => n.includes(w))) continue
    ;(n.startsWith(q) ? starts : rest).push(it)
    if (starts.length >= limit) break
  }
  return [...starts, ...rest].slice(0, limit)
}
