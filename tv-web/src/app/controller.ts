import type { User } from 'firebase/auth'
import { AccountSync } from '../account/accountSync'
import type { AccountPlaylist } from '../account/playlists'
import { IdbCatalogueStore } from '../data/catalogueStore'
import { Session } from '../data/session'
import { serverFingerprint } from '../data/serverIdentity'
import type { XtreamAccount } from '../data/xtreamApi'
import { deriveActivationCode, identityProofFor, installIdFor, tvIdOrInstallSecret } from '../license/identity'
import { publishIdentity } from '../license/identityPublisher'
import { LicenseClient } from '../license/licenseClient'
import type { LicenseState } from '../license/policy'
import { deviceId, type Platform } from '../platform'
import { SyncRunner } from './syncRunner'
import { EpgCache } from '../data/epg'
import { favourites, toggleFavourite, type FavKind } from '../data/favourites'
import type { LibraryKind } from '../data/catalogueStore'
import { Parental } from '../data/parental'
import type { LibrarySync } from '../data/session'
import { episodeUrl, movieInfo, movieUrl, showInfo, type Movie, type MovieInfo, type Show, type ShowInfo, type VodCategory } from '../data/vodApi'
import { continueWatching, recordProgress, watchEntry, watchedEpisodes, type WatchEntry } from '../data/watchState'
import { liveUrl } from '../xtream'
import type { LiveCategory, LiveStream } from '../data/xtreamApi'

export type SyncPhase =
  | { kind: 'idle' }
  | { kind: 'running'; provider: string; moved: boolean }
  | { kind: 'done'; channels: number; categories: number; at: number }
  | { kind: 'error'; provider: string; message: string }

/** The catalogue we already hold. Survives a refresh that is running, failing, or yet to start. */
export interface Catalogue { channels: number; categories: number; at: number }

export interface AppState {
  license: LicenseState
  registered: boolean
  activationCode: string
  /** The phone has added this TV to an account (device_auth). */
  claimed: boolean
  providerName: string | null
  hasAccount: boolean
  /**
   * What we can show RIGHT NOW, kept apart from `sync` on purpose. `sync` is the current attempt and
   * goes back to 'running' on every refresh; if the gate read that, a refresh on a TV that already
   * has 15,951 channels would look exactly like a first sync and take the screen away (W2 QA
   * 2026-09-30: "Update channels" threw Settings back to Home, and every relaunch showed 30 s of
   * "Loading channels"). Null only until the very first sync lands.
   */
  catalogue: Catalogue | null
  sync: SyncPhase
  /** Movies and series: fetched after Live, never in the gate's way. Null until the first one lands. */
  library: LibrarySync | null
  librarySyncing: boolean
}

/**
 * Wires W2 together: the licence gate, the TV's identity, the account's playlist and the catalogue
 * sync. React reads it through subscribe/snapshot; nothing here knows about screens.
 */
export class AppController {
  readonly installId: string
  readonly activationCode: string
  private readonly license: LicenseClient
  private readonly session = new Session(localStorage, new IdbCatalogueStore())
  private readonly account: AccountSync
  private state: AppState
  private listeners = new Set<() => void>()

  constructor(private readonly platform: Platform) {
    const tvId = tvIdOrInstallSecret(deviceId(platform), localStorage)
    this.installId = installIdFor(platform, tvId)
    this.activationCode = deriveActivationCode(this.installId)
    this.license = new LicenseClient(this.installId, this.activationCode, localStorage)
    this.account = new AccountSync(this.installId, () => this.session.chosenPlaylistId(), (p, claimed) => this.onPlaylist(p, claimed))
    this.proof = identityProofFor(platform, tvId)
    const last = this.session.lastSync()
    this.state = {
      license: this.license.state(),
      registered: this.license.registeredOnServer,
      activationCode: this.activationCode,
      claimed: false,
      providerName: localStorage.getItem('dx.providerName'),
      hasAccount: this.session.account() !== null,
      catalogue: last,
      sync: last ? { kind: 'done', ...last } : { kind: 'idle' },
      library: this.session.lastLibrarySync(),
      librarySyncing: false,
    }
  }

  private readonly proof: string

  start(): void {
    this.license.subscribe((s) => this.set({ license: s, registered: this.license.registeredOnServer }))
    this.license.start()
    publishIdentity(this.installId, this.proof, (u: User | null) => this.account.setUser(u))
    // A TV that already has an account refreshes on launch; a new one waits for the phone.
    if (this.state.hasAccount) void this.runSync(false)
    else void this.tryTestAccount()
  }

  subscribe = (cb: () => void): (() => void) => { this.listeners.add(cb); return () => this.listeners.delete(cb) }
  snapshot = (): AppState => this.state

  // ── Live TV (W3) ──
  readonly epg = new EpgCache(() => this.session.account())
  /** Parental controls apply at the facade, so every screen agrees without knowing why (Android rule). */
  readonly parental = new Parental(localStorage)
  async liveCategories(): Promise<LiveCategory[]> { return this.parental.filterCategories(await this.session.liveCategories()) }
  async liveStreams(): Promise<LiveStream[]> {
    const [cats, streams] = await Promise.all([this.session.liveCategories(), this.session.liveStreams()])
    return this.parental.filterItems(streams, cats)
  }
  /** Built from the current session every time: an absolute stream URL is never stored (CLAUDE.md). */
  liveUrl(streamId: string): string | null { const a = this.session.account(); return a ? liveUrl(a, streamId) : null }
  favourites(kind: FavKind = 'live'): string[] { return favourites(localStorage, kind) }
  toggleFavourite(id: string, kind: FavKind = 'live'): string[] { return toggleFavourite(localStorage, id, kind) }

