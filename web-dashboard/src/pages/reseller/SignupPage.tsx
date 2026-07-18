import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createUserWithEmailAndPassword, sendEmailVerification, updateProfile } from 'firebase/auth'
import { doc, serverTimestamp, setDoc } from 'firebase/firestore'
import { auth, db } from '../../firebase'
import { COUNTRIES, Field, SplitShell, errText } from './authUi'

export default function SignupPage() {
    const nav = useNavigate()
    const [form, setForm] = useState({ name: '', email: '', password: '', phone: '', country: '', contact: '' })
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
        setForm((f) => ({ ...f, [k]: e.target.value }))

    async function submit(e: React.FormEvent) {
        e.preventDefault()
        setError('')
        if (!form.name.trim()) { setError('Enter your business / display name.'); return }
        if (form.password.length < 6) { setError('Password must be at least 6 characters.'); return }
        setBusy(true)
        try {
            const cred = await createUserWithEmailAndPassword(auth, form.email.trim(), form.password)
            try { await updateProfile(cred.user, { displayName: form.name.trim() }) } catch { /* non-fatal */ }
            await setDoc(doc(db, 'resellers', cred.user.uid), {
                email: form.email.trim(), displayName: form.name.trim(), phone: form.phone.trim(),
                country: form.country, contact: form.contact.trim(),
                credits: 0, status: 'active', clientCount: 0, createdAt: serverTimestamp(),
            })
            try { await sendEmailVerification(cred.user) } catch { /* non-fatal */ }
            nav('/reseller/verify')
        } catch (err: unknown) { setError(errText(err)) } finally { setBusy(false) }
    }

    return (
        <SplitShell title="Become a reseller" subtitle="Create your account, buy credits, and manage your own clients.">
            <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                <Field label="Business / display name"><input className="input" value={form.name} onChange={set('name')} placeholder="Acme TV" /></Field>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                    <Field label="Email"><input className="input" type="email" required value={form.email} onChange={set('email')} placeholder="you@example.com" /></Field>
                    <Field label="Country">
                        <select className="input" value={form.country} onChange={set('country')}>
                            <option value="">Select…</option>
                            {COUNTRIES.map((c) => <option key={c} value={c}>{c}</option>)}
                        </select>
                    </Field>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                    <Field label="Phone"><input className="input" value={form.phone} onChange={set('phone')} placeholder="+1 555 0100" /></Field>
                    <Field label="WhatsApp / Telegram"><input className="input" value={form.contact} onChange={set('contact')} placeholder="@handle (optional)" /></Field>
                </div>
                <Field label="Password"><input className="input" type="password" required value={form.password} onChange={set('password')} placeholder="At least 6 characters" /></Field>
                {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                <button disabled={busy} className="btn btn-primary btn-block" style={{ marginTop: 4, padding: '11px 16px', fontSize: 14 }}>{busy ? 'Creating…' : 'Create account'}</button>
            </form>
            <p style={{ marginTop: 24, fontSize: 14, opacity: 0.7 }}>Already have an account? <Link to="/reseller/login">Sign in</Link></p>
        </SplitShell>
    )
}
