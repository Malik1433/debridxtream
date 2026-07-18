import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { signInWithEmailAndPassword } from 'firebase/auth'
import { auth } from '../../firebase'
import { CardShell, Field, errText } from './authUi'

export default function LoginPage() {
    const nav = useNavigate()
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')

    async function submit(e: React.FormEvent) {
        e.preventDefault()
        setError('')
        setBusy(true)
        try { await signInWithEmailAndPassword(auth, email.trim(), password); nav('/reseller') }
        catch (err: unknown) { setError(errText(err)) } finally { setBusy(false) }
    }

    return (
        <CardShell title="Reseller sign in" subtitle="Manage your clients and credits.">
            <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                <Field label="Email"><input className="input" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" /></Field>
                <Field label="Password"><input className="input" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Your password" /></Field>
                {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                <button disabled={busy} className="btn btn-primary btn-block" style={{ padding: '11px 16px', fontSize: 14 }}>{busy ? 'Signing in…' : 'Sign in'}</button>
            </form>
            <p style={{ marginTop: 24, fontSize: 14, opacity: 0.7 }}>New here? <Link to="/reseller/signup">Create an account</Link></p>
        </CardShell>
    )
}
