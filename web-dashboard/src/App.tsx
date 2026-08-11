import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import SignupPage from './pages/reseller/SignupPage'
import LoginPage from './pages/reseller/LoginPage'
import VerifyEmailPage from './pages/reseller/VerifyEmailPage'
import DashboardPage from './pages/reseller/DashboardPage'
import AccountPage from './pages/account/AccountPage'
import AccountLoginPage from './pages/account/LoginPage'
import AccountSignupPage from './pages/account/SignupPage'
import AccountVerifyEmailPage from './pages/account/VerifyEmailPage'
import PlaylistsPage from './pages/account/PlaylistsPage'
import LinkDevicePage from './pages/account/LinkDevicePage'
import DevicesPage from './pages/account/DevicesPage'
import './index.css'

/**
 * §7 U8b: the legacy config page is gone — it existed to type IPTV credentials into a phone and
 * push them through `device_codes`, which is exactly the channel §7 closed. Its addresses live on
 * because they are printed inside older TV builds, so they now land on the account claim page
 * instead of 404-ing.
 */
const RedirectToLink = () => {
    const location = useLocation()
    return <Navigate to={`/link${location.search}`} replace />
}

/**
 * Any address that matches no route used to render NOTHING — React mounted an empty <Routes> and
 * the dark page background did the rest, so a typo, a stale link, or a path that is supposed to be
 * rewritten away by the host all looked identical: a black screen with no way out.
 *
 * It also matters as a diagnostic. /admin is served by a Vercel rewrite to the panel's own host;
 * if that rewrite ever stops applying, the request falls through to this app, and "not found" says
 * so plainly where a blank page said nothing at all.
 */
const NotFound = () => {
    const location = useLocation()
    return (
        <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
            <div style={{ maxWidth: 380, textAlign: 'center' }}>
                <div style={{ fontSize: 13, letterSpacing: '0.08em', textTransform: 'uppercase', opacity: 0.5 }}>DX Play</div>
                <h1 style={{ fontSize: 26, fontWeight: 700, margin: '10px 0 6px' }}>Page not found</h1>
                <p style={{ opacity: 0.6, fontSize: 14, margin: '0 0 20px' }}>
                    Nothing lives at <code>{location.pathname}</code>.
                </p>
                <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
                    <a href="/link" style={{ padding: '10px 16px', borderRadius: 8, background: '#fff', color: '#0a0a0a', fontWeight: 600, fontSize: 14 }}>Add a TV</a>
                    <a href="/account" style={{ padding: '10px 16px', borderRadius: 8, border: '1px solid rgba(255,255,255,0.2)', fontSize: 14 }}>My account</a>
                </div>
            </div>
        </div>
    )
}

function App() {
    return (
        <Router>
            <div className="min-h-screen bg-neutral-950 text-white selection:bg-gold-500/30">
                <Routes>
                    <Route path="/" element={<RedirectToLink />} />
                    <Route path="/config" element={<RedirectToLink />} />
                    <Route path="/setup" element={<RedirectToLink />} />
                    {/* Reseller dashboard */}
                    <Route path="/reseller" element={<DashboardPage />} />
                    <Route path="/reseller/login" element={<LoginPage />} />
                    <Route path="/reseller/signup" element={<SignupPage />} />
                    <Route path="/reseller/verify" element={<VerifyEmailPage />} />
                    {/* Customer account — a different audience from /reseller (§7.0): resellers pay
                        per device with no limit, customers get a device-limited subscription. Kept as
                        separate route trees so neither can drift into the other's flow. */}
                    <Route path="/account" element={<AccountPage />} />
                    <Route path="/account/login" element={<AccountLoginPage />} />
                    <Route path="/account/signup" element={<AccountSignupPage />} />
                    <Route path="/account/verify" element={<AccountVerifyEmailPage />} />
                    <Route path="/account/playlists" element={<PlaylistsPage />} />
                    <Route path="/account/devices" element={<DevicesPage />} />
                    {/* The TV's QR points here. Short path on purpose — it gets typed by hand when a
                        phone camera won't cooperate. */}
                    <Route path="/link" element={<LinkDevicePage />} />
                    <Route path="/account/link" element={<LinkDevicePage />} />
                    <Route path="*" element={<NotFound />} />
                </Routes>
            </div>
        </Router>
    )
}

export default App
