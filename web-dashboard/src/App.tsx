import { BrowserRouter as Router, Routes, Route } from 'react-router-dom'
import LandingPage from './pages/LandingPage'
import ConfigPage from './pages/ConfigPage'
import MagicLinkPage from './pages/MagicLinkPage'
import './index.css'

function App() {
    return (
        <Router>
            <div className="min-h-screen bg-neutral-950 text-white selection:bg-gold-500/30">
                <Routes>
                    <Route path="/" element={<LandingPage />} />
                    <Route path="/config" element={<ConfigPage />} />
                    <Route path="/setup" element={<MagicLinkPage />} />
                </Routes>
            </div>
        </Router>
    )
}

export default App
