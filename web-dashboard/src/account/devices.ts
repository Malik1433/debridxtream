import { collection, doc, onSnapshot, query, updateDoc, where } from 'firebase/firestore'
import { db } from '../firebase'

/**
 * A TV on this account (`licenses/{installId}`).
 *
 * The customer sees it; they do not control it. Everything except the name is written by
 * claimDevice or by the device itself — which is why the only editable field here is deviceName
 * (§7.8: freeing a slot is staff-only).
 */
export interface AccountDevice {
    installId: string
    deviceName?: string
    activationCode?: string
    status?: string
    tier?: string
    expiresAt?: number
    lastSeenAt?: unknown
    /** Present when a reseller sold this device — it then has no subscription slot. */
    resellerId?: string
    subscriptionId?: string
}

/** Live list of devices claimed by this account. The where() is what satisfies the list rule. */
export function watchDevices(ownerUid: string, cb: (rows: AccountDevice[]) => void, onError: (e: Error) => void) {
    // No orderBy: pairing a filter with a sort would demand a composite index, and the list is
    // capped at a handful of devices anyway. Sorted below instead.
    const q = query(collection(db, 'licenses'), where('ownerUid', '==', ownerUid))
    return onSnapshot(q, (snap) => {
        const rows = snap.docs.map((d) => ({ installId: d.id, ...(d.data() as Omit<AccountDevice, 'installId'>) }))
        rows.sort((a, b) => (a.deviceName || a.installId).localeCompare(b.deviceName || b.installId))
        cb(rows)
    }, onError)
}

export async function renameDevice(installId: string, deviceName: string) {
    await updateDoc(doc(db, 'licenses', installId), { deviceName: deviceName.trim().slice(0, 60) })
}

/** Millis from either a Firestore Timestamp or a plain number, or null when absent. */
export function toMillis(value: unknown): number | null {
    if (typeof value === 'number') return value
    const ts = value as { toMillis?: () => number }
    return typeof ts?.toMillis === 'function' ? ts.toMillis() : null
}

export function lastSeenLabel(value: unknown, nowMs: number): string {
    const ms = toMillis(value)
    if (!ms) return 'not seen yet'
    const diff = nowMs - ms
    if (diff < 0 || diff < 5 * 60_000) return 'online now'
    if (diff < 60 * 60_000) return `${Math.round(diff / 60_000)} min ago`
    if (diff < 24 * 60 * 60_000) return `${Math.round(diff / 3_600_000)} h ago`
    return `${Math.round(diff / 86_400_000)} days ago`
}
