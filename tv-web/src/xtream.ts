/** W0 test account, read from `w0.local.json` next to the page. Never committed, never shown. */
export interface W0Config {
  server: string
  username: string
  password: string
  /** Optional: channels to zap through; otherwise the first live streams the account lists. */
  streamIds?: string[]
}

export function baseUrl(server: string): string {
  const s = server.trim().replace(/\/+$/, '')
  return /^https?:\/\//i.test(s) ? s : `http://${s}`
}

export function apiUrl(c: W0Config, action?: string): string {
  const q = `username=${encodeURIComponent(c.username)}&password=${encodeURIComponent(c.password)}`
  return `${baseUrl(c.server)}/player_api.php?${q}${action ? `&action=${action}` : ''}`
}

/** Live `.ts` - the only format this provider serves (allowed_output_formats ["ts"]). */
export function liveUrl(c: W0Config, streamId: string): string {
  return `${baseUrl(c.server)}/live/${encodeURIComponent(c.username)}/${encodeURIComponent(c.password)}/${streamId}.ts`
}

/** For the screen and the report: the host is not shown, nor any credential. */
export function redact(text: string, c: W0Config): string {
  let out = text
  for (const secret of [c.password, c.username, baseUrl(c.server)]) {
    if (secret) out = out.split(secret).join('***')
  }
  return out
}
