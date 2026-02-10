import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Tv, Cloud, Save, CheckCircle, AlertCircle, Laptop, Smartphone, ArrowRight, ChevronLeft } from 'lucide-react';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Utility for Tailwind class merging
 */
function cn(...inputs: ClassValue[]) {
    return twMerge(clsx(inputs));
}

/**
 * DebridXtream Companion Portal: A premium web interface for configuring 
 * IPTV and Debrid settings on the Android TV app.
 * 
 * V2.2 Flow:
 * 1. Landing: Enter Device Key (Pairing Code)
 * 2. Configuration: Enter IPTV / Debrid details
 * 3. Sync: Push to Relay
 */
export default function App() {
    // State: 'pairing' (Step 1) or 'config' (Step 2)
    const [step, setStep] = useState<'pairing' | 'config'>('pairing');

    // Config View State
    const [activeTab, setActiveTab] = useState<'iptv' | 'debrid'>('iptv');

    const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
    const [message, setMessage] = useState('');

    // Form states
    const [iptv, setIptv] = useState({ serverUrl: '', username: '', password: '' });
    const [debrid, setDebrid] = useState({ token: '', mediaFusionUrl: '' });

    // Remote Pairing Code
    const [remoteCode, setRemoteCode] = useState('');

    useEffect(() => {
        // Attempt to load previous session data
        const savedIptv = localStorage.getItem('iptv_config');
        if (savedIptv) setIptv(JSON.parse(savedIptv));

        // Check if we have a saved code to maybe auto-advance (optional, good for UX)
        const savedCode = localStorage.getItem('device_code');
        if (savedCode && savedCode.length === 6) {
            setRemoteCode(savedCode);
        }
    }, []);

    /**
     * Step 1: Validate Code and Move to Config
     */
    /**
     * Step 1: Validate Code and Move to Config
     */
    const handleConnect = async () => {
        if (remoteCode.length !== 6) {
            setStatus('error');
            setMessage('Please enter a valid 6-digit code');
            setTimeout(() => setStatus('idle'), 3000);
            return;
        }

        setStatus('loading');

        try {
            // Real Server-Side Validation
            const response = await fetch(`https://relay.debridxtream.com/api/pair/validate?code=${remoteCode}`);
            const data = await response.json();

            if (response.ok && data.valid) {
                setStatus('success');
                setMessage('Device Found!');
                if ('vibrate' in navigator) navigator.vibrate([50, 50, 50]);

                // Store and advance after short delay
                localStorage.setItem('device_code', remoteCode);
                setTimeout(() => {
                    setStatus('idle');
                    setStep('config');
                }, 1000);
            } else {
                throw new Error(data.message || "Invalid or expired code.");
            }
        } catch (err: any) {
            setStatus('error');
            setMessage(err.message || "Connection failed");
            // Reset status after delay to allow retry
            setTimeout(() => setStatus('idle'), 4000);
        }
    };

    /**
     * Step 2: Send Configuration
     */
    const handleSync = async () => {
        setStatus('loading');
        try {
            const payload = {
                iptv: iptv.serverUrl ? iptv : null,
                debrid: debrid.token ? debrid : null,
            };

            // Save to local storage
            localStorage.setItem('iptv_config', JSON.stringify(iptv));

            const endpoint = `https://relay.debridxtream.com/api/pair/config`;

            // Wrap payload with the code
            const remotePayload = {
                code: remoteCode,
                config: payload
            };

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(remotePayload),
            });

            if (!response.ok) throw new Error("Relay Server not accessible. Have you deployed it?");

            const data = await response.json();
            if (data.success) {
                setStatus('success');
                setMessage(`Sent to TV! (Code: ${remoteCode})`);
                if ('vibrate' in navigator) navigator.vibrate(100);
                setTimeout(() => setStatus('idle'), 4000);
            } else {
                throw new Error(data.message || "Remote sync failed");
            }

        } catch (err: any) {
            console.error(err);
            setStatus('error');
            setMessage(err.message || 'Unable to connect. Check Relay.');
        }
    };

    return (
        <div className="min-h-screen p-4 md:p-8 flex flex-col items-center justify-center">
            {/* Header Section */}
            <motion.header
                layout
                initial={{ y: -20, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                transition={{ duration: 0.6 }}
                className="text-center mb-8 relative z-20"
            >
                <div className="inline-block p-[2px] rounded-2xl bg-gradient-to-tr from-gold-primary via-white/50 to-gold-secondary mb-6 shadow-[0_0_30px_rgba(255,215,0,0.3)]">
                    <div className="bg-black rounded-xl p-3 px-5 flex items-center gap-2">
                        <span className="text-gold-primary font-display font-black text-2xl italic tracking-tighter">DX</span>
                    </div>
                </div>
                <h1 className="text-4xl md:text-6xl font-display font-black text-transparent bg-clip-text bg-gradient-to-b from-gold-primary to-gold-secondary mb-4 tracking-tight drop-shadow-sm">
                    {step === 'pairing' ? "Connect Device (Wizard Mode)" : "Configure Setup"}
                </h1>
                <p className="text-white/60 text-lg md:text-xl max-w-md mx-auto leading-relaxed font-light">
                    {step === 'pairing'
                        ? "Enter the 6-digit code displayed on your TV"
                        : `Connected to device ${remoteCode}`}
                </p>
                <div className="mt-4 px-3 py-1 bg-gold-primary/20 rounded-full inline-block border border-gold-primary/30">
                    <p className="text-gold-primary text-xs font-bold tracking-widest uppercase">v2.3 Active</p>
                </div>
            </motion.header>

            {/* Main Card */}
            <AnimatePresence mode="wait">
                {step === 'pairing' ? (
                    <motion.main
                        key="step-pairing"
                        initial={{ opacity: 0, scale: 0.95 }}
                        animate={{ opacity: 1, scale: 1 }}
                        exit={{ opacity: 0, scale: 0.95, y: -20 }}
                        className="w-full max-w-md"
                    >
                        <div className="glass rounded-[2.5rem] p-8 md:p-12 relative overflow-hidden shadow-[0_32px_64px_-16px_rgba(0,0,0,0.6)]">
                            {/* Glow Effect */}
                            <div className="absolute top-0 left-1/2 -translate-x-1/2 w-32 h-32 bg-gold-primary/20 blur-[60px] rounded-full pointer-events-none" />

                            <div className="space-y-8 relative z-10">
                                <div>
                                    <label className="block text-xs font-black text-gold-primary uppercase tracking-widest opacity-80 mb-4 text-center">
                                        Device Pairing Key
                                    </label>
                                    <div className="relative group">
                                        <input
                                            type="text"
                                            maxLength={6}
                                            placeholder="000000"
                                            value={remoteCode}
                                            onChange={(e) => setRemoteCode(e.target.value.toUpperCase())}
                                            className="w-full bg-black/40 border-2 border-white/10 rounded-2xl py-6 text-center text-4xl md:text-5xl font-black tracking-[0.5em] uppercase text-white outline-none focus:border-gold-primary focus:shadow-[0_0_30px_rgba(255,215,0,0.15)] transition-all placeholder:text-white/5"
                                        />
                                    </div>
                                    <p className="text-center text-white/40 text-xs mt-4 font-medium">
                                        Found in Settings {'>'} Companion App
                                    </p>
                                </div>

                                <button
                                    onClick={handleConnect}
                                    className="w-full py-5 rounded-2xl font-black text-lg flex items-center justify-center gap-3 bg-gradient-to-r from-gold-primary to-gold-secondary text-velvet-black shadow-[0_10px_30px_-5px_rgba(255,215,0,0.3)] hover:scale-[1.02] active:scale-[0.98] transition-all group"
                                >
                                    <span>CONNECT</span>
                                    <ArrowRight size={22} className="group-hover:translate-x-1 transition-transform" />
                                </button>
                            </div>
                        </div>
                    </motion.main>
                ) : (
                    <motion.main
                        key="step-config"
                        initial={{ opacity: 0, y: 20 }}
                        animate={{ opacity: 1, y: 0 }}
                        className="w-full max-w-lg glass rounded-[2.5rem] overflow-hidden shadow-[0_32px_64px_-16px_rgba(0,0,0,0.6)] relative z-10"
                    >
                        {/* Back Button */}
                        <button
                            onClick={() => setStep('pairing')}
                            className="absolute top-4 left-4 p-2 text-white/50 hover:text-white hover:bg-white/10 rounded-full transition-colors z-20"
                        >
                            <ChevronLeft size={24} />
                        </button>

                        {/* Tab Selection */}
                        <div className="flex bg-black/20 p-2 gap-2 border-b border-white/5 backdrop-blur-md pt-16 md:pt-2">
                            <TabButton
                                active={activeTab === 'iptv'}
                                onClick={() => setActiveTab('iptv')}
                                icon={<Tv size={20} />}
                                label="IPTV"
                            />
                            <TabButton
                                active={activeTab === 'debrid'}
                                onClick={() => setActiveTab('debrid')}
                                icon={<Cloud size={20} />}
                                label="Debrid"
                            />
                        </div>

                        {/* Dynamic Form Area */}
                        <div className="p-8 md:p-10">
                            <AnimatePresence mode="wait">
                                {activeTab === 'iptv' ? (
                                    <motion.div
                                        key="iptv-form"
                                        initial={{ x: -10, opacity: 0 }}
                                        animate={{ x: 0, opacity: 1 }}
                                        exit={{ x: 10, opacity: 0 }}
                                        transition={{ duration: 0.3 }}
                                        className="space-y-6"
                                    >
                                        <div className="flex items-center gap-4 mb-2">
                                            <div className="w-10 h-10 rounded-full bg-gold-primary/10 flex items-center justify-center text-gold-primary">
                                                <Tv size={24} />
                                            </div>
                                            <h2 className="text-xl font-bold">XCodes Credentials</h2>
                                        </div>

                                        <InputField
                                            label="Portal URL"
                                            placeholder="https://example.tv:8080"
                                            value={iptv.serverUrl}
                                            onChange={(e: any) => setIptv({ ...iptv, serverUrl: e.target.value })}
                                        />
                                        <InputField
                                            label="Username"
                                            placeholder="Your username"
                                            value={iptv.username}
                                            onChange={(e: any) => setIptv({ ...iptv, username: e.target.value })}
                                        />
                                        <InputField
                                            label="Password"
                                            type="password"
                                            placeholder="Your password"
                                            value={iptv.password}
                                            onChange={(e: any) => setIptv({ ...iptv, password: e.target.value })}
                                        />
                                    </motion.div>
                                ) : (
                                    <motion.div
                                        key="debrid-form"
                                        initial={{ x: 10, opacity: 0 }}
                                        animate={{ x: 0, opacity: 1 }}
                                        exit={{ x: -10, opacity: 0 }}
                                        transition={{ duration: 0.3 }}
                                        className="space-y-6"
                                    >
                                        <div className="flex items-center gap-4 mb-2">
                                            <div className="w-10 h-10 rounded-full bg-emerald-500/10 flex items-center justify-center text-emerald-400">
                                                <Cloud size={24} />
                                            </div>
                                            <h2 className="text-xl font-bold">Premium Services</h2>
                                        </div>

                                        <InputField
                                            label="Real-Debrid API Token"
                                            placeholder="Paste token here"
                                            type="password"
                                            value={debrid.token}
                                            onChange={(e: any) => setDebrid({ ...debrid, token: e.target.value })}
                                            helperText={<a href="https://real-debrid.com/apitoken" target="_blank" rel="noreferrer" className="underline hover:text-white">Get yours from my-account page</a>}
                                        />
                                        <InputField
                                            label="MediaFusion Addon URL"
                                            placeholder="stremio://mediafusion..."
                                            value={debrid.mediaFusionUrl}
                                            onChange={(e: any) => setDebrid({ ...debrid, mediaFusionUrl: e.target.value })}
                                            helperText="Supports stremio:// or https:// formats"
                                        />
                                    </motion.div>
                                )}
                            </AnimatePresence>

                            {/* Action Trigger */}
                            <button
                                onClick={handleSync}
                                disabled={status === 'loading'}
                                className={cn(
                                    "w-full mt-10 py-5 rounded-2xl font-black text-lg flex items-center justify-center gap-3 transition-all active:scale-[0.98] shadow-[0_20px_40px_-10px_rgba(255,215,0,0.3)] group overflow-hidden relative",
                                    status === 'loading'
                                        ? "bg-gold-primary/40 cursor-not-allowed"
                                        : "bg-gradient-to-r from-gold-primary to-gold-secondary text-velvet-black hover:shadow-gold-primary/50"
                                )}
                            >
                                <div className="absolute inset-0 bg-white/20 translate-x-[-100%] group-hover:translate-x-[100%] transition-transform duration-700" />
                                {status === 'loading' ? (
                                    <div className="w-6 h-6 border-4 border-velvet-black/20 border-t-velvet-black rounded-full animate-spin" />
                                ) : (
                                    <>
                                        <Save size={22} className="group-hover:rotate-12 transition-transform" />
                                        <span>SAVE TO TV</span>
                                    </>
                                )}
                            </button>

                            {/* Feedback Overlay */}
                            <AnimatePresence>
                                {status !== 'idle' && status !== 'loading' && (
                                    <motion.div
                                        initial={{ y: 20, opacity: 0 }}
                                        animate={{ y: 0, opacity: 1 }}
                                        exit={{ y: -20, opacity: 0 }}
                                        className={cn(
                                            "mt-8 p-5 rounded-2xl flex items-center gap-4",
                                            status === 'success' ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20" : "bg-rose-500/10 text-rose-400 border border-rose-500/20"
                                        )}
                                    >
                                        {status === 'success' ? <CheckCircle size={24} /> : <AlertCircle size={24} />}
                                        <p className="font-bold text-sm">{message}</p>
                                    </motion.div>
                                )}
                            </AnimatePresence>
                        </div>
                    </motion.main>
                )}
            </AnimatePresence>

            {/* Information Footer */}
            <footer className="mt-auto py-10 w-full max-w-lg">
                <div className="flex flex-col items-center gap-6 text-white/30">
                    <div className="flex items-center gap-5 glass p-3 px-6 rounded-full text-xs font-bold uppercase tracking-widest">
                        <div className="flex items-center gap-2">
                            <Smartphone size={14} />
                            <span>Portal Active</span>
                        </div>
                        <div className="w-1.5 h-1.5 bg-gold-primary/40 rounded-full" />
                        <div className="flex items-center gap-2">
                            <Laptop size={14} />
                            <span>v2.2 Stable</span>
                        </div>
                    </div>

                    <div className="text-center space-y-2">
                        <p className="text-[10px] uppercase tracking-widest font-black opacity-50">Powered by DebridXtream Elite</p>
                    </div>
                </div>
            </footer>
        </div>
    );
}

/**
 * Modern Tab Switch Component
 */
function TabButton({ active, onClick, icon, label }: { active: boolean, onClick: () => void, icon: React.ReactNode, label: string }) {
    return (
        <button
            onClick={onClick}
            className={cn(
                "flex-1 py-4 px-2 rounded-2xl flex items-center justify-center gap-3 transition-all duration-500 relative",
                active ? "bg-gold-primary text-velvet-black shadow-xl scale-100" : "text-white/30 hover:text-white/60 hover:bg-white/5 active:scale-95"
            )}
        >
            {icon}
            <span className="font-black tracking-tight text-sm uppercase">{label}</span>
            {active && (
                <motion.div
                    layoutId="tab-glow"
                    className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-8 h-1 bg-gold-primary rounded-full blur-sm"
                />
            )}
        </button>
    );
}

/**
 * Premium Themed Input Component
 */
function InputField({ label, placeholder, type = 'text', value, onChange, helperText }: any) {
    const [isFocused, setIsFocused] = useState(false);

    return (
        <div className="space-y-2.5">
            <div className="flex justify-between items-end ml-1">
                <label className="text-xs font-black text-gold-primary uppercase tracking-widest opacity-80">{label}</label>
            </div>
            <div className={cn(
                "relative transition-all duration-300",
                isFocused ? "scale-[1.01]" : "scale-100"
            )}>
                <input
                    type={type}
                    placeholder={placeholder}
                    value={value}
                    onChange={onChange}
                    onFocus={() => setIsFocused(true)}
                    onBlur={() => setIsFocused(false)}
                    className={cn(
                        "w-full bg-black/40 border rounded-[1.25rem] px-6 py-4.5 outline-none transition-all duration-300 placeholder:text-white/10 text-white font-bold",
                        isFocused ? "border-gold-primary shadow-[0_0_20px_rgba(255,215,0,0.1)]" : "border-white/5"
                    )}
                />
            </div>
            {helperText && <p className="text-[10px] text-white/30 ml-2 font-medium">{helperText}</p>}
        </div>
    );
}
