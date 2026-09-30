import type { CatalogueStore } from './catalogueStore'
import { NO_SERVER, serverFingerprint } from './serverIdentity'
import { liveCatalogue, login, type AccountInfo, type LiveCategory, type LiveStream, type XtreamAccount } from './xtreamApi'

/**
 * The provider this TV plays from, and the server-switch contract (CLAUDE.md, "One device, one
 * provider"): when the provider changes, everything that provider gave us goes - BEFORE the new
 * sync writes a row. The question "is the data on disk still ours?" is answered from state (the
 * stored data fingerprint), so an interrupted switch is simply retried on the next launch.
 */
export interface KeyValue { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void }

const ACCOUNT_KEY = 'dx.account'
const DATA_FP_KEY = 'dx.dataFingerprint'
const SYNC_KEY = 'dx.srv.lastSync'
const CHOSEN_KEY = 'dx.chosenPlaylistId'
/** Every key a provider owns starts with this; the purge clears them all. */
export const SERVER_SCOPED_PREFIX = 'dx.srv.'

export interface SyncResult { account: AccountInfo; categories: number; channels: number }

export class Session {
  constructor(private readonly kv: KeyValue & { length?: number; key?(i: number): string | null },
    private readonly store: CatalogueStore) {}

  account(): XtreamAccount | null {
    try { const raw = this.kv.getItem(ACCOUNT_KEY); return raw ? JSON.parse(raw) as XtreamAccount : null } catch { return null }
  }

  chosenPlaylistId(): string | null { return this.kv.getItem(CHOSEN_KEY) }

  /** The stored Live catalogue, in the provider's own order. */
  async liveCategories(): Promise<LiveCategory[]> {
    return (await this.store.liveCategories()).sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
  }

  async liveStreams(): Promise<LiveStream[]> {
    return (await this.store.liveStreams()).sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
  }
  choosePlaylist(id: string): void { this.kv.setItem(CHOSEN_KEY, id) }

  lastSync(): { at: number; categories: number; channels: number } | null {
    try { const raw = this.kv.getItem(SYNC_KEY); return raw ? JSON.parse(raw) : null } catch { return null }
  }

  /** Point the TV at [a]. Nothing is purged here - that happens at the start of the next sync. */
  setAccount(a: XtreamAccount | null): boolean {
    const before = this.account()
    if (a) this.kv.setItem(ACCOUNT_KEY, JSON.stringify(a)); else this.kv.removeItem(ACCOUNT_KEY)
    return serverFingerprint(before?.server, before?.username) !== serverFingerprint(a?.server, a?.username) ||
      before?.password !== a?.password
  }

  /** From state: the data on disk belongs to a different provider than the one we point at. */
  isServerDataStale(): boolean {
    const a = this.account()
    return (this.kv.getItem(DATA_FP_KEY) ?? NO_SERVER) !== serverFingerprint(a?.server, a?.username)
  }

  /** The one wipe (Android: ServerDataReset.purge). A new per-provider store must be added HERE. */
  async purge(): Promise<void> {
    await this.store.clear()
    const doomed: string[] = []
    const n = this.kv.length ?? 0
    for (let i = 0; i < n; i++) { const k = this.kv.key?.(i); if (k && k.startsWith(SERVER_SCOPED_PREFIX)) doomed.push(k) }
    doomed.forEach((k) => this.kv.removeItem(k))
    const a = this.account()
    this.kv.setItem(DATA_FP_KEY, serverFingerprint(a?.server, a?.username))
  }

  /**
   * Log in and refresh the Live catalogue. Purge first if the provider changed. A failed or EMPTY
   * fetch never replaces a populated catalogue (CLAUDE.md: a failed refresh must not destroy good data).
   */
  async sync(fetcher?: typeof fetch): Promise<SyncResult> {
    const a = this.account()
    if (!a) throw new Error('no account')
    if (this.isServerDataStale()) await this.purge()
    const account = await login(a, fetcher)
    const { categories, streams } = await liveCatalogue(a, fetcher)
    if (streams.length === 0) {
      const had = this.lastSync()
      if (had && had.channels > 0) return { account, categories: had.categories, channels: had.channels }
    } else {
      await this.store.replaceLive(categories, streams)
    }
    const rec = { at: Date.now(), categories: categories.length, channels: streams.length }
    this.kv.setItem(SYNC_KEY, JSON.stringify(rec))
    return { account, categories: rec.categories, channels: rec.channels }
  }
}
