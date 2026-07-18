import { Check } from 'lucide-react'

/** DX logomark: dark square, gold "DX", + wordmark. */
export function DxLogo({ onDark = false }: { onDark?: boolean }) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 30, height: 30, background: onDark ? '#ec3013' : '#201e1d', color: onDark ? '#f3f2f2' : '#ec3013', fontFamily: 'Archivo', fontWeight: 800, fontSize: 14, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>DX</div>
            <span style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 17, color: onDark ? '#f3f2f2' : 'inherit' }}>DebridXtream</span>
        </div>
    )
}

export function Field({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
    return <label className="field">{label && <span>{label}</span>}{children}</label>
}

/** Split layout used by Sign Up: dark benefits panel (left) + form (right). */
export function SplitShell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
    return (
        <div className="mod mod-split">
            <div style={{ background: '#201e1d', color: '#f3f2f2', padding: '40px 36px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }} className="split-panel">
                <DxLogo onDark />
                <div>
                    <h2 style={{ color: '#f3f2f2', fontSize: 26, marginBottom: 24 }}>Sell streams.<br />Keep the profit.</h2>
                    <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 16 }}>
                        {['Buy credits wholesale', 'Activate any device by its TV code', 'Renew & track expiry in one place', 'Set your own prices'].map((b) => (
                            <li key={b} style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 14, opacity: 0.9 }}>
                                <span style={{ width: 18, height: 18, background: '#ec3013', color: '#f3f2f2', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}><Check size={12} strokeWidth={3} /></span>{b}
                            </li>
                        ))}
                    </ul>
                </div>
                <p style={{ fontSize: 12, opacity: 0.4, margin: 0 }}>© 2026 DebridXtream — reseller portal</p>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '40px' }}>
                <div style={{ width: '100%', maxWidth: 460, margin: '0 auto' }}>
                    <div className="kicker" style={{ marginBottom: 10 }}>Reseller Program</div>
                    <h2 style={{ marginBottom: 6 }}>{title}</h2>
                    <p style={{ opacity: 0.6, fontSize: 15, marginTop: 0, marginBottom: 28 }}>{subtitle}</p>
                    {children}
                </div>
            </div>
        </div>
    )
}

/** Centered card used by Sign In / Verify. */
export function CardShell({ title, subtitle, children, maxWidth = 440 }: { title: string; subtitle: string; children: React.ReactNode; maxWidth?: number }) {
    return (
        <div className="mod" style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
            <div style={{ width: '100%', maxWidth }}>
                <div style={{ marginBottom: 24 }}><DxLogo /></div>
                <h2 style={{ marginBottom: 6 }}>{title}</h2>
                <p style={{ opacity: 0.6, fontSize: 15, marginTop: 0, marginBottom: 28 }}>{subtitle}</p>
                {children}
            </div>
        </div>
    )
}

export function errText(err: unknown): string {
    const code = (err as { code?: string })?.code || ''
    if (code.includes('email-already-in-use')) return 'That email is already registered.'
    if (code.includes('invalid-email')) return 'Enter a valid email address.'
    if (code.includes('weak-password')) return 'Password is too weak (min 6 characters).'
    if (code.includes('wrong-password') || code.includes('invalid-credential')) return 'Wrong email or password.'
    if (code.includes('user-not-found')) return 'No account with that email.'
    if (code.includes('too-many-requests')) return 'Too many attempts — try again later.'
    if (code.includes('network-request-failed')) return 'Network error — check your connection.'
    return (err as { message?: string })?.message || 'Something went wrong.'
}

export const COUNTRIES = [
    'United States', 'United Kingdom', 'Canada', 'Germany', 'France', 'Netherlands',
    'Spain', 'Italy', 'Pakistan', 'India', 'United Arab Emirates', 'Saudi Arabia',
    'Australia', 'Brazil', 'Mexico', 'Türkiye', 'Other',
]
