const { v4: uuidv4 } = require('uuid');

// In-memory store (volatile on Vercel)
const memoryStore = new Map();

module.exports = (req, res) => {
    // Robust CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

    if (req.method === 'OPTIONS') {
        return res.status(200).end();
    }

    const { url, method } = req;
    console.log(`[Relay] ${method} ${url}`);

    // 1. POLL Endpoint (TV calls this)
    if (url.includes('/api/pair/poll') || url.includes('/api/pair/status')) {
        const urlParams = new URLSearchParams(url.split('?')[1]);
        const code = urlParams.get('code') || urlParams.get('deviceId');

        if (!code) return res.status(400).json({ error: 'Missing code' });

        const config = memoryStore.get(code);

        if (config) {
            console.log(`[Relay] Found config for ${code}`);
            memoryStore.delete(code); // One-time use
            return res.status(200).json(config);
        } else {
            return res.status(200).json({ status: 'waiting' });
        }
    }

    // 2. CONFIG Endpoint (Web Dashboard calls this)
    if (url.includes('/api/pair/config') && method === 'POST') {
        const body = req.body || {};
        const { code, config } = body;

        console.log(`[Relay] Received config for code: ${code}`);

        if (!code || !config) {
            return res.status(400).json({ error: 'Missing code or config', received: Object.keys(body) });
        }

        memoryStore.set(code, config);

        // TTL: Auto-clear after 10 minutes to prevent memory leaks
        setTimeout(() => {
            if (memoryStore.has(code)) memoryStore.delete(code);
        }, 600000);

        return res.status(200).json({ success: true, message: 'Config relayed' });
    }

    res.status(404).json({ error: 'Not found', url });
};

