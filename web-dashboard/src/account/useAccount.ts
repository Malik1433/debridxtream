import { useEffect, useState } from 'react'
import { onAuthStateChanged, type User } from 'firebase/auth'
import { doc, onSnapshot } from 'firebase/firestore'
import { auth, db } from '../firebase'

/**
 * An end customer's profile (`users/{uid}`).
 *
 * Deliberately holds nothing about entitlement. Devices, subscriptions and playlists all live in
 * their own collections that this account cannot write — see §7 of ANTI_PIRACY_DECISION.md. If a
 * field here ever started deciding what someone may watch, a self-editable profile would become an
 * entitlement editor.
 */
export interface AccountProfile {
    email: string
    displayName?: string
    phone?: string
    country?: string
    contact?: string
    status?: string
    createdAt?: number
}

/** Live Firebase Auth user + their `users/{uid}` profile doc. */
export function useAccount() {
    const [user, setUser] = useState<User | null>(null)
    const [profile, setProfile] = useState<AccountProfile | null>(null)
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        return onAuthStateChanged(auth, (u) => {
            setUser(u)
            setLoading(false)
        })
    }, [])

    useEffect(() => {
        if (!user) {
            setProfile(null)
            return
        }
        return onSnapshot(doc(db, 'users', user.uid), (snap) => {
            setProfile(snap.exists() ? (snap.data() as AccountProfile) : null)
        })
    }, [user])

    return { user, profile, loading }
}
