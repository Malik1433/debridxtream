import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Zap, ArrowRight, Shield, Globe } from 'lucide-react';
import { motion } from 'framer-motion';
import { FirestoreService } from '../services/FirestoreService';

const LandingPage: React.FC = () => {
    const [code, setCode] = useState('');
    const navigate = useNavigate();

    const [isVerifying, setIsVerifying] = useState(false);
    const [error, setError] = useState('');

    const handleConnect = async (e: React.FormEvent) => {
        e.preventDefault();
        const cleanCode = code.trim().toUpperCase();
        if (cleanCode.length < 4) return;

        setIsVerifying(true);
        setError('');

        try {
            const isValid = await FirestoreService.verifyCode(cleanCode);
            if (isValid) {
                navigate(`/config?code=${cleanCode}`);
            } else {
                setError('Invalid or expired device code. Please check your TV.');
            }
        } catch (err) {
            setError('Connection error. Please try again.');
        } finally {
            setIsVerifying(false);
        }
    };

    return (
        <div className="min-h-screen flex flex-col items-center justify-center p-6 relative overflow-hidden">
            {/* Background Atmosphere */}
            <motion.div
                animate={{
                    scale: [1, 1.2, 1],
                    opacity: [0.3, 0.5, 0.3]
                }}
                transition={{ duration: 10, repeat: Infinity }}
                className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[80%] h-[80%] bg-gold-500/5 blur-[160px] rounded-full"
            />

            <div className="max-w-xl w-full relative z-10 flex flex-col items-center">
                {/* Branding Section */}
                <motion.div
                    initial={{ opacity: 0, y: -30 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="flex flex-col items-center mb-16 text-center"
                >
                    <div className="w-24 h-24 mb-6 relative">
                        <div className="absolute inset-0 bg-gold-500 blur-2xl opacity-20 animate-pulse" />
                        <div className="relative w-full h-full glass-gold rounded-3xl flex items-center justify-center">
                            <Zap className="w-10 h-10 text-gold-500 fill-gold-500/20" />
                        </div>
                    </div>
                    <h1 className="text-4xl md:text-6xl font-black text-white mb-4 tracking-tighter">
                        Elite <span className="text-gold-500">Companion</span>
                    </h1>
                    <p className="text-neutral-500 text-sm md:text-base font-medium max-w-[280px]">
                        The most sophisticated way to synchronize your entertainment.
                    </p>
                </motion.div>

                {/* Connection Box */}
                <motion.div
                    initial={{ opacity: 0, scale: 0.95 }}
                    animate={{ opacity: 1, scale: 1 }}
                    transition={{ delay: 0.2 }}
                    className="w-full glass-gold rounded-[2.5rem] p-10 md:p-12 mb-12 shadow-2xl"
                >
                    <form onSubmit={handleConnect} className="space-y-8">
                        <div className="space-y-4">
                            <label className="block text-[10px] font-black uppercase tracking-[0.5em] text-gold-500/60 ml-2 text-center px-4">
                                Enter Device Code
                            </label>
                            <input
                                type="text"
                                maxLength={8}
                                placeholder="X - X - X - X"
                                className={`w-full bg-white/5 border-2 rounded-2xl px-6 py-5 text-3xl font-black text-center text-white focus:outline-none focus:bg-white/10 transition-all uppercase placeholder:text-neutral-800 tracking-[0.2em] ${error ? 'border-rose-500/50' : 'border-white/10 focus:border-gold-500/50'}`}
                                value={code}
                                onChange={(e) => {
                                    setCode(e.target.value);
                                    if (error) setError('');
                                }}
                            />
                            {error && (
                                <p className="text-rose-500 text-[10px] font-bold text-center uppercase tracking-wider animate-pulse">
                                    {error}
                                </p>
                            )}
                        </div>

                        <button
                            type="submit"
                            disabled={code.length < 4 || isVerifying}
                            className="w-full btn-gold flex items-center justify-center gap-3 group px-8"
                        >
                            {isVerifying ? (
                                <>
                                    <Zap className="w-5 h-5 animate-spin" />
                                    Authenticating...
                                </>
                            ) : (
                                <>
                                    Establish Link
                                    <ArrowRight className="w-5 h-5 group-hover:translate-x-1 transition-transform" />
                                </>
                            )}
                        </button>
                    </form>
                </motion.div>

                {/* Perks Matrix */}
                <div className="grid grid-cols-3 gap-8 w-full">
                    {[
                        { icon: Shield, label: "Encrypted", sub: "Cloud Link" },
                        { icon: Globe, label: "Universal", sub: "Any Device" },
                        { icon: Zap, label: "Instant", sub: "Real-time" }
                    ].map((item, i) => (
                        <motion.div
                            key={i}
                            initial={{ opacity: 0 }}
                            animate={{ opacity: 1 }}
                            transition={{ delay: 0.4 + (i * 0.1) }}
                            className="flex flex-col items-center text-center group"
                        >
                            <div className="p-3 rounded-xl bg-white/2 border border-white/5 mb-3 group-hover:border-gold-500/30 transition-colors">
                                <item.icon className="w-4 h-4 text-neutral-500 group-hover:text-gold-500 transition-colors" />
                            </div>
                            <span className="text-[10px] font-black text-white uppercase tracking-widest">{item.label}</span>
                            <span className="text-[9px] text-neutral-600 font-bold uppercase">{item.sub}</span>
                        </motion.div>
                    ))}
                </div>

                <motion.p
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    transition={{ delay: 0.8 }}
                    className="mt-16 text-[10px] font-black uppercase tracking-[0.4em] text-neutral-700"
                >
                    Version 2.0 • Secured by Firestore Delta
                </motion.p>
            </div>
        </div>
    );
};

export default LandingPage;
