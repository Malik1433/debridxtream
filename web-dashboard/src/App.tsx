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
                </Routes>
            </div>
        </Router>
    )
}

export default App
