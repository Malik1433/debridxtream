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
                <p style={{ fontSize: 12, opacity: 0.4, margin: 0 }}>© 2026 DX Play</p>
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
/**
 * Google's four-colour mark, inline.
 *
 * Inline rather than an <img>: Google's brand guidelines require the real mark next to "Continue
 * with Google", and loading it from a CDN would put a third-party request on the sign-in page.
 */
export function GoogleMark({ size = 16 }: { size?: number }) {
    return (
        <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden="true" style={{ flex: 'none' }}>
            <path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9 3.6l6.7-6.7C35.6 2.6 30.2 0 24 0 14.6 0 6.5 5.4 2.6 13.2l7.8 6.1C12.3 13.2 17.7 9.5 24 9.5z" />
            <path fill="#4285F4" d="M46.1 24.6c0-1.6-.1-3.1-.4-4.6H24v9.1h12.4c-.5 2.9-2.2 5.400-4.6 7.1l7.2 5.6c4.2-3.9 6.6-9.6 6.6-16.4z" />
            <path fill="#FBBC05" d="M10.4 28.7c-.5-1.4-.8-2.9-.8-4.4s.3-3 .8-4.4l-7.8-6.1C1 16.9 0 20.3 0 24s1 7.1 2.6 10.2l7.8-5.5z" />
            <path fill="#34A853" d="M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.2-5.6c-2 1.4-4.6 2.2-8.7 2.2-6.3 0-11.7-3.7-13.6-9.1l-7.8 5.5C6.5 42.6 14.6 48 24 48z" />
        </svg>
    )
}

export function nextTarget(search: string, fallback = '/account'): string {
    const raw = new URLSearchParams(search).get('next')
    if (!raw || !raw.startsWith('/') || raw.startsWith('//')) return fallback
    return raw
}
