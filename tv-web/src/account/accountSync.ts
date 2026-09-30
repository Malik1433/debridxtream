import type { User } from 'firebase/auth'
import { collection, doc, onSnapshot, query, where, type Unsubscribe } from 'firebase/firestore'
import { firebase } from '../firebase'
import { parsePlaylist, pickActive, type AccountPlaylist } from './playlists'

/**
 * Port of AccountPlaylistSync: the TV READS its owner's playlists. Anonymous, the owner is whoever
 * `device_auth/{ourUid}` says claimed this TV (set by claimDevice when the customer confirms it on
 * their phone); the rules let us read that owner's playlists and nobody else's. Edits on the phone
 * arrive here on their own. A released claim stops the flow.
 */
export class AccountSync {
  private bindingStop: Unsubscribe | null = null
  private playlistStop: Unsubscribe | null = null
  private all: AccountPlaylist[] = []
  private claimed = false

  constructor(
    private readonly installId: string,
    private readonly chosenId: () => string | null,
    private readonly onActive: (p: AccountPlaylist | null, claimed: boolean) => void,
  ) {}

  /** Call on every auth change: the identity can change under us (anonymous, then a real sign-in). */
  setUser(user: User | null): void {
    this.stop()
    if (!user) return
    if (!user.isAnonymous) { this.watchOwner(user.uid); return }
    const { db } = firebase()
    this.bindingStop = onSnapshot(doc(db, 'device_auth', user.uid), (snap) => {
      const owner = snap.exists() ? snap.get('ownerUid') : null
      if (typeof owner !== 'string' || !owner) {
        this.claimed = false
        this.playlistStop?.(); this.playlistStop = null
        this.all = []
        this.emit()
        return
      }
      this.watchOwner(owner)
    }, () => undefined)
  }

  /** Re-run the choice (e.g. after the viewer picks a server in Settings). */
  reapply(): void { this.emit() }

  stop(): void {
    this.bindingStop?.(); this.bindingStop = null
    this.playlistStop?.(); this.playlistStop = null
  }

  private watchOwner(ownerUid: string): void {
    this.claimed = true
    this.playlistStop?.()
    const { db } = firebase()
    // The equality filter is what satisfies the read rule; without it the query is refused.
    this.playlistStop = onSnapshot(query(collection(db, 'playlists'), where('ownerUid', '==', ownerUid)), (snap) => {
      this.all = snap.docs.map((d) => parsePlaylist(d.id, d.data())).filter((p): p is AccountPlaylist => p !== null)
      this.emit()
    }, () => undefined)
  }

  private emit(): void {
    this.onActive(pickActive(this.all, this.installId, this.chosenId()), this.claimed)
  }
}
