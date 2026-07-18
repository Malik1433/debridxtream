import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import ConfigPage from './pages/ConfigPage'
import SignupPage from './pages/reseller/SignupPage'
import LoginPage from './pages/reseller/LoginPage'
import VerifyEmailPage from './pages/reseller/VerifyEmailPage'
import DashboardPage from './pages/reseller/DashboardPage'
import './index.css'

const RedirectToRoot = () => {
    const location = useLocation()
    return <Navigate to={`/${location.search}`} replace />
}

function App() {
    return (
        <Router>
            <div className="min-h-screen bg-neutral-950 text-white selection:bg-gold-500/30">
                <Routes>
                    <Route path="/" element={<ConfigPage />} />
                    <Route path="/config" element={<RedirectToRoot />} />
                    <Route path="/setup" element={<RedirectToRoot />} />
                    {/* Reseller dashboard */}
                    <Route path="/reseller" element={<DashboardPage />} />
                    <Route path="/reseller/login" element={<LoginPage />} />
                    <Route path="/reseller/signup" element={<SignupPage />} />
                    <Route path="/reseller/verify" element={<VerifyEmailPage />} />
                </Routes>
            </div>
        </Router>
    )
}

export default App
