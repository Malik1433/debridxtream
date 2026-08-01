import {
    GoogleAuthProvider, signInWithPopup, signInWithRedirect, type User,
} from 'firebase/auth'
import { doc, getDoc, serverTimestamp, setDoc } from 'firebase/firestore'
import { auth, db } from '../firebase'

/**
 * Makes sure a signed-in user has a `users/{uid}` profile.
 *
 * Email sign-up writes this itself, but Google sign-in has no form to hang it on — and without it the
 * account page has no name to show and no createdAt, which is what the verification grace measures
 * from. Idempotent, so it is safe to call on every Google sign-in rather than only the first.
 */
export async function ensureUserProfile(user: User) {
    const ref = doc(db, 'users', user.uid)
    const existing = await getDoc(ref)
    if (existing.exists()) return
    await setDoc(ref, {
        email: user.email,
        displayName: user.displayName || '',
        status: 'active',
        createdAt: serverTimestamp(),
    })
}

/**
 * Sign in with Google.
 *
 * Popup first, redirect as the fallback. Phones are where this actually runs, and a popup is blocked
 * often enough there — in in-app browsers especially — that treating a block as failure would leave
 * a customer with a dead button and no explanation. The redirect path returns to this same page and
 * Firebase completes the sign-in from persisted state, so the caller needs no special handling.
 */
export async function signInWithGoogle(): Promise<void> {
    const provider = new GoogleAuthProvider()
    // Always ask which account: a shared family phone signed into someone else's Google would
    // otherwise silently attach the TV to the wrong person.
    provider.setCustomParameters({ prompt: 'select_account' })

    try {
        const result = await signInWithPopup(auth, provider)
        await ensureUserProfile(result.user)
    } catch (err: unknown) {
        const code = (err as { code?: string })?.code || ''
        const recoverable = code.includes('popup-blocked') ||
            code.includes('popup-closed-by-user') ||
            code.includes('cancelled-popup-request') ||
            code.includes('operation-not-supported-in-this-environment')
        if (!recoverable) throw err
        // A user who deliberately closed the popup should not be thrown into a redirect.
        if (code.includes('popup-closed-by-user') || code.includes('cancelled-popup-request')) return
        await signInWithRedirect(auth, provider)
    }
}

/** Turns a Google sign-in failure into something a customer can act on. */
export function googleErrorText(err: unknown): string {
    const code = ((err as { code?: string })?.code || '').replace('auth/', '')
    switch (code) {
        case 'account-exists-with-different-credential':
            return 'That email already has an account. Sign in with your password instead.'
        case 'unauthorized-domain':
            return 'Google sign-in is not enabled for this site yet.'
        case 'operation-not-allowed':
            return 'Google sign-in is not switched on for this app yet.'
        case 'network-request-failed':
            return 'Network error — check your connection.'
        default:
            return (err as { message?: string })?.message || 'Could not sign in with Google.'
    }
}
