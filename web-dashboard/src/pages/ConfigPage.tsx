import React, { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
    ArrowLeft,
    AlertCircle,
    CheckCircle2,
    Globe,
    Link2,
    Loader2,
    Lock,
    Plus,
    Save,
    Server,
    Trash2,
    User,
    ShieldCheck
} from 'lucide-react';
import { AnimatePresence, motion } from 'framer-motion';
import { FirestoreService } from '../services/FirestoreService';

type AddonRow = {
    id: string;
    value: string;
};

type FieldErrors = {
    general?: string;
    iptv?: string;
    addons?: string;
    addonRows?: Record<string, string>;
};

type SaveState = 'idle' | 'verifying' | 'saving' | 'success' | 'error';

const createRowId = () =>
    (globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`);

const normalizeAddonUrl = (value: string): string => {
    const trimmed = value.trim();
    if (!trimmed) return '';
    return trimmed
        .replace(/^stremio:\/\//i, 'https://')
        .replace(/\/+$/, '');
};

const isManifestUrl = (value: string): boolean => {
    const lower = value.toLowerCase();
    return (lower.startsWith('https://') || lower.startsWith('http://')) && lower.includes('manifest.json');
};

const normalizeServerUrl = (value: string): string => {
    const trimmed = value.trim().replace(/\/+$/, '');
    return trimmed;
};

const hasAnyIptvValue = (serverUrl: string, username: string, password: string): boolean => {
    return [serverUrl, username, password].some((value) => value.trim().length > 0);
};

const hasCompleteIptvValue = (serverUrl: string, username: string, password: string): boolean => {
    return [serverUrl, username, password].every((value) => value.trim().length > 0);
};

const ConfigPage: React.FC = () => {
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const code = (searchParams.get('code') || '').toUpperCase();

    const [iptvConfig, setIptvConfig] = useState({
        url: '',
        username: '',
        password: ''
    });
    const [addonRows, setAddonRows] = useState<AddonRow[]>([{ id: createRowId(), value: '' }]);
    const [saveState, setSaveState] = useState<SaveState>('idle');
    const [statusMessage, setStatusMessage] = useState('');
    const [errors, setErrors] = useState<FieldErrors>({});

    const activeAddonUrls = useMemo(() => {
        const seen = new Set<string>();
        const urls: string[] = [];

        addonRows.forEach((row) => {
            const normalized = normalizeAddonUrl(row.value);
            if (!normalized || !isManifestUrl(normalized)) return;
            if (seen.has(normalized)) return;
            seen.add(normalized);
            urls.push(normalized);
        });

        return urls;
    }, [addonRows]);

    const updateAddonRow = (id: string, value: string) => {
        setAddonRows((rows) => rows.map((row) => (row.id === id ? { ...row, value } : row)));
        setErrors((current) => {
            if (!current.addonRows?.[id]) return current;
            const nextAddonRows = { ...(current.addonRows ?? {}) };
            delete nextAddonRows[id];
            return { ...current, addonRows: nextAddonRows };
        });
    };

    const addAddonRow = () => {
        setAddonRows((rows) => [...rows, { id: createRowId(), value: '' }]);
    };

    const removeAddonRow = (id: string) => {
        setAddonRows((rows) => {
            if (rows.length === 1) {
                return [{ id: createRowId(), value: '' }];
            }
            return rows.filter((row) => row.id !== id);
        });
        setErrors((current) => {
            if (!current.addonRows?.[id]) return current;
            const nextAddonRows = { ...(current.addonRows ?? {}) };
            delete nextAddonRows[id];
            return { ...current, addonRows: nextAddonRows };
        });
    };

    const verifyIptvCredentials = async (serverUrl: string, username: string, password: string) => {
        const payload = {
            serverUrl: normalizeServerUrl(serverUrl),
            username: username.trim(),
            password: password.trim()
        };

        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 12000);

        try {
            const response = await fetch('/api/verify-iptv', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(payload),
                signal: controller.signal
            });

            if (response.ok) {
                const data = await response.json().catch(() => ({}));
                return {
                    ok: Boolean(data.ok),
                    message: typeof data.message === 'string' && data.message.trim()
                        ? data.message.trim()
                        : (Boolean(data.ok) ? 'IPTV verified.' : 'IPTV verification failed.')
                };
            }

            const fallbackMessage = await response.text().catch(() => '');
            return {
                ok: false,
                message: fallbackMessage.trim() || `Verification service returned ${response.status}.`
            };
        } catch (error: any) {
            return {
                ok: false,
                message: error?.name === 'AbortError'
                    ? 'IPTV verification timed out.'
                    : 'Unable to verify IPTV from the companion service.'
            };
        } finally {
            window.clearTimeout(timeout);
        }
    };

    const handleSave = async () => {
        if (!code) return;

        const nextErrors: FieldErrors = {};
        const normalizedServerUrl = normalizeServerUrl(iptvConfig.url);
        const normalizedUsername = iptvConfig.username.trim();
        const normalizedPassword = iptvConfig.password.trim();
        const iptvFilled = hasAnyIptvValue(normalizedServerUrl, normalizedUsername, normalizedPassword);
        const iptvComplete = hasCompleteIptvValue(normalizedServerUrl, normalizedUsername, normalizedPassword);

        const addonRowErrors: Record<string, string> = {};
        const seen = new Set<string>();
        const addonUrls: string[] = [];

        addonRows.forEach((row) => {
            const normalized = normalizeAddonUrl(row.value);
            if (!normalized) return;
            if (!isManifestUrl(normalized)) {
                addonRowErrors[row.id] = 'Use a full manifest.json URL.';
                return;
            }
            if (seen.has(normalized)) return;
            seen.add(normalized);
            addonUrls.push(normalized);
        });

        if (Object.keys(addonRowErrors).length > 0) {
            nextErrors.addonRows = addonRowErrors;
        }

        if (iptvFilled && !iptvComplete) {
            nextErrors.iptv = 'Fill server URL, username, and password together.';
        } else if (iptvComplete && !/^https?:\/\//i.test(normalizedServerUrl)) {
            nextErrors.iptv = 'IPTV server URL must start with http:// or https://.';
        }

        if (!iptvFilled && addonUrls.length === 0) {
            nextErrors.general = 'Add IPTV details or at least one Stremio manifest URL.';
        }

        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) {
            setSaveState('error');
            setStatusMessage(nextErrors.general || nextErrors.iptv || 'Please fix the highlighted fields.');
            return;
        }

        setSaveState('verifying');
        setStatusMessage('Verifying IPTV credentials...');

        if (iptvComplete) {
            const verification = await verifyIptvCredentials(normalizedServerUrl, normalizedUsername, normalizedPassword);
            if (!verification.ok) {
                setErrors((current) => ({
                    ...current,
                    iptv: verification.message
                }));
                setSaveState('error');
                setStatusMessage(verification.message);
                return;
            }
        }

        setSaveState('saving');
        setStatusMessage('Synchronizing with TV...');

        const timeoutPromise = new Promise((_, reject) => {
            setTimeout(() => reject(new Error('TIMEOUT')), 15000);
        });

        try {
            await Promise.race([
                FirestoreService.pushConfig(code, {
                    schemaVersion: 2,
                    iptv: iptvComplete ? {
                        url: normalizedServerUrl,
                        username: normalizedUsername,
                        password: normalizedPassword
                    } : undefined,
                    debridConfig: {
                        stremioAddonUrls: addonUrls
                    },
                    stremioAddonUrls: addonUrls
                }),
                timeoutPromise
            ]);

            setSaveState('success');
            setStatusMessage('Sync successful. Check your TV.');
            window.setTimeout(() => {
                setSaveState('idle');
                setStatusMessage('');
            }, 5000);
        } catch (error: any) {
            const message = error?.message === 'TIMEOUT'
                ? 'Connection timed out. Please check the companion service.'
                : `Push failed: ${error?.message || 'Unknown error'}`;
            setSaveState('error');
            setStatusMessage(message);
            setErrors((current) => ({ ...current, general: message }));
        }
    };

    return (
        <div className="min-h-screen bg-neutral-950 p-4 sm:p-6 lg:p-8 text-white">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-5">
                <div className="flex items-center justify-between gap-4">
                    <button
                        onClick={() => navigate('/')}
                        className="nav-btn group shrink-0"
                    >
                        <ArrowLeft className="h-5 w-5 transition-transform group-hover:-translate-x-1" />
                    </button>

                    <div className="flex flex-1 justify-end">
                        <div className="flex items-center gap-3 rounded-full border border-white/10 bg-white/5 px-4 py-2 backdrop-blur">
                            <div className="h-2 w-2 rounded-full bg-gold-500 animate-pulse" />
                            <span className="text-[10px] font-black uppercase tracking-[0.28em] text-gold-500">
                                Session: {code || 'N/A'}
                            </span>
                        </div>
                    </div>
                </div>

                <div className="rounded-[2rem] border border-white/5 bg-white/[0.03] p-5 sm:p-6">
                    <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                        <div>
                            <p className="text-[10px] font-black uppercase tracking-[0.35em] text-neutral-500">
                                Companion Setup
                            </p>
                            <h1 className="mt-2 text-2xl font-black tracking-tight sm:text-3xl">
                                IPTV and Stremio links in one mobile form
                            </h1>
                        </div>
                        <div className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-2 text-[10px] font-black uppercase tracking-[0.25em] text-neutral-300">
                            <ShieldCheck className="h-4 w-4 text-gold-500" />
                            Schema v2
                        </div>
                    </div>
                    <p className="mt-3 max-w-2xl text-sm leading-relaxed text-neutral-400">
                        Fill IPTV details only if you need them. Add one or more Stremio manifest URLs below.
                        Save will verify IPTV first and block wrong details before anything is sent.
                    </p>
                </div>

                <div className="grid gap-4 lg:grid-cols-[1fr_1.12fr]">
                    <section className="rounded-[1.75rem] border border-white/5 bg-white/[0.03] p-5 sm:p-6">
                        <div className="flex items-center gap-3">
                            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gold-500/10 text-gold-500">
                                <Server className="h-5 w-5" />
                            </div>
                            <div>
                                <h2 className="text-lg font-bold">IPTV Provider</h2>
                                <p className="text-xs text-neutral-500">Verified before sync. Wrong info stays here.</p>
                            </div>
                        </div>

                        <div className="mt-5 space-y-4">
                            <div className="space-y-2">
                                <label className="ml-1 block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500">
                                    Server URL
                                </label>
                                <div className="relative">
                                    <Globe className="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-600" />
                                    <input
                                        type="text"
                                        inputMode="url"
                                        placeholder="http://line.example.com:8080"
                                        className={`input-field w-full pl-12 ${errors.iptv ? 'border-rose-500/50' : ''}`}
                                        value={iptvConfig.url}
                                        onChange={(e) => {
                                            setIptvConfig({ ...iptvConfig, url: e.target.value });
                                            if (errors.iptv) {
                                                setErrors((current) => ({ ...current, iptv: undefined }));
                                            }
                                        }}
                                    />
                                </div>
                            </div>

                            <div className="grid gap-4 sm:grid-cols-2">
                                <div className="space-y-2">
                                    <label className="ml-1 block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500">
                                        Username
                                    </label>
                                    <div className="relative">
                                        <User className="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-600" />
                                        <input
                                            type="text"
                                            placeholder="Username"
                                            className={`input-field w-full pl-12 ${errors.iptv ? 'border-rose-500/50' : ''}`}
                                            value={iptvConfig.username}
                                            onChange={(e) => {
                                                setIptvConfig({ ...iptvConfig, username: e.target.value });
                                                if (errors.iptv) {
                                                    setErrors((current) => ({ ...current, iptv: undefined }));
                                                }
                                            }}
                                        />
                                    </div>
                                </div>

                                <div className="space-y-2">
                                    <label className="ml-1 block text-[10px] font-black uppercase tracking-[0.3em] text-neutral-500">
                                        Password
                                    </label>
                                    <div className="relative">
                                        <Lock className="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-600" />
                                        <input
                                            type="password"
                                            placeholder="••••••••"
                                            className={`input-field w-full pl-12 ${errors.iptv ? 'border-rose-500/50' : ''}`}
                                            value={iptvConfig.password}
                                            onChange={(e) => {
                                                setIptvConfig({ ...iptvConfig, password: e.target.value });
                                                if (errors.iptv) {
                                                    setErrors((current) => ({ ...current, iptv: undefined }));
                                                }
                                            }}
                                        />
                                    </div>
                                </div>
                            </div>

                            <p className="text-[10px] leading-relaxed text-neutral-500">
                                If you only want Stremio addons, leave IPTV blank and it will not be sent.
                            </p>

                            <AnimatePresence>
                                {errors.iptv && (
                                    <motion.div
                                        initial={{ opacity: 0, y: -4 }}
                                        animate={{ opacity: 1, y: 0 }}
                                        exit={{ opacity: 0, y: -4 }}
                                        className="flex items-start gap-2 rounded-xl border border-rose-500/20 bg-rose-500/10 px-3 py-2 text-sm text-rose-300"
                                    >
                                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                                        <span>{errors.iptv}</span>
                                    </motion.div>
                                )}
                            </AnimatePresence>
                        </div>
                    </section>

                    <section className="rounded-[1.75rem] border border-white/5 bg-white/[0.03] p-5 sm:p-6">
                        <div className="flex items-center justify-between gap-3">
                            <div className="flex items-center gap-3">
                                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gold-500/10 text-gold-500">
                                    <Link2 className="h-5 w-5" />
                                </div>
                                <div>
                                    <h2 className="text-lg font-bold">Stremio Addons</h2>
                                    <p className="text-xs text-neutral-500">Add as many manifest URLs as you need.</p>
                                </div>
                            </div>

                            <button
                                type="button"
                                onClick={addAddonRow}
                                className="inline-flex items-center gap-2 rounded-full border border-gold-500/20 bg-gold-500/10 px-3 py-2 text-[10px] font-black uppercase tracking-[0.22em] text-gold-500 transition-colors hover:bg-gold-500/15"
                            >
                                <Plus className="h-3.5 w-3.5" />
                                Add Link
                            </button>
                        </div>

                        <div className="mt-5 space-y-3">
                            {addonRows.map((row, index) => {
                                const rowError = errors.addonRows?.[row.id];
                                return (
                                    <div key={row.id} className="space-y-2 rounded-2xl border border-white/5 bg-black/20 p-3 sm:p-4">
                                        <div className="flex items-center justify-between gap-3">
                                            <label className="text-[10px] font-black uppercase tracking-[0.28em] text-neutral-500">
                                                Link {index + 1}
                                            </label>
                                            <button
                                                type="button"
                                                onClick={() => removeAddonRow(row.id)}
                                                className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-2.5 py-1.5 text-[10px] font-black uppercase tracking-[0.22em] text-neutral-300 transition-colors hover:border-rose-500/40 hover:text-rose-300"
                                            >
                                                <Trash2 className="h-3.5 w-3.5" />
                                                Remove
                                            </button>
                                        </div>

                                        <div className="relative">
                                            <Link2 className="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-600" />
                                            <input
                                                type="text"
                                                inputMode="url"
                                                placeholder="https://addon.example/config/manifest.json"
                                                className={`input-field w-full pl-12 ${rowError ? 'border-rose-500/50' : ''}`}
                                                value={row.value}
                                                onChange={(e) => {
                                                    updateAddonRow(row.id, e.target.value);
                                                }}
                                            />
                                        </div>

                                        <AnimatePresence>
                                            {rowError && (
                                                <motion.div
                                                    initial={{ opacity: 0, y: -4 }}
                                                    animate={{ opacity: 1, y: 0 }}
                                                    exit={{ opacity: 0, y: -4 }}
                                                    className="flex items-start gap-2 rounded-xl border border-rose-500/20 bg-rose-500/10 px-3 py-2 text-sm text-rose-300"
                                                >
                                                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                                                    <span>{rowError}</span>
                                                </motion.div>
                                            )}
                                        </AnimatePresence>
                                    </div>
                                );
                            })}
                        </div>

                        <p className="mt-4 text-[10px] leading-relaxed text-neutral-500">
                            Paste one full `manifest.json` URL per row. `stremio://` links are normalized automatically.
                            Duplicate links are ignored.
                        </p>

                        <AnimatePresence>
                            {errors.general && (
                                <motion.div
                                    initial={{ opacity: 0, y: -4 }}
                                    animate={{ opacity: 1, y: 0 }}
                                    exit={{ opacity: 0, y: -4 }}
                                    className="mt-4 flex items-start gap-2 rounded-xl border border-rose-500/20 bg-rose-500/10 px-3 py-2 text-sm text-rose-300"
                                >
                                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                                    <span>{errors.general}</span>
                                </motion.div>
                            )}
                        </AnimatePresence>
                    </section>
                </div>

                <div className="rounded-[1.75rem] border border-white/5 bg-white/[0.03] p-4 sm:p-5">
                    <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                        <div className="flex flex-wrap items-center gap-2">
                            <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-[10px] font-black uppercase tracking-[0.22em] text-neutral-300">
                                <Server className="h-3.5 w-3.5 text-gold-500" />
                                {activeAddonUrls.length} addon(s)
                            </span>
                            <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-[10px] font-black uppercase tracking-[0.22em] text-neutral-300">
                                <ShieldCheck className="h-3.5 w-3.5 text-gold-500" />
                                Verify before save
                            </span>
                        </div>

                        <div className="flex flex-col gap-3 sm:items-end">
                            <AnimatePresence mode="wait">
                                {saveState !== 'idle' && (
                                    <motion.div
                                        key={saveState}
                                        initial={{ opacity: 0, y: 4 }}
                                        animate={{ opacity: 1, y: 0 }}
                                        exit={{ opacity: 0, y: 4 }}
                                        className={`flex items-center gap-2 text-sm ${
                                            saveState === 'success'
                                                ? 'text-emerald-400'
                                                : saveState === 'error'
                                                    ? 'text-rose-400'
                                                    : 'text-gold-500'
                                        }`}
                                    >
                                        {saveState === 'verifying' || saveState === 'saving' ? (
                                            <Loader2 className="h-4 w-4 animate-spin" />
                                        ) : saveState === 'success' ? (
                                            <CheckCircle2 className="h-4 w-4" />
                                        ) : (
                                            <AlertCircle className="h-4 w-4" />
                                        )}
                                        <span className="font-medium">{statusMessage}</span>
                                    </motion.div>
                                )}
                            </AnimatePresence>

                            <button
                                onClick={handleSave}
                                disabled={saveState === 'verifying' || saveState === 'saving' || !code}
                                className={`btn-gold inline-flex w-full justify-center gap-3 px-6 py-4 text-sm font-black uppercase tracking-[0.18em] sm:w-auto sm:min-w-[220px] ${
                                    saveState === 'verifying' || saveState === 'saving' ? 'opacity-60 cursor-not-allowed' : ''
                                }`}
                            >
                                {saveState === 'verifying' || saveState === 'saving' ? (
                                    <>
                                        <Loader2 className="h-5 w-5 animate-spin" />
                                        {saveState === 'verifying' ? 'Verifying IPTV' : 'Synchronizing'}
                                    </>
                                ) : (
                                    <>
                                        <Save className="h-5 w-5" />
                                        Synchronize with TV
                                    </>
                                )}
                            </button>
                        </div>
                    </div>

                    <p className="mt-4 text-[9px] font-black uppercase tracking-[0.38em] text-neutral-600">
                        Schema v2 • Stremio-first • mobile friendly
                    </p>
                </div>
            </div>
        </div>
    );
};

export default ConfigPage;