  // ── Movies and series (W4) ──
  async libraryCategories(kind: LibraryKind): Promise<VodCategory[]> { return this.parental.filterCategories(await this.session.libraryCategories(kind)) }
  async libraryItems<T extends Movie | Show>(kind: LibraryKind): Promise<T[]> {
    const [cats, items] = await Promise.all([this.session.libraryCategories(kind), this.session.libraryItems<T>(kind)])
    return this.parental.filterItems(items, cats)
  }
  movieInfo(id: string, ext: string): Promise<MovieInfo> { return this.withAccount((a) => movieInfo(a, id, ext)) }
  showInfo(id: string): Promise<ShowInfo> { return this.withAccount((a) => showInfo(a, id)) }
  movieUrl(id: string, ext: string): string | null { const a = this.session.account(); return a ? movieUrl(a, id, ext) : null }
  episodeUrl(id: string, ext: string): string | null { const a = this.session.account(); return a ? episodeUrl(a, id, ext) : null }
  watchEntry(kind: WatchEntry['kind'], id: string): WatchEntry | null { return watchEntry(localStorage, kind, id) }
  recordProgress(e: Omit<WatchEntry, 'watched' | 'updatedAt'>): WatchEntry { return recordProgress(localStorage, e) }
  continueWatching(): WatchEntry[] { return continueWatching(localStorage) }
  watchedEpisodes(seriesId: string): Map<string, WatchEntry> { return watchedEpisodes(localStorage, seriesId) }
  retryLibrary(): void { void this.runLibrarySync() }

  private async withAccount<T>(fn: (a: XtreamAccount) => Promise<T>): Promise<T> {
    const a = this.session.account()
    if (!a) throw new Error('no account')
    return fn(a)
  }

  private libraryRun: Promise<void> | null = null
  /** One at a time; Live always first (the gate waits only for channels). */
  private runLibrarySync(): Promise<void> {
    if (this.libraryRun) return this.libraryRun
    this.set({ librarySyncing: true })
    this.libraryRun = this.session.syncLibrary()
      .then((r) => this.set({ library: r, librarySyncing: false }))
      .catch(() => this.set({ librarySyncing: false }))
      .finally(() => { this.libraryRun = null })
    return this.libraryRun
  }

  retrySync(): void { void this.runSync(false) }

  private set(patch: Partial<AppState>): void {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l())
  }

  private onPlaylist(p: AccountPlaylist | null, claimed: boolean): void {
    this.set({ claimed })
    if (!p) return
    this.useAccount({ server: p.url, username: p.username, password: p.password }, p.name)
  }

  private useAccount(a: XtreamAccount, name: string): void {
    const before = this.session.account()
    const moved = before !== null && serverFingerprint(before.server, before.username) !== serverFingerprint(a.server, a.username)
    const changed = this.session.setAccount(a)
    localStorage.setItem('dx.providerName', name)
    // A MOVE is not a refresh. The channels we hold are not stale, they are WRONG - the new
    // provider numbers its streams from 1 too (CLAUDE.md, "One device, one provider"), so showing
    // the new provider's name over the old list is the exact failure that contract exists to stop.
    // Dropping the catalogue here puts the gate back on the sync screen, which is also the only
    // place the customer is told "You were moved to <name>".
    if (moved) this.syncer.moved()
    this.set({ hasAccount: true, providerName: name, ...(moved ? { catalogue: null, library: null } : {}) })
    if (changed || this.state.catalogue === null) void this.runSync(moved)
  }

  private readonly syncer = new SyncRunner(() => this.session.sync(), {
    started: (moved) => this.set({ sync: { kind: 'running', provider: this.state.providerName ?? 'your provider', moved } }),
    succeeded: (r) => {
      const at = Date.now()
      this.set({
        catalogue: { channels: r.channels, categories: r.categories, at },
        sync: { kind: 'done', channels: r.channels, categories: r.categories, at },
      })
      void this.runLibrarySync()
    },
    failed: (e) => this.set({ sync: { kind: 'error', provider: this.state.providerName ?? 'your provider',
      message: e instanceof Error ? e.message : String(e) } }),
  })

  /** Queued and generation-guarded: see SyncRunner (D2, D4). */
  private runSync(moved: boolean): Promise<void> { return this.syncer.request(moved) }

  /**
   * Development and TV QA only: with no account on the cloud side yet, a `w0.local.json` packaged
   * next to the app (gitignored, never shipped) stands in for the phone. Absent in a store build.
   */
  private async tryTestAccount(): Promise<void> {
    try {
      const res = await fetch('./w0.local.json', { cache: 'no-store' })
      if (!res.ok || this.state.hasAccount) return
      const c = (await res.json()) as XtreamAccount
      if (c.server && c.username) this.useAccount(c, 'test account')
    } catch { /* no test account: wait for the phone */ }
  }
}
