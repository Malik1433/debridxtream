import React, { useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Server, Key, ArrowLeft, Save, Globe, User, Lock, Activity, CheckCircle2, AlertCircle } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { FirestoreService } from '../services/FirestoreService';

const ConfigPage: React.FC = () => {
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const code = (searchParams.get('code') || '').toUpperCase();
    const [activeTab, setActiveTab] = useState<'iptv' | 'debrid' | 'mediafusion'>('iptv');


    const [iptvConfig, setIptvConfig] = useState({
        url: '',
        username: '',
        password: ''
    });

    const [debridKey, setDebridKey] = useState('');
    const [mediaFusionUrl, setMediaFusionUrl] = useState('');
    const [isSaving, setIsSaving] = useState(false);
    const [saveStatus, setSaveStatus] = useState<'idle' | 'success' | 'error'>('idle');


    const handleSave = async () => {
        if (!code) return;
        setIsSaving(true);
        setSaveStatus('idle');

        // Create a timeout promise to prevent indefinite hanging
        const timeoutPromise = new Promise((_, reject) => {
            setTimeout(() => reject(new Error('TIMEOUT')), 15000);
        });

        try {
            // Race the Firestore push against our timeout
            await Promise.race([
                FirestoreService.pushConfig(code, {
                    iptv: iptvConfig,
                    debrid: debridKey,
                    mediafusion: mediaFusionUrl
                }),

                timeoutPromise
            ]);

            setSaveStatus('success');
            setTimeout(() => setSaveStatus('idle'), 5000);
        } catch (error: any) {
            console.error('Detailed push failure:', error);
            if (error.message === 'TIMEOUT') {
                alert('Connection timed out. This is usually caused by an invalid Firebase APP ID configuration.');
            } else {
                alert('Push failed: ' + (error.message || 'Unknown error'));
            }
            setSaveStatus('error');
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <div className="min-h-screen p-4 md:p-12 flex flex-col items-center relative overflow-hidden">
            {/* Ambient Background Glows */}
            <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] bg-gold-500/10 blur-[120px] rounded-full" />
            <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] bg-gold-500/5 blur-[120px] rounded-full" />

            <div className="max-w-4xl w-full relative z-10">
                {/* Header Navigation */}
                <div className="flex items-center justify-between mb-12">
                    <button
                        onClick={() => navigate('/')}
                        className="nav-btn group"
                    >
                        <ArrowLeft className="w-5 h-5 group-hover:-translate-x-1 transition-transform" />
                    </button>

                    <motion.div
                        initial={{ opacity: 0, y: -20 }}
                        animate={{ opacity: 1, y: 0 }}
                        className="flex items-center gap-3 px-6 py-2.5 rounded-full glass-gold"
                    >
                        <div className="w-2 h-2 rounded-full bg-gold-500 animate-pulse" />
                        <span className="text-[10px] font-black text-gold-500 uppercase tracking-[0.3em]">
                            Session: {code || 'N/A'}
                        </span>
                    </motion.div>
                </div>

                <motion.div
                    initial={{ opacity: 0, y: 20 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="glass-gold rounded-[2.5rem] overflow-hidden"
                >
                    {/* Primary Tabs */}
                    <div className="flex bg-white/2 border-b border-white/5">
                        <button
                            onClick={() => setActiveTab('iptv')}
                            className={`flex-1 flex items-center justify-center gap-3 py-8 font-black uppercase tracking-[0.2em] text-xs transition-all
                                ${activeTab === 'iptv' ? 'text-gold-500 bg-gold-500/5 shadow-[inset_0_-2px_0_rgba(212,175,55,1)]' : 'text-neutral-500 hover:text-neutral-300'}`}
                        >
                            <Server className="w-4 h-4" />
                            Provider Config
                        </button>
                        <button
                            onClick={() => setActiveTab('debrid')}
                            className={`flex-1 flex items-center justify-center gap-3 py-8 font-black uppercase tracking-[0.2em] text-xs transition-all
                                ${activeTab === 'debrid' ? 'text-gold-500 bg-gold-500/5 shadow-[inset_0_-2px_0_rgba(212,175,55,1)]' : 'text-neutral-500 hover:text-neutral-300'}`}
                        >
                            <Key className="w-4 h-4" />
                            Premium Debrid
                        </button>
                        <button
                            onClick={() => setActiveTab('mediafusion')}
                            className={`flex-1 flex items-center justify-center gap-3 py-8 font-black uppercase tracking-[0.2em] text-xs transition-all
                                ${activeTab === 'mediafusion' ? 'text-gold-500 bg-gold-500/5 shadow-[inset_0_-2px_0_rgba(212,175,55,1)]' : 'text-neutral-500 hover:text-neutral-300'}`}
                        >
                            <Activity className="w-4 h-4" />
                            MediaFusion
                        </button>

                    </div>

                    {/* Form Container */}
                    <div className="p-8 md:p-16">
                        <AnimatePresence mode="wait">
                            {activeTab === 'iptv' ? (
                                <motion.div
                                    key="iptv"
                                    initial={{ opacity: 0, x: -20 }}
                                    animate={{ opacity: 1, x: 0 }}
                                    exit={{ opacity: 0, x: 20 }}
                                    className="space-y-10"
                                >
                                    <div className="space-y-3">
                                        <label className="block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500 ml-2">
                                            Server URL
                                        </label>
                                        <div className="relative group">
                                            <Globe className="absolute left-6 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-600 group-focus-within:text-gold-500 transition-colors" />
                                            <input
                                                type="text"
                                                placeholder="http://line.spainott.site:8080"
                                                className="input-field pl-16"
                                                value={iptvConfig.url}
                                                onChange={(e) => setIptvConfig({ ...iptvConfig, url: e.target.value })}
                                            />
                                        </div>
                                    </div>

                                    <div className="grid md:grid-cols-2 gap-8">
                                        <div className="space-y-3">
                                            <label className="block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500 ml-2">
                                                Username
                                            </label>
                                            <div className="relative group">
                                                <User className="absolute left-6 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-600 group-focus-within:text-gold-500 transition-colors" />
                                                <input
                                                    type="text"
                                                    placeholder="Username"
                                                    className="input-field pl-16"
                                                    value={iptvConfig.username}
                                                    onChange={(e) => setIptvConfig({ ...iptvConfig, username: e.target.value })}
                                                />
                                            </div>
                                        </div>
                                        <div className="space-y-3">
                                            <label className="block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500 ml-2">
                                                Password
                                            </label>
                                            <div className="relative group">
                                                <Lock className="absolute left-6 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-600 group-focus-within:text-gold-500 transition-colors" />
                                                <input
                                                    type="password"
                                                    placeholder="••••••••"
                                                    className="input-field pl-16"
                                                    value={iptvConfig.password}
                                                    onChange={(e) => setIptvConfig({ ...iptvConfig, password: e.target.value })}
                                                />
                                            </div>
                                        </div>
                                    </div>
                                </motion.div>
                            ) : activeTab === 'debrid' ? (
                                <motion.div
                                    key="debrid"
                                    initial={{ opacity: 0, x: 20 }}
                                    animate={{ opacity: 1, x: 0 }}
                                    exit={{ opacity: 0, x: -20 }}
                                    className="space-y-10"
                                >
                                    <div className="space-y-4">
                                        <label className="block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500 ml-2">
                                            Real-Debrid API Context
                                        </label>
                                        <div className="relative group">
                                            <Key className="absolute left-6 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-600 group-focus-within:text-gold-500 transition-colors" />
                                            <input
                                                type="text"
                                                placeholder="Paste your private API key..."
                                                className="input-field pl-16 font-mono text-sm tracking-widest"
                                                value={debridKey}
                                                onChange={(e) => setDebridKey(e.target.value)}
                                            />
                                        </div>
                                        <p className="text-[10px] text-neutral-600 italic px-2 leading-relaxed">
                                            You can find this in your Real-Debrid dashboard under the "API" section. This allows high-speed 4K streaming without buffering.
                                        </p>
                                    </div>
                                </motion.div>
                            ) : (
                                <motion.div
                                    key="mediafusion"
                                    initial={{ opacity: 0, x: 20 }}
                                    animate={{ opacity: 1, x: 0 }}
                                    exit={{ opacity: 0, x: -20 }}
                                    className="space-y-10"
                                >
                                    <div className="space-y-6">
                                        <div className="flex flex-col gap-4 p-6 rounded-2xl bg-gold-500/5 border border-gold-500/10">
                                            <p className="text-xs text-neutral-400 leading-relaxed font-bold">
                                                To set up MediaFusion, first configure your manifest on their portal, then copy the result link below.
                                            </p>
                                            <a
                                                href="https://mediafusion.elfhosted.com/configure"
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className="flex items-center justify-center gap-2 py-3 px-6 rounded-xl bg-gold-500/10 hover:bg-gold-500/20 text-gold-500 text-[10px] font-black uppercase tracking-[0.2em] transition-all border border-gold-500/20"
                                            >
                                                <Globe className="w-4 h-4" />
                                                Open MediaFusion Config Portal
                                            </a>
                                        </div>

                                        <div className="space-y-3">
                                            <label className="block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500 ml-2">
                                                MediaFusion Manifest URL
                                            </label>
                                            <div className="relative group">
                                                <Activity className="absolute left-6 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-600 group-focus-within:text-gold-500 transition-colors" />
                                                <input
                                                    type="text"
                                                    placeholder="https://mediafusion.elfhosted.com/..."
                                                    className="input-field pl-16"
                                                    value={mediaFusionUrl}
                                                    onChange={(e) => setMediaFusionUrl(e.target.value)}
                                                />
                                            </div>
                                            <p className="text-[10px] text-neutral-600 italic px-2 leading-relaxed">
                                                Example: https://mediafusion.elfhosted.com/RealDebrid=XYZ/manifest.json
                                            </p>
                                        </div>
                                    </div>
                                </motion.div>
                            )}

                        </AnimatePresence>

                        {/* Action Bar */}
                        <div className="mt-16 pt-10 border-t border-white/5 flex flex-col items-center">
                            <AnimatePresence mode="wait">
                                {saveStatus === 'success' ? (
                                    <motion.div
                                        initial={{ opacity: 0, scale: 0.9 }}
                                        animate={{ opacity: 1, scale: 1 }}
                                        className="mb-6 flex items-center gap-3 text-gold-500"
                                    >
                                        <CheckCircle2 className="w-5 h-5" />
                                        <span className="font-bold text-sm tracking-wide">Sync Successful! Check your TV.</span>
                                    </motion.div>
                                ) : saveStatus === 'error' ? (
                                    <motion.div
                                        initial={{ opacity: 0, scale: 0.9 }}
                                        animate={{ opacity: 1, scale: 1 }}
                                        className="mb-6 flex items-center gap-3 text-rose-500"
                                    >
                                        <AlertCircle className="w-5 h-5" />
                                        <span className="font-bold text-sm tracking-wide">Failed to push. Verify link code.</span>
                                    </motion.div>
                                ) : null}
                            </AnimatePresence>

                            <button
                                onClick={handleSave}
                                disabled={isSaving || !code}
                                className={`w-full btn-gold group ${isSaving ? 'opacity-50 cursor-not-allowed' : ''}`}
                            >
                                <span className="flex items-center justify-center gap-4">
                                    {isSaving ? (
                                        <>
                                            <Activity className="w-5 h-5 animate-spin" />
                                            Encrypting & Pushing...
                                        </>
                                    ) : (
                                        <>
                                            <Save className="w-5 h-5 group-hover:rotate-12 transition-transform" />
                                            Synchronize with TV
                                        </>
                                    )}
                                </span>
                            </button>

                            <p className="mt-6 text-[9px] text-neutral-700 font-bold uppercase tracking-[0.4em]">
                                Secured by DebridXtream 128-bit Encryption
                            </p>
                        </div>
                    </div>
                </motion.div>

                {/* Footer Signature */}
                <div className="mt-12 text-center">
                    <p className="text-neutral-600 text-[10px] font-black uppercase tracking-[0.5em]">
                        Powered by DebridXtream Elite
                    </p>
                </div>
            </div>
        </div>
    );
};

export default ConfigPage;
