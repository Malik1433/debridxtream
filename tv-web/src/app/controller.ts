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

export type SyncPhase =
  | { kind: 'idle' }
  | { kind: 'running'; provider: string; moved: boolean }
  | { kind: 'done'; channels: number; categories: number; at: number }
  | { kind: 'error'; provider: string; message: string }

export interface AppState {
  license: LicenseState
  registered: boolean
  activationCode: string
  /** The phone has added this TV to an account (device_auth). */
  claimed: boolean
  providerName: string | null
  hasAccount: boolean
  sync: SyncPhase
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
      sync: last ? { kind: 'done', ...last } : { kind: 'idle' },
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
    this.set({ hasAccount: true, providerName: name })
    if (changed || this.state.sync.kind === 'idle') void this.runSync(moved)
  }

  private running = false
  private async runSync(moved: boolean): Promise<void> {
    if (this.running) return
    this.running = true
    const provider = this.state.providerName ?? 'your provider'
    this.set({ sync: { kind: 'running', provider, moved } })
    try {
      const r = await this.session.sync()
      this.set({ sync: { kind: 'done', channels: r.channels, categories: r.categories, at: Date.now() } })
    } catch (e) {
      this.set({ sync: { kind: 'error', provider, message: e instanceof Error ? e.message : String(e) } })
    } finally {
      this.running = false
    }
  }

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
