import {
    addDoc, collection, deleteDoc, doc, onSnapshot, query, serverTimestamp, updateDoc, where,
} from 'firebase/firestore'
import { db } from '../firebase'

/**
 * One IPTV source belonging to a customer account.
 *
 * These are the credentials the TV needs at playback time, so they are the most sensitive thing this
 * app stores. They live here — owner-scoped — rather than in `device_codes`, which is readable by
 * anyone who knows a device key (§7 of ANTI_PIRACY_DECISION.md). A device reads them; nothing pushes
 * them.
 */
export interface Playlist {
    id: string
    ownerUid: string
    name: string
    /** Only 'xtream' is implemented today; the field exists so adding M3U later needs no migration. */
    type: 'xtream' | 'm3u'
    url: string
    username: string
    password: string
    enabled: boolean
}

export type PlaylistDraft = Omit<Playlist, 'id' | 'ownerUid'>

export function emptyDraft(): PlaylistDraft {
    return { name: '', type: 'xtream', url: '', username: '', password: '', enabled: true }
}

/** Trailing slashes break the `player_api.php` join, so they come off once, here. */
export function normalizeServerUrl(value: string): string {
    return value.trim().replace(/\/+$/, '')
}

/**
 * @returns a message describing the first problem, or null when the draft is usable.
 *
 * Checked before the write rather than after the failure: a customer typing their provider's details
 * on a phone gets one clear sentence instead of a permission error or a silently dead playlist.
 */
export function validateDraft(d: PlaylistDraft): string | null {
    if (!d.name.trim()) return 'Give this playlist a name.'
    const raw = d.url.trim()
    if (!raw) return 'Enter your provider’s server address.'
    // Both checks run on the RAW value, before normalisation. Stripping trailing slashes turns a
    // bare "http://" into "http:", which would then be reported as "must start with http://" — a
    // dead end for someone who typed exactly that.
    if (!/^https?:\/\//i.test(raw)) return 'The server address must start with http:// or https://'
    if (!/^https?:\/\/[^/?#]+/i.test(raw)) return 'Add your provider’s address after http:// — for example http://example.com:8080'
    try { new URL(normalizeServerUrl(raw)) } catch { return 'That server address doesn’t look valid.' }
    if (!d.username.trim()) return 'Enter your username.'
    if (!d.password) return 'Enter your password.'
    return null
}

/**
 * Live list of the signed-in customer's playlists. The where() is what satisfies the read rule.
 *
 * Sorted in JS rather than with orderBy(): combining a filter and a sort makes Firestore demand a
 * composite index, which failed the whole list until the index was built. Somebody has a handful of
 * playlists, so ordering them here costs nothing and removes a deploy-time dependency that could
 * break this page in a fresh environment.
 */
export function watchPlaylists(ownerUid: string, cb: (rows: Playlist[]) => void, onError: (e: Error) => void) {
    const q = query(collection(db, 'playlists'), where('ownerUid', '==', ownerUid))
    return onSnapshot(q, (snap) => {
        const rows = snap.docs.map((d) => {
            const data = d.data() as Omit<Playlist, 'id'> & { createdAt?: { toMillis?: () => number } }
            return { row: { id: d.id, ...(data as Omit<Playlist, 'id'>) }, at: data.createdAt?.toMillis?.() ?? Number.MAX_SAFE_INTEGER }
        })
        // A doc written moments ago has a null serverTimestamp in the local echo. Sorting it last
        // keeps a freshly added playlist from jumping position when the server value lands.
        rows.sort((a, b) => a.at - b.at)
        cb(rows.map((r) => r.row))
    }, onError)
}

export async function createPlaylist(ownerUid: string, d: PlaylistDraft) {
    await addDoc(collection(db, 'playlists'), {
        ownerUid,
        name: d.name.trim(),
        type: d.type,
        url: normalizeServerUrl(d.url),
        username: d.username.trim(),
        password: d.password,
        enabled: d.enabled,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
    })
}

export async function savePlaylist(id: string, d: PlaylistDraft) {
    // ownerUid is deliberately NOT in this payload. The rules reject an update that changes it, and
    // leaving it out means an edit can never even attempt to hand a playlist to someone else.
    await updateDoc(doc(db, 'playlists', id), {
        name: d.name.trim(),
        type: d.type,
        url: normalizeServerUrl(d.url),
        username: d.username.trim(),
        password: d.password,
        enabled: d.enabled,
        updatedAt: serverTimestamp(),
    })
}

export async function removePlaylist(id: string) {
    await deleteDoc(doc(db, 'playlists', id))
}

export interface TestResult { ok: boolean; message: string }

/**
 * Asks the provider whether these details actually work, via the existing /api/verify-iptv endpoint.
 *
 * Advisory only — a failure never blocks saving. Providers go down, and a customer who cannot save
 * correct details because their server is briefly unreachable is worse off than one who saves details
 * that turn out to be wrong.
 */
export async function testPlaylist(d: PlaylistDraft): Promise<TestResult> {
    try {
        const res = await fetch('/api/verify-iptv', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                serverUrl: normalizeServerUrl(d.url),
                username: d.username.trim(),
                password: d.password,
            }),
        })
        if (res.status === 404) return { ok: false, message: 'Connection test is unavailable here.' }
        const body = await res.json().catch(() => ({}))
        return { ok: res.ok && body?.ok === true, message: body?.message || (res.ok ? 'IPTV verified.' : 'Could not verify these details.') }
    } catch {
        return { ok: false, message: 'Could not reach the verification service.' }
    }
}
