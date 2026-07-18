import { useEffect, useState } from 'react'
import { onAuthStateChanged, type User } from 'firebase/auth'
import { doc, onSnapshot } from 'firebase/firestore'
import { auth, db } from '../firebase'

export interface Reseller {
    email: string
    displayName?: string
    phone?: string
    country?: string
    contact?: string
    credits: number
    status: string
    clientCount?: number
}

/** Live Firebase Auth user + their reseller profile doc. */
export function useAuth() {
    const [user, setUser] = useState<User | null>(null)
    const [reseller, setReseller] = useState<Reseller | null>(null)
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        return onAuthStateChanged(auth, (u) => {
            setUser(u)
            setLoading(false)
        })
    }, [])

    useEffect(() => {
        if (!user) {
            setReseller(null)
            return
        }
        return onSnapshot(doc(db, 'resellers', user.uid), (snap) => {
            setReseller(snap.exists() ? (snap.data() as Reseller) : null)
        })
    }, [user])

    return { user, reseller, loading }
}
