import { Check } from 'lucide-react'
import { DxLogo } from '../reseller/authUi'

// The generic pieces (CardShell, Field, errText) are shared with the reseller portal as-is. They are
// re-exported here so account pages have one import path, and so the reseller files — which are
// deployed and working — do not have to be edited to introduce this section.
export { CardShell, Field, errText, COUNTRIES } from '../reseller/authUi'

/**
 * Split layout for customer sign-up. Same shape as the reseller one, different promise: the reseller
 * panel sells earnings, this one sells "your TVs, set up from your phone".
 */
export function AccountSplitShell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
    return (
        <div className="mod mod-split">
            <div style={{ background: '#201e1d', color: '#f3f2f2', padding: '40px 36px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }} className="split-panel">
                <DxLogo onDark />
                <div>
                    <h2 style={{ color: '#f3f2f2', fontSize: 26, marginBottom: 24 }}>Your TVs,<br />set up from your phone.</h2>
                    <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 16 }}>
                        {['Scan the code on your TV to add it', 'Keep your playlists in one place', 'Edit once — every TV updates', 'Up to 3 devices on one account'].map((b) => (
                            <li key={b} style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 14, opacity: 0.9 }}>
                                <span style={{ width: 18, height: 18, background: '#ec3013', color: '#f3f2f2', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}><Check size={12} strokeWidth={3} /></span>{b}
                            </li>
                        ))}
                    </ul>
                </div>
                <p style={{ fontSize: 12, opacity: 0.4, margin: 0 }}>© 2026 DebridXtream</p>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '40px' }}>
                <div style={{ width: '100%', maxWidth: 460, margin: '0 auto' }}>
                    <div className="kicker" style={{ marginBottom: 10 }}>My Account</div>
                    <h2 style={{ marginBottom: 6 }}>{title}</h2>
                    <p style={{ opacity: 0.6, fontSize: 15, marginTop: 0, marginBottom: 28 }}>{subtitle}</p>
                    {children}
                </div>
            </div>
        </div>
    )
}

/**
 * Carries the query string across an auth navigation.
 *
 * The TV's QR will arrive as `/link?code=XXXX` (U5) and the customer may be bounced through sign-up
 * and email verification first. Losing the code somewhere in that detour would drop them on an empty
 * account page with no idea what went wrong, so every internal auth link preserves it from the start.
 */
export function withSearch(path: string, search: string): string {
    return search && search !== '?' ? `${path}${search}` : path
}

/**
 * Where to land after signing in or verifying.
 *
 * Preserving the code was not enough on its own: a customer arriving from the TV's QR was bounced to
 * sign-in, came back, and landed on the account page — holding the code but nowhere near the screen
 * that uses it. `next` carries the destination too.
 *
 * Only same-site paths are accepted. A `next` that could point at another origin would turn the
 * sign-in page into an open redirect, which is a phishing tool.
 */
export function nextTarget(search: string, fallback = '/account'): string {
    const raw = new URLSearchParams(search).get('next')
    if (!raw || !raw.startsWith('/') || raw.startsWith('//')) return fallback
    return raw
}
