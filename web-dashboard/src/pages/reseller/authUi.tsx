import { Check } from 'lucide-react'

/** DX logomark: Cinema brand mark + wordmark. */
export function DxLogo({ onDark = true }: { onDark?: boolean } = {}) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <img 
                src="/brand/logo-icon-cinema.png" 
                alt="DX Play" 
                style={{ width: 34, height: 34, objectFit: 'contain' }} 
                onError={(e) => {
                    e.currentTarget.style.display = 'none';
                }}
            />
            <span style={{ fontFamily: 'Archivo', fontWeight: 900, fontSize: 19, color: onDark ? '#FFFFFF' : '#0A0908', letterSpacing: '-0.02em' }}>
                DX<span style={{ color: '#EC3013' }}>Play</span>
            </span>
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
            <div style={{ background: '#12100F', borderRight: '1px solid rgba(255,255,255,0.1)', color: '#F5F5F4', padding: '48px 40px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }} className="split-panel">
                <DxLogo />
                <div>
                    <h2 style={{ color: '#FFFFFF', fontSize: 28, marginBottom: 24, fontWeight: 900 }}>Sell app licenses.<br /><span style={{ color: '#EC3013' }}>Keep 100% profit.</span></h2>
                    <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 18 }}>
                        {['Buy wholesale license credits', 'Activate any TV with 4-digit code', 'Renew & track expiry in one dashboard', 'Set your own retail prices'].map((b) => (
                            <li key={b} style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 14, color: '#D6D3D1' }}>
                                <span style={{ width: 20, height: 20, borderRadius: 6, background: '#EC3013', color: '#FFFFFF', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none', boxShadow: '0 0 10px rgba(236,48,19,0.4)' }}><Check size={13} strokeWidth={3} /></span>{b}
                            </li>
                        ))}
                    </ul>
                </div>
                <p style={{ fontSize: 12, color: '#78716C', margin: 0 }}>© 2026 DX Play — Software Reseller Portal</p>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '40px', background: '#0A0908' }}>
                <div style={{ width: '100%', maxWidth: 460, margin: '0 auto', background: '#141211', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 20, padding: 36, boxShadow: '0 20px 50px rgba(0,0,0,0.6)' }}>
                    <div className="kicker" style={{ marginBottom: 10 }}>Reseller Program</div>
                    <h2 style={{ marginBottom: 6 }}>{title}</h2>
                    <p style={{ opacity: 0.6, fontSize: 14, marginTop: 0, marginBottom: 28 }}>{subtitle}</p>
                    {children}
                </div>
            </div>
        </div>
    )
}

/** Centered card used by Sign In / Verify. */
export function CardShell({ title, subtitle, children, maxWidth = 440 }: { title: string; subtitle: string; children: React.ReactNode; maxWidth?: number }) {
    return (
        <div className="mod" style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24, background: '#0A0908' }}>
            <div style={{ width: '100%', maxWidth, background: '#141211', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 24, padding: 36, boxShadow: '0 20px 50px rgba(0,0,0,0.7)' }}>
                <div style={{ marginBottom: 24, display: 'flex', justifyContent: 'center' }}><DxLogo /></div>
                <h2 style={{ marginBottom: 6, textAlign: 'center' }}>{title}</h2>
                <p style={{ opacity: 0.6, fontSize: 14, marginTop: 0, marginBottom: 28, textAlign: 'center' }}>{subtitle}</p>
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
