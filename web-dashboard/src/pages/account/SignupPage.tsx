import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { createUserWithEmailAndPassword, sendEmailVerification } from 'firebase/auth'
import { doc, serverTimestamp, setDoc } from 'firebase/firestore'
import { auth, db } from '../../firebase'
import { googleErrorText, signInWithGoogle } from '../../account/googleSignIn'
import { AccountSplitShell, Field, GoogleMark, errText, nextTarget, withSearch } from './accountUi'

export default function AccountSignupPage() {
    const nav = useNavigate()
    const { search } = useLocation()
    const [name, setName] = useState('')
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')

    async function submit(e: React.FormEvent) {
        e.preventDefault()
        setError('')
        setBusy(true)
        try {
            const cred = await createUserWithEmailAndPassword(auth, email.trim(), password)
            // Profile first, then the verification mail. If the mail send fails (rate limits, a
            // transient outage) the account still exists and is usable — the verify screen can
            // resend. Doing it the other way round would leave an account with no profile doc.
            await setDoc(doc(db, 'users', cred.user.uid), {
                email: cred.user.email,
                displayName: name.trim(),
                status: 'active',
                createdAt: serverTimestamp(),
            })
            try { await sendEmailVerification(cred.user) } catch { /* the verify screen offers a resend */ }
            nav(withSearch('/account/verify', search), { replace: true })
        } catch (err: unknown) {
            setError(errText(err))
        } finally {
            setBusy(false)
        }
    }

    async function google() {
        setError('')
        setBusy(true)
        try {
            await signInWithGoogle()
            // Google accounts arrive already verified, so there is nothing to verify — go straight
            // where they were headed instead of via the verify screen.
            nav(withSearch(nextTarget(search), search), { replace: true })
        } catch (err: unknown) {
            setError(googleErrorText(err))
        } finally { setBusy(false) }
    }

    return (
        <AccountSplitShell title="Create your account" subtitle="One account for all your devices.">
            <button type="button" onClick={google} disabled={busy} className="btn btn-secondary btn-block" style={{ justifyContent: 'center', padding: '11px 16px', fontSize: 14, gap: 8, marginBottom: 18 }}>
                <GoogleMark />Continue with Google
            </button>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 18 }}>
                <div style={{ flex: 1, height: 1, background: 'var(--color-neutral-200)' }} />
                <span style={{ fontSize: 12, opacity: 0.45 }}>or sign up with email</span>
                <div style={{ flex: 1, height: 1, background: 'var(--color-neutral-200)' }} />
            </div>
            <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                <Field label="Your name"><input className="input" type="text" required value={name} onChange={(e) => setName(e.target.value)} placeholder="Your name" /></Field>
                <Field label="Email"><input className="input" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" /></Field>
                <Field label="Password"><input className="input" type="password" required minLength={6} value={password} onChange={(e) => setPassword(e.target.value)} placeholder="At least 6 characters" /></Field>
                {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                <button disabled={busy} className="btn btn-primary btn-block" style={{ padding: '11px 16px', fontSize: 14 }}>{busy ? 'Creating…' : 'Create account'}</button>
            </form>
            <p style={{ marginTop: 24, fontSize: 14, opacity: 0.7 }}>
                Already have an account? <Link to={withSearch('/account/login', search)}>Sign in</Link>
            </p>
            <p style={{ marginTop: 8, fontSize: 13, opacity: 0.45 }}>
                Reselling to customers? <Link to="/reseller/signup">Reseller sign-up</Link>
            </p>
        </AccountSplitShell>
    )
}
