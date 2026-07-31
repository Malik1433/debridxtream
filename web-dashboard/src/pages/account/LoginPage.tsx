import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { sendPasswordResetEmail, signInWithEmailAndPassword } from 'firebase/auth'
import { auth } from '../../firebase'
import { CardShell, Field, errText, withSearch } from './accountUi'

export default function AccountLoginPage() {
    const nav = useNavigate()
    const { search } = useLocation()
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const [notice, setNotice] = useState('')

    async function submit(e: React.FormEvent) {
        e.preventDefault()
        setError(''); setNotice('')
        setBusy(true)
        try {
            await signInWithEmailAndPassword(auth, email.trim(), password)
            nav(withSearch('/account', search), { replace: true })
        } catch (err: unknown) {
            setError(errText(err))
        } finally {
            setBusy(false)
        }
    }

    async function resetPassword() {
        setError(''); setNotice('')
        if (!email.trim()) { setError('Enter your email first, then tap "Forgot password".'); return }
        try {
            await sendPasswordResetEmail(auth, email.trim())
            // Deliberately does not reveal whether the address is registered.
            setNotice('If that address has an account, a reset link is on its way.')
        } catch (err: unknown) {
            setError(errText(err))
        }
    }

    return (
        <CardShell title="Sign in" subtitle="Manage your devices and playlists.">
            <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                <Field label="Email"><input className="input" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" /></Field>
                <Field label="Password"><input className="input" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Your password" /></Field>
                {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                {notice && <p style={{ color: 'var(--dx-success)', fontSize: 13, margin: 0 }}>{notice}</p>}
                <button disabled={busy} className="btn btn-primary btn-block" style={{ padding: '11px 16px', fontSize: 14 }}>{busy ? 'Signing in…' : 'Sign in'}</button>
            </form>
            <button type="button" onClick={resetPassword} className="btn btn-ghost" style={{ marginTop: 14, opacity: 0.6, fontSize: 13, padding: 0 }}>Forgot password?</button>
            <p style={{ marginTop: 20, fontSize: 14, opacity: 0.7 }}>
                New here? <Link to={withSearch('/account/signup', search)}>Create an account</Link>
            </p>
        </CardShell>
    )
}
