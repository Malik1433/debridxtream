import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import ConfigPage from './pages/ConfigPage'
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
                </Routes>
            </div>
        </Router>
    )
}

export default App
