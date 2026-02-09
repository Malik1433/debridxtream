import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Tv, Cloud, Save, CheckCircle, AlertCircle, Laptop, Smartphone } from 'lucide-react';
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
 */
export default function App() {
    const [activeTab, setActiveTab] = useState<string>('iptv');
    const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
    const [message, setMessage] = useState('');

    // Form states
    const [iptv, setIptv] = useState({ serverUrl: '', username: '', password: '' });
    const [debrid, setDebrid] = useState({ token: '', mediaFusionUrl: '' });

    // Mobile-friendly IP storage
    const [serverAddress, setServerAddress] = useState('');
    const [remoteCode, setRemoteCode] = useState('');

    // State for the 2-step flow
    const [isPaired, setIsPaired] = useState(false);
    const [pairLoading, setPairLoading] = useState(false);
    const FIREBASE_BASE_URL = "https://debridxtream-default-rtdb.firebaseio.com";

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const server = params.get('server') || window.location.host;
        setServerAddress(server.startsWith('http') ? server : `http://${server}`);

        const savedIptv = localStorage.getItem('iptv_config');
        if (savedIptv) setIptv(JSON.parse(savedIptv));
    }, []);

    /**
     * Step 1: Validate the device key against Firebase
     */
    const handlePairDevice = async () => {
        if (remoteCode.length !== 6) {
            setStatus('error');
            setMessage("Please enter a valid 6-digit code from your TV.");
            return;
        }

        setPairLoading(true);
        setStatus('loading');
        try {
            const response = await fetch(`${FIREBASE_BASE_URL}/pairings/${remoteCode}.json`);
            const data = await response.json();

            if (data && data.status === 'pairing') {
                setIsPaired(true);
                setStatus('success');
                setMessage("✅ Device Connected! Please enter your configurations below.");
                setActiveTab('iptv'); // Switch to first config tab

                // Haptic feedback
                if ('vibrate' in navigator) navigator.vibrate([50, 30, 50]);
            } else {
                throw new Error("Invalid or expired code. Please check your TV screen.");
            }
        } catch (err: any) {
            console.error(err);
            setStatus('error');
            setMessage(err.message || "Failed to verify device. Is your TV online?");
        } finally {
            setPairLoading(false);
            if (!isPaired) setTimeout(() => setStatus('idle'), 3000);
        }
    };

    /**
     * Step 2: Sends the current configuration to Firebase (synced status)
     */
    const handleSync = async () => {
        if (!isPaired && activeTab === 'remote') {
            handlePairDevice();
            return;
        }

        setStatus('loading');
        try {
            const payload = {
                iptv: iptv.serverUrl ? iptv : null,
                debrid: debrid.token ? debrid : null,
            };

            localStorage.setItem('iptv_config', JSON.stringify(iptv));

            // Remote Sync via Firebase
            if (isPaired || activeTab === 'remote') {
                const syncPayload = {
                    status: 'synced',
                    config: payload,
                    syncTime: Date.now()
                };

                const response = await fetch(`${FIREBASE_BASE_URL}/pairings/${remoteCode}.json`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(syncPayload),
                });

                if (response.ok) {
                    setStatus('success');
                    setMessage(`🚀 Config pushed to TV! (Code: ${remoteCode})`);
                    if ('vibrate' in navigator) navigator.vibrate(100);
                    setTimeout(() => setStatus('idle'), 4000);
                } else {
                    throw new Error("Failed to push config. Try again.");
                }
                return;
            }

            // Fallback Local Sync (Legacy)
            const response = await fetch(`${serverAddress}/api/config`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });

            if (response.ok) {
                setStatus('success');
                setMessage("Synced to local TV!");
                setTimeout(() => setStatus('idle'), 4000);
            } else {
                throw new Error("Local sync failed. Check IP.");
            }
        } catch (err: any) {
            console.error(err);
            setStatus('error');
            setMessage(err.message || 'Sync failed.');
        }
    };

    return (
        <div className="min-h-screen p-4 md:p-8 flex flex-col items-center">
            {/* Header Section */}
            <motion.header
                initial={{ y: -20, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                className="text-center mb-10 mt-6"
            >
                <div className="inline-block p-1 rounded-2xl bg-gradient-to-tr from-gold-primary to-gold-secondary mb-4 shadow-xl">
                    <div className="bg-velvet-black rounded-xl p-2 px-4 flex items-center gap-2">
                        <span className="text-gold-primary font-black text-xl italic tracking-tighter">DX</span>
                    </div>
                </div>
                <h1 className="text-4xl md:text-5xl font-bold text-gold-primary mb-3 tracking-tight">
                    DX Companion <span className="text-xs align-top opacity-50">v2.0</span>
                </h1>
                <p className="text-white/50 text-base md:text-lg max-w-sm mx-auto leading-relaxed">
                    Premium Setup Portal for DebridXtream
                </p>
            </motion.header>

            {/* Main Card */}
            <motion.main
                initial={{ y: 20, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                className="w-full max-w-lg glass rounded-[2.5rem] overflow-hidden shadow-[0_32px_64px_-16px_rgba(0,0,0,0.6)] relative z-10"
            >
                {/* Step Indicator */}
                <div className="flex bg-black/40 p-4 justify-around border-b border-white/5">
                    <div className={cn("flex flex-col items-center gap-1", !isPaired ? "text-gold-primary" : "text-emerald-400")}>
                        <div className={cn("w-8 h-8 rounded-full border-2 flex items-center justify-center font-bold", !isPaired ? "border-gold-primary" : "border-emerald-400 bg-emerald-400/20")}>
                            {isPaired ? "✓" : "1"}
                        </div>
                        <span className="text-[10px] font-black uppercase">Device</span>
                    </div>
                    <div className="w-12 h-[2px] bg-white/10 mt-4" />
                    <div className={cn("flex flex-col items-center gap-1", isPaired ? "text-gold-primary" : "text-white/20")}>
                        <div className={cn("w-8 h-8 rounded-full border-2 flex items-center justify-center font-bold", isPaired ? "border-gold-primary shadow-[0_0_15px_rgba(255,215,0,0.2)]" : "border-white/10")}>
                            2
                        </div>
                        <span className="text-[10px] font-black uppercase">Setup</span>
                    </div>
                </div>

                {!isPaired ? (
                    /* Step 1: Pair Device */
                    <div className="p-10 space-y-8">
                        <div className="text-center space-y-3">
                            <Laptop className="mx-auto text-gold-primary mb-2" size={48} />
                            <h2 className="text-2xl font-bold">Connect to TV</h2>
                            <p className="text-white/40 text-sm">Enter the 6-digit code shown on your TV screen to begin secure pairing.</p>
                        </div>

                        <div className="flex justify-center flex-col items-center gap-6">
                            <div className="w-full max-w-xs transition-all duration-300 focus-within:scale-105">
                                <input
                                    type="text"
                                    maxLength={6}
                                    placeholder="123456"
                                    value={remoteCode}
                                    onChange={(e) => setRemoteCode(e.target.value.toUpperCase())}
                                    className="w-full bg-black/40 border-2 border-white/5 rounded-3xl py-6 text-center text-4xl font-black text-gold-primary tracking-[0.5em] outline-none focus:border-gold-primary shadow-inner"
                                />
                            </div>

                            <button
                                onClick={handlePairDevice}
                                disabled={pairLoading || remoteCode.length !== 6}
                                className="w-full max-w-xs py-5 rounded-2xl bg-gradient-to-r from-gold-primary to-gold-secondary text-velvet-black font-black text-lg transition-all active:scale-95 disabled:opacity-30 flex items-center justify-center gap-3"
                            >
                                {pairLoading ? <div className="w-6 h-6 border-4 border-velvet-black/20 border-t-velvet-black rounded-full animate-spin" /> : <><span>CONNECT TV</span><Smartphone size={20} /></>}
                            </button>
                        </div>
                    </div>
                ) : (
                    /* Step 2: Configure */
                    <div className="p-0 animate-in fade-in slide-in-from-bottom-4 duration-500">
                        {/* Tab Selection */}
                        <div className="flex bg-black/20 p-2 gap-2 border-b border-white/5">
                            <TabButton active={activeTab === 'iptv'} onClick={() => setActiveTab('iptv')} icon={<Tv size={20} />} label="IPTV" />
                            <TabButton active={activeTab === 'debrid'} onClick={() => setActiveTab('debrid')} icon={<Cloud size={20} />} label="Debrid" />
                        </div>

                        <div className="p-8 md:p-10 space-y-8">
                            <AnimatePresence mode="wait">
                                {activeTab === 'iptv' ? (
                                    <motion.div key="iptv" initial={{ x: -20, opacity: 0 }} animate={{ x: 0, opacity: 1 }} exit={{ x: 20, opacity: 0 }} className="space-y-6">
                                        <InputField label="Portal URL" placeholder="https://example.tv:8080" value={iptv.serverUrl} onChange={(e: any) => setIptv({ ...iptv, serverUrl: e.target.value })} />
                                        <InputField label="Username" placeholder="Enter username" value={iptv.username} onChange={(e: any) => setIptv({ ...iptv, username: e.target.value })} />
                                        <InputField label="Password" type="password" placeholder="••••••••" value={iptv.password} onChange={(e: any) => setIptv({ ...iptv, password: e.target.value })} />
                                    </motion.div>
                                ) : (
                                    <motion.div key="debrid" initial={{ x: 20, opacity: 0 }} animate={{ x: 0, opacity: 1 }} exit={{ x: -20, opacity: 0 }} className="space-y-6">
                                        <InputField label="Real-Debrid API Token" placeholder="Paste token here" type="password" value={debrid.token} onChange={(e: any) => setDebrid({ ...debrid, token: e.target.value })} helperText={<a href="https://real-debrid.com/apitoken" target="_blank" className="underline">Get your token</a>} />
                                        <InputField label="MediaFusion URL" placeholder="stremio://..." value={debrid.mediaFusionUrl} onChange={(e: any) => setDebrid({ ...debrid, mediaFusionUrl: e.target.value })} />
                                    </motion.div>
                                )}
                            </AnimatePresence>

                            <button
                                onClick={handleSync}
                                disabled={status === 'loading'}
                                className="w-full py-5 rounded-2xl bg-gradient-to-r from-gold-primary to-gold-secondary text-velvet-black font-black text-lg transition-all active:scale-[0.98] flex items-center justify-center gap-3 group"
                            >
                                {status === 'loading' ? (
                                    <div className="w-6 h-6 border-4 border-velvet-black/20 border-t-velvet-black rounded-full animate-spin" />
                                ) : (
                                    <>
                                        <Save size={22} className="group-hover:rotate-12 transition-transform" />
                                        <span>SAVE TO TV</span>
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                )}

                {/* Status Toast Overlay */}
                <AnimatePresence>
                    {status !== 'idle' && status !== 'loading' && (
                        <motion.div initial={{ y: 50, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 50, opacity: 0 }} className={cn("absolute bottom-6 left-6 right-6 p-4 rounded-xl flex items-center gap-3 backdrop-blur-xl border z-50", status === 'success' ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20" : "bg-rose-500/10 text-rose-400 border-rose-500/20")}>
                            {status === 'success' ? <CheckCircle size={20} /> : <AlertCircle size={20} />}
                            <span className="text-xs font-bold">{message}</span>
                        </motion.div>
                    )}
                </AnimatePresence>
            </motion.main>

            <footer className="mt-8 text-center text-white/20 text-[10px] font-black uppercase tracking-[0.2em]">
                {isPaired ? <span className="text-emerald-400/50 flex items-center justify-center gap-1"><Smartphone size={10} /> Linked to Terminal: {remoteCode}</span> : "Waiting for Authorization"}
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
