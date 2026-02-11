import React, { useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Loader2, ShieldCheck, Zap } from 'lucide-react';

const MagicLinkPage: React.FC = () => {
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const code = searchParams.get('code');

    useEffect(() => {
        if (code && code.length >= 4) {
            // Tiny delay for visual feedback, then redirect to config
            const timer = setTimeout(() => {
                navigate(`/config?code=${code.toUpperCase()}`);
            }, 1800);
            return () => clearTimeout(timer);
        } else {
            // No code or invalid code, go to home
            const timer = setTimeout(() => {
                navigate('/');
            }, 2500);
            return () => clearTimeout(timer);
        }
    }, [code, navigate]);

    return (
        <div className="min-h-screen flex flex-col items-center justify-center p-6 relative overflow-hidden">
            {/* Pulsing Aura */}
            <motion.div
                animate={{
                    scale: [1, 1.1, 1],
                    opacity: [0.1, 0.2, 0.1]
                }}
                transition={{ duration: 4, repeat: Infinity }}
                className="absolute w-[150%] h-[150%] bg-gold-500 rounded-full blur-[200px]"
            />

            <motion.div
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                className="max-w-md w-full glass-gold rounded-[3rem] p-16 flex flex-col items-center text-center relative z-10"
            >
                <div className="relative mb-12">
                    <motion.div
                        animate={{ rotate: 360 }}
                        transition={{ duration: 3, repeat: Infinity, ease: "linear" }}
                        className="p-1 rounded-full border-2 border-dashed border-gold-500/30"
                    >
                        <div className="p-6 rounded-full bg-gold-500/10 backdrop-blur-xl">
                            <Zap className="w-10 h-10 text-gold-500 fill-gold-500/20" />
                        </div>
                    </motion.div>

                    <div className="absolute -top-2 -right-2">
                        <motion.div
                            initial={{ scale: 0 }}
                            animate={{ scale: 1 }}
                            transition={{ delay: 0.5, type: "spring" }}
                            className="p-2 rounded-full bg-gold-500 shadow-lg shadow-gold-500/20"
                        >
                            <ShieldCheck className="w-4 h-4 text-black" />
                        </motion.div>
                    </div>
                </div>

                <h1 className="text-3xl font-black text-white mb-4 tracking-tight">Authenticating</h1>
                <div className="flex items-center gap-3 px-6 py-2 rounded-full bg-white/5 border border-white/5 mb-6">
                    <Loader2 className="w-3 h-3 text-gold-500 animate-spin" />
                    <span className="text-[10px] font-black text-gold-500 uppercase tracking-[0.3em]">
                        {code ? `Link Code: ${code.toUpperCase()}` : "Ready for connection"}
                    </span>
                </div>

                <p className="text-neutral-500 text-xs leading-relaxed max-w-[200px]">
                    Establishing a secure end-to-end encrypted bridge to your TV.
                </p>
            </motion.div>

            <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ delay: 1 }}
                className="mt-12 flex flex-col items-center gap-2 relative z-10"
            >
                <span className="text-neutral-700 text-[9px] font-black uppercase tracking-[0.5em]">
                    DebridXtream Cloud Bridge
                </span>
                <div className="w-24 h-[1px] bg-gradient-to-r from-transparent via-neutral-800 to-transparent" />
            </motion.div>
        </div>
    );
};

export default MagicLinkPage;
