/**
 * The k items with the highest key, highest first, in one pass - instead of sorting the whole
 * catalogue to keep ten (W4 QA round 8: `memo:home-top-movies` cost 1212 ms on the Samsung, a full
 * sort of 69,500 films). Ties keep catalogue order, exactly as the stable sort it replaces did.
 */
export function topBy<T>(items: readonly T[], k: number, key: (t: T) => number): T[] {
  const out: T[] = []
  const keys: number[] = []
  for (const x of items) {
    const v = key(x)
    if (out.length === k && v <= keys[k - 1]) continue
    let j = out.length
    while (j > 0 && keys[j - 1] < v) j--
    out.splice(j, 0, x); keys.splice(j, 0, v)
    if (out.length > k) { out.pop(); keys.pop() }
  }
  return out
}
