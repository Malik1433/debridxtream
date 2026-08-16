import {
    addDoc, collection, deleteDoc, doc, onSnapshot, query, serverTimestamp, updateDoc, where,
} from 'firebase/firestore'
import { db } from '../firebase'

/**
 * One debrid link belonging to a customer account.
 *
 * Two kinds, because the device stores two different things:
 *  - `addon`    — a single Stremio add-on, identified by its `manifest.json` URL,
 *  - `registry` — a JSON list of add-ons, so one link brings a whole set.
 *
 * They live beside `playlists` and are read the same way: owner-scoped, and the device READS them.
 * They are NOT a field on the playlist, because an add-on has nothing to do with which IPTV
 * provider the device runs — a customer with three servers and one add-on would otherwise have to
 * enter it three times.
 *
 * Until now the only way to add one was to type the whole URL on a television with a remote.
 */
export interface Addon {
    id: string
    ownerUid: string
    name: string
    kind: 'addon' | 'registry'
    url: string
    enabled: boolean
    /** Which device this is for — the device's installId. Empty means every device. */
    deviceId?: string
}

export type AddonDraft = Omit<Addon, 'id' | 'ownerUid'>

export function emptyAddonDraft(): AddonDraft {
    return { name: '', kind: 'addon', url: '', enabled: true, deviceId: '' }
}

/** Whitespace only. The path matters here (`manifest.json`), so nothing else is stripped. */
export function normalizeAddonUrl(value: string): string {
    return value.trim()
}

/**
 * @returns a message describing the first problem, or null when the draft is usable.
 *
 * The https requirement is not house style, it is what the device enforces: its own validator
 * rejects anything else, so a link saved over http would be accepted here and then silently
 * ignored on the television — the worst of both.
 */
export function validateAddonDraft(d: AddonDraft): string | null {
    if (!d.name.trim()) return 'Give this add-on a name.'
    const raw = normalizeAddonUrl(d.url)
    if (!raw) return 'Paste the add-on link.'
    if (!/^https:\/\//i.test(raw)) return 'The link must start with https:// — your devices reject anything else.'
    try { new URL(raw) } catch { return 'That link doesn’t look valid.' }
    if (d.kind === 'addon' && !/manifest\.json/i.test(raw)) {
        return 'A Stremio add-on link ends in manifest.json. If this is a list of add-ons, choose “Add-on list” instead.'
    }
    return null
}

/**
 * Live list of the signed-in customer's add-ons.
 *
 * The where() is what satisfies the list rule, and the sort is in JS for the reason the playlists
 * list documents: pairing a filter with an orderBy would demand a composite index, and a missing
 * index fails the whole list rather than degrading.
 */
export function watchAddons(ownerUid: string, cb: (rows: Addon[]) => void, onError: (e: Error) => void) {
    const q = query(collection(db, 'addons'), where('ownerUid', '==', ownerUid))
    return onSnapshot(q, (snap) => {
        const rows = snap.docs.map((d) => {
            const data = d.data() as Omit<Addon, 'id'> & { createdAt?: { toMillis?: () => number } }
            return { row: { id: d.id, ...(data as Omit<Addon, 'id'>) }, at: data.createdAt?.toMillis?.() ?? Number.MAX_SAFE_INTEGER }
        })
        rows.sort((a, b) => a.at - b.at)
        cb(rows.map((r) => r.row))
    }, onError)
}

export async function createAddon(ownerUid: string, d: AddonDraft) {
    await addDoc(collection(db, 'addons'), {
        ownerUid,
        name: d.name.trim(),
        kind: d.kind,
        url: normalizeAddonUrl(d.url),
        enabled: d.enabled,
        deviceId: d.deviceId || '',
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
    })
}

export async function saveAddon(id: string, d: AddonDraft) {
    // ownerUid is deliberately absent, as it is for a playlist: the rules reject an update that
    // changes it, and leaving it out means an edit can never even attempt to hand this to someone
    // else.
    await updateDoc(doc(db, 'addons', id), {
        name: d.name.trim(),
        kind: d.kind,
        url: normalizeAddonUrl(d.url),
        enabled: d.enabled,
        deviceId: d.deviceId || '',
        updatedAt: serverTimestamp(),
    })
}

export async function removeAddon(id: string) {
    await deleteDoc(doc(db, 'addons', id))
}
