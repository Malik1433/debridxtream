import { sha256Bytes } from '../sha256'

/** Fingerprint meaning "no server". Distinct from any real one, so a logout is a change too. */
export const NO_SERVER = ''

/**
 * Port of ServerIdentity.fingerprint: which provider the data on this TV belongs to. host[:port]
 * (lower-cased, scheme/path/credentials dropped) + username (case kept: Xtream is case-sensitive).
 * The server-switch contract (CLAUDE.md) purges everything when this changes.
 */
export function serverFingerprint(serverUrl: string | null | undefined, username: string | null | undefined): string {
  const authority = authorityOf(serverUrl)
  if (!authority || !username || !username.trim()) return NO_SERVER
  let hex = ''
  sha256Bytes(`${authority}|${username}`).slice(0, 8).forEach((b) => { hex += (b < 16 ? '0' : '') + b.toString(16) })
  return hex
}

function authorityOf(serverUrl: string | null | undefined): string {
  const raw = (serverUrl ?? '').trim()
  if (!raw) return ''
  const idx = raw.indexOf('://')
  const afterScheme = idx >= 0 ? raw.slice(idx + 3) : raw
  const authority = afterScheme.split('/')[0].split('?')[0].split('#')[0]
  const at = authority.lastIndexOf('@')
  return (at >= 0 ? authority.slice(at + 1) : authority).toLowerCase()
}
