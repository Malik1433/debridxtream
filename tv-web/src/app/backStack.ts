/**
 * BACK for things inside a screen (fullscreen player, an open panel): the innermost handler that
 * wants it takes it, before the App's own "go up a screen". Keeps BACK meaning "up one level" at
 * every depth (TV rulebook) without each screen fighting the App for the key.
 */
const handlers: Array<() => boolean> = []

export function pushBackHandler(h: () => boolean): () => void {
  handlers.push(h)
  return () => { const i = handlers.lastIndexOf(h); if (i >= 0) handlers.splice(i, 1) }
}

/** @return true when an inner handler consumed BACK. */
export function handleBack(): boolean {
  for (let i = handlers.length - 1; i >= 0; i--) if (handlers[i]()) return true
  return false
}
