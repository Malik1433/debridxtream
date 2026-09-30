import { onAuthStateChanged, signInAnonymously, type User } from 'firebase/auth'
import { doc, serverTimestamp, setDoc } from 'firebase/firestore'
import { firebase } from '../firebase'

declare const __APP_VERSION_CODE__: number

/**
 * Port of DeviceIdentity: sign in anonymously and record "this uid is me" (with the proof only this
 * TV can make) against the installId. It grants nothing by itself - claimDevice, run while the
 * customer confirms the TV on their phone, decides which account the uid belongs to. Fire and
 * forget: it must never block the gate or playback.
 */
export function publishIdentity(installId: string, proof: string, onUser: (u: User | null) => void): () => void {
  const { auth, db } = firebase()
  const stop = onAuthStateChanged(auth, (user) => {
    onUser(user)
    if (!user) return
    void setDoc(doc(db, 'device_identity', installId), {
      authUid: user.uid,
      proof,
      appVersionCode: __APP_VERSION_CODE__,
      updatedAt: serverTimestamp(),
    }, { merge: true }).catch(() => undefined)
  })
  if (!auth.currentUser) void signInAnonymously(auth).catch(() => undefined)
  return stop
}
