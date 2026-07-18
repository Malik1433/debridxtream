import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { signInWithEmailAndPassword } from 'firebase/auth'
import { auth } from '../../firebase'
import { AuthShell, Field, btnCls, errText, inputCls } from './authUi'

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
        try {
            await signInWithEmailAndPassword(auth, email.trim(), password)
            nav('/reseller')
        } catch (err: unknown) {
            setError(errText(err))
        } finally {
            setBusy(false)
        }
    }

    return (
        <AuthShell title="Reseller sign in" subtitle="Manage your clients and credits.">
            <form onSubmit={submit} className="space-y-4">
                <Field label="Email">
                    <input className={inputCls} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
                </Field>
                <Field label="Password">
                    <input className={inputCls} type="password" required value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Your password" />
                </Field>
                {error && <p className="text-sm text-red-400">{error}</p>}
                <button disabled={busy} className={btnCls}>{busy ? 'Signing in…' : 'Sign in'}</button>
            </form>
            <p className="mt-6 text-center text-sm text-neutral-400">
                New here?{' '}
                <Link to="/reseller/signup" className="text-gold-400 hover:underline">Create an account</Link>
            </p>
        </AuthShell>
    )
}
