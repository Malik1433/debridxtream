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
import { enrich, type Enrichment } from '../data/tmdb'
import { recentChannels, recordChannel, type RecentChannel } from '../data/recentLive'
import { recentSearches, recordSearch } from '../data/recentSearches'
import { note, span, spanAsync } from './perf/span'
import { applyOrder, holdNames, orderOffThread } from '../data/offThreadSort'
import { ALL_ID, numericSortKeys, type SortMode } from './screens/library/libraryModel'

/** Let the first screen settle before the background sorts start. */
const PREWARM_AFTER_MS = 3_000
import { allWatch, continueWatching, markWatched, recordProgress, watchEntry, watchedEpisodes, type WatchEntry } from '../data/watchState'
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
  async liveCategories(): Promise<LiveCategory[]> {
    return this.filtered('live-cats', async () => this.parental.filterCategories(await this.session.liveCategories()))
  }

  async liveStreams(): Promise<LiveStream[]> {
    return this.filtered('live-streams', async () => {
      const [cats, streams] = await Promise.all([this.session.liveCategories(), this.session.liveStreams()])
      return this.parental.filterItems(streams, cats)
    })
  }
  /** Built from the current session every time: an absolute stream URL is never stored (CLAUDE.md). */
  liveUrl(streamId: string): string | null { const a = this.session.account(); return a ? liveUrl(a, streamId) : null }
  favourites(kind: FavKind = 'live'): string[] { return favourites(localStorage, kind) }
  toggleFavourite(id: string, kind: FavKind = 'live'): string[] { return toggleFavourite(localStorage, id, kind) }

  // ── Movies and series (W4) ──
  async libraryCategories(kind: LibraryKind): Promise<VodCategory[]> {
    return this.filtered(`lib-cats-${kind}`, async () => this.parental.filterCategories(await this.session.libraryCategories(kind)))
  }

  async libraryItems<T extends Movie | Show>(kind: LibraryKind): Promise<T[]> {
    const p = this.filtered(`lib-items-${kind}`, async () => {
      const [cats, items] = await spanAsync(`read:lib-${kind}`, () => Promise.all([this.session.libraryCategories(kind), this.session.libraryItems<T>(kind)]))
      return span(`parental:lib-${kind}`, () => this.parental.filterItems(items, cats))
    })
    void p.then((items) => {
      this.prewarmSorts(kind, items)
      // SIMILAR MOVIES looks titles up in the worker (round 10): hand it the names once, in the background.
      if (kind === 'movies') setTimeout(() => {
        const t = performance.now()
        void holdNames('movies', items).then((ok) => { if (ok) note('prewarm:names-movies', t) })
      }, PREWARM_AFTER_MS + 2_000)
    }).catch(() => undefined)
    return p
  }

  /**
   * W4 QA round 10: opening Movies sorted all 69,500 films on the main thread (1.1-1.3 s on the
   * Samsung, `memo:lib-sorted-*`). A little after the library is in, the sorts the screen will open
   * with - All in Recently Added, and the viewer's last chosen sort - are computed in a worker and
   * dropped into the same memo the screen reads, so it finds them ready. Whatever is not ready yet
   * (or a TV with no worker) is sorted on the spot as before; nothing waits on this.
   */
  private prewarmed = new Set<string>()
  private generation = 0
  private prewarmSorts(kind: LibraryKind, items: Array<Movie | Show>): void {
    const mode = this.mode()
    const tag = `${kind}|${mode}`
    if (this.prewarmed.has(tag)) return
    this.prewarmed.add(tag)
    let saved: string | null = null
    try { saved = sessionStorage.getItem(`lib:${kind}:sort`) } catch { /* private mode */ }
    const modes = Array.from(new Set<SortMode>(['recent', (saved as SortMode | null) ?? 'recent']))
    const gen = this.generation
    setTimeout(() => {
      void (async () => {
        for (const m of modes) {
          const k = `lib-sorted-${kind}-${ALL_ID}-${m}|${mode}`
          if (this.derivedMemo.has(k)) continue
          const keys = numericSortKeys(items, m)
          if (!keys) continue
          const idx = await orderOffThread(keys.primary, keys.secondary)
          // A sync or a parental change since: these items are no longer what the screen shows.
          if (!idx || gen !== this.generation || mode !== this.mode() || this.derivedMemo.has(k)) continue
          this.derivedMemo.set(k, span(`prewarm:lib-sorted-${kind}-${m}`, () => applyOrder(items, idx)))
        }
      })()
    }, PREWARM_AFTER_MS)
  }

  /**
   * The FILTERED catalogue, held for as long as it can stay true. Session caches the read; this
   * caches the work done to it, which is the expensive half: parental filtering walks 69,536 movies
   * and 15,951 channels, and it was being redone on every single visit to Movies, Series or Live.
   * W3d/W4 QA (2026-10-02): the screens still showed "loading" after the read was cached, and focus
   * went sluggish while they did - that was this, on the main thread.
   *
   * The key carries the parental answer, so turning adult categories on or off serves a different
   * entry instead of a stale one; a sync clears the lot (`forgetFiltered`).
   */
  private filteredMemo = new Map<string, Promise<unknown>>()
  /** The same entries once resolved, readable WITHOUT a promise - see peek(). */
  private filteredReady = new Map<string, unknown>()
  private derivedMemo = new Map<string, unknown>()

  private mode(): string { return this.parental.hidesAdult() ? 'hide' : 'show' }

  private filtered<T>(key: string, work: () => Promise<T>): Promise<T> {
    const k = `${key}|${this.mode()}`
    const hit = this.filteredMemo.get(k) as Promise<T> | undefined
    if (hit) return hit
    const p = spanAsync(`load:${key}`, work).then((v) => { if (this.filteredMemo.get(k) === p) this.filteredReady.set(k, v); return v })
      .catch((e: unknown) => { this.filteredMemo.delete(k); throw e })
    this.filteredMemo.set(k, p)
    return p
  }

  /**
   * The cached list RIGHT NOW, or undefined on a cold start. W4 QA P1: even a resolved promise costs
   * a microtask and a second render, and every mount began in "loading" - visible on the TV each time
   * the viewer went Series → Movies → Live. A screen that peeks first paints its list on frame one.
   */
  peek<T>(key: 'live-cats' | 'live-streams' | `lib-cats-${LibraryKind}` | `lib-items-${LibraryKind}`): T | undefined {
    return this.filteredReady.get(`${key}|${this.mode()}`) as T | undefined
  }

  /**
   * Work derived from the cached lists (category rows, the by-category index), kept across screen
   * changes: a screen's own useMemo dies with the screen, and rebuilding these walks 69,536 movies on
   * the main thread - the "focus goes slow while it loads" half of P1. Cleared with the lists.
   */
  memo<T>(key: string, compute: () => T): T {
    const k = `${key}|${this.mode()}`
    if (this.derivedMemo.has(k)) return this.derivedMemo.get(k) as T
    const v = span(`memo:${key}`, compute)
    this.derivedMemo.set(k, v)
    return v
  }

  private forgetFiltered(): void { this.filteredMemo.clear(); this.filteredReady.clear(); this.derivedMemo.clear(); this.prewarmed.clear(); this.generation++ }
  movieInfo(id: string, ext: string): Promise<MovieInfo> { return this.withAccount((a) => movieInfo(a, id, ext)) }
  showInfo(id: string): Promise<ShowInfo> { return this.withAccount((a) => showInfo(a, id)) }
  /** TMDB, as the Android detail pages use it (plot, backdrop, cast...). Null without a key or a match. */
  enrich(kind: 'movie' | 'tv', title: string, year: string): Promise<Enrichment | null> { return enrich(kind, title, year) }
  movieUrl(id: string, ext: string): string | null { const a = this.session.account(); return a ? movieUrl(a, id, ext) : null }
  episodeUrl(id: string, ext: string): string | null { const a = this.session.account(); return a ? episodeUrl(a, id, ext) : null }
  watchEntry(kind: WatchEntry['kind'], id: string): WatchEntry | null { return watchEntry(localStorage, kind, id) }
  recordProgress(e: Omit<WatchEntry, 'watched' | 'updatedAt'>): WatchEntry { return recordProgress(localStorage, e) }
  continueWatching(): WatchEntry[] { return continueWatching(localStorage) }
  allWatch(): Map<string, WatchEntry> { return allWatch(localStorage) }
  markWatched(e: Parameters<typeof markWatched>[1], watched: boolean): void { markWatched(localStorage, e, watched) }
  recentChannels(): RecentChannel[] { return recentChannels(localStorage) }
  recordChannel(c: RecentChannel): void { recordChannel(localStorage, c) }
  recentSearches(): string[] { return recentSearches(localStorage) }
  recordSearch(q: string): void { recordSearch(localStorage, q) }
  /** The provider login this box uses (Settings → Account → Signed in as, as on Android). */
  accountUser(): string | null { return this.session.account()?.username ?? null }
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
      .then((r) => { this.forgetFiltered(); this.set({ library: r, librarySyncing: false }) })
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
      this.forgetFiltered()
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
