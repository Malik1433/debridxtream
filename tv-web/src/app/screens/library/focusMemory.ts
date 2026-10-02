/**
 * Where the viewer was in each list, so BACK from a detail page lands on the same poster instead of
 * the first one (TV rulebook: BACK goes up and you are where you left). In memory: a new session
 * starts at the top, which is what a TV viewer expects.
 */
const memory = new Map<string, number>()
export const remember = (key: string, index: number) => { memory.set(key, index) }
export const recall = (key: string): number => memory.get(key) ?? 0

/**
 * Set when a detail page closes: the list that remounts under it takes focus back to the poster it
 * opened from. Without it the App's focus guard - which runs first - parks focus on the sidebar.
 */
let returning = false
export const markReturn = () => { returning = true }
export const takeReturn = (): boolean => { const r = returning; returning = false; return r }
