/** One IPTV source on the customer's account, as the TV sees it (Android: AccountPlaylist). */
export interface AccountPlaylist {
  id: string
  name: string
  url: string
  username: string
  password: string
  enabled: boolean
  isXtream: boolean
  createdAtMs: number
  /** installId this playlist is addressed to; empty means every device on the account. */
  assignedTo: string
}

/** A `playlists` doc -> AccountPlaylist, or null when it has no url. Port of the Android mapping. */
export function parsePlaylist(id: string, d: Record<string, unknown>): AccountPlaylist | null {
  const url = typeof d.url === 'string' ? d.url : ''
  if (!url) return null
  const str = (v: unknown) => (typeof v === 'string' ? v : '')
  const created = d.createdAt as { toMillis?: () => number } | undefined
  return {
    id,
    name: str(d.name) || hostOf(url),
    url,
    username: str(d.username),
    password: str(d.password),
    enabled: d.enabled !== false,
    isXtream: d.type !== 'm3u',
    createdAtMs: typeof created?.toMillis === 'function' ? created.toMillis() : Number.MAX_SAFE_INTEGER,
    assignedTo: str(d.deviceId),
  }
}

/**
 * Port of AccountPlaylistSync.applyActive - the ONE server this TV runs: what the viewer picked here,
 * then a playlist addressed to this TV, then one for every device; oldest first, as the phone sorts.
 * A playlist addressed to another TV is never this one's.
 */
export function pickActive(all: AccountPlaylist[], installId: string, chosenId: string | null): AccountPlaylist | null {
  const usable = all
    .filter((p) => p.enabled && p.isXtream && (!p.assignedTo || p.assignedTo === installId))
    .sort((a, b) => a.createdAtMs - b.createdAtMs)
  return (chosenId ? usable.find((p) => p.id === chosenId) : undefined) ??
    usable.find((p) => p.assignedTo === installId) ??
    usable.find((p) => !p.assignedTo) ??
    null
}

function hostOf(url: string): string {
  const s = url.replace(/^[a-z]+:\/\//i, '')
  return s.split(/[/:?#]/)[0] || url
}
