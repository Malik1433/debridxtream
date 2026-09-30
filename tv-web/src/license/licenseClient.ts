import { doc, getDoc, onSnapshot, serverTimestamp, setDoc, type Unsubscribe } from 'firebase/firestore'
import { firebase } from '../firebase'
import { applyDeleted, applyDoc, EMPTY_CACHE, isCurrentlyEntitled, licenseState, type LicenseCache, type LicenseState } from './policy'

declare const __APP_VERSION__: string
declare const __APP_VERSION_CODE__: number

const CACHE_KEY = 'dx.license'

/**
 * Port of LicenseManager: register this TV as `pending` (never overwriting an admin's status), keep
 * a live listener on its licence doc and on the owner's global `enforce` switch, and remember the
 * result so the gate decides instantly on the next launch and survives short outages.
 */
export class LicenseClient {
  private cache: LicenseCache
  private listeners: Unsubscribe[] = []
  private subscribers = new Set<(s: LicenseState) => void>()
  private registering = false
  private recoveryTried = false

  constructor(readonly installId: string, readonly activationCode: string, private readonly storage: Storage) {
    this.cache = this.load()
    if (isCurrentlyEntitled(this.cache, Date.now())) this.cache.everEntitled = true
    if (this.cache.firstSeenAt <= 0) this.cache.firstSeenAt = Date.now()
    this.save()
  }

  state(): LicenseState { return licenseState(this.cache, Date.now()) }
  /** True once the server has this TV's doc: only then can the provider find it by its code. */
  get registeredOnServer(): boolean { return this.cache.docCreated }

  subscribe(cb: (s: LicenseState) => void): () => void {
    this.subscribers.add(cb)
    cb(this.state())
    return () => this.subscribers.delete(cb)
  }

  start(): void {
    if (this.listeners.length) return
    const { db } = firebase()
    this.listeners.push(onSnapshot(doc(db, 'app_config', 'licensing'), (snap) => {
      if (!snap.exists()) return
      this.update({ ...this.cache, enforce: snap.get('enforce') === true })
    }, () => undefined))

    const ref = doc(db, 'licenses', this.installId)
    void this.registerOrTouch()
    // Metadata changes included: the fromCache -> server flip is the "we are online" signal, and a
    // doc that never existed never changes otherwise (Android measured this the hard way).
    this.listeners.push(onSnapshot(ref, { includeMetadataChanges: true }, (snap) => {
      const fromServer = !snap.metadata.fromCache
      if (!snap.exists()) {
        const neverRegistered = fromServer && !this.registering && !this.recoveryTried &&
          !this.cache.docCreated && !this.cache.everEntitled
        if (neverRegistered) { this.recoveryTried = true; void this.registerOrTouch() }
        else if (fromServer) this.update(applyDeleted(this.cache))
        return
      }
      let next = applyDoc(this.cache, snap.data(), Date.now())
      // ONLY a real server round-trip counts as reaching the licence server.
      if (fromServer) next = { ...next, lastVerifiedAt: Date.now() }
      this.update(next)
    }, () => undefined))
  }

  stop(): void {
    this.listeners.forEach((u) => u())
    this.listeners = []
  }

  /** Create as `pending` only if absent; otherwise touch telemetry only (the rules allow nothing else). */
  private async registerOrTouch(): Promise<void> {
    const { db } = firebase()
    const ref = doc(db, 'licenses', this.installId)
    this.registering = true
    try {
      const snap = await getDoc(ref)
      const telemetry = {
        appVersionCode: __APP_VERSION_CODE__,
        appVersionName: __APP_VERSION__,
        activationCode: this.activationCode,
        lastSeenAt: serverTimestamp(),
      }
      if (!snap.exists()) {
        await setDoc(ref, { status: 'pending', tier: 'normal', createdAt: serverTimestamp(), ...telemetry })
      } else {
        await setDoc(ref, telemetry, { merge: true })
      }
      this.update({ ...this.cache, docCreated: true })
    } catch {
      // Offline or refused: the listener's first server sync retries once (see start()).
    } finally {
      this.registering = false
    }
  }

  private update(next: LicenseCache): void {
    this.cache = next
    this.save()
    const s = this.state()
    this.subscribers.forEach((cb) => cb(s))
  }

  private load(): LicenseCache {
    try {
      const raw = this.storage.getItem(CACHE_KEY)
      return raw ? { ...EMPTY_CACHE, ...(JSON.parse(raw) as Partial<LicenseCache>) } : { ...EMPTY_CACHE }
    } catch {
      return { ...EMPTY_CACHE }
    }
  }

  private save(): void {
    try { this.storage.setItem(CACHE_KEY, JSON.stringify(this.cache)) } catch { /* quota: next launch decides online */ }
  }
}
