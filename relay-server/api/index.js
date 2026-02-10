const { v4: uuidv4 } = require('uuid');

// In-memory store for demo purposes (Vercel serverless functions are stateless, 
// so this will only work if the instance stays warm or for testing).
// FOR PRODUCTION: Use Redis (Upstash) or Firebase Realtime DB.
const memoryStore = new Map();

module.exports = (req, res) => {
    // CORS
    res.setHeader('Access-Control-Allow-Credentials', true);
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS,PATCH,DELETE,POST,PUT');
    res.setHeader(
        'Access-Control-Allow-Headers',
        'X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version'
    );

    if (req.method === 'OPTIONS') {
        res.status(200).end();
        return;
    }

    const { path } = req.query; // vercel rewrites

    // 1. POLL Endpoint (TV calls this)
    // GET /api/pair/status?deviceId=XYZ
    if (req.url.includes('/api/pair/status')) {
        const urlParams = new URLSearchParams(req.url.split('?')[1]);
        const deviceId = urlParams.get('deviceId');

        if (!deviceId) return res.status(400).json({ error: 'Missing deviceId' });

        // Retrieve config for this device
        // In a real DB, you'd look up by code or deviceId mapping
        const config = memoryStore.get(deviceId);

        if (config) {
            memoryStore.delete(deviceId); // One-time use
            return res.status(200).json(config);
        } else {
            return res.status(200).json({ status: 'waiting' });
        }
    }

    // 2. PUSH Endpoint (Web Dashboard calls this)
    // POST /api/pair/config
    if (req.url.includes('/api/pair/config') && req.method === 'POST') {
        const { code, config } = req.body;

        if (!code || !config) return res.status(400).json({ error: 'Missing code or config' });

        // In a real system, 'code' would map to a 'deviceId' waiting for it.
        // For this simple demo without a DB, we'll cheat slightly:
        // The TV is polling with a generated code. We need to store it so the TV finds it.
        // TV Logic: generatePairingCode() -> derived from deviceID.
        // We need to reverse map or just store by 'code' if the TV polled by code 
        // (but TV polls by deviceId usually to be secure).

        // SIMPLIFIED ARCHITECTURE FOR DEMO:
        // TV Polls: /api/pair/status?code=123456 (Modified TV logic required?)
        // OR
        // Web Pushes to a "mailbox" labeled by the CODE.

        // Let's assume the TV logic I wrote polls with `deviceId`. 
        // AND the TV sent the `code` -> `deviceId` mapping to the server?
        // NO, the TV just polls. 

        // CRITICAL FIX: The TV needs to "register" the code first? 
        // Or we just store the config under the KEY `code`. 
        // The TV polls with `code`? 

        // Let's check TV logic... 
        // RemotePairingManager.kt: 
        // pollingUrl = "$RELAY_URL/status?code=$code&deviceId=$deviceId"
        // It sends BOTH.

        // So we can store by CODE.

        console.log(`Received config for code: ${code}`);
        memoryStore.set(code, config);

        return res.status(200).json({ success: true, message: 'Config relayed' });
    }

    // 3. VALIDATE Endpoint (Web Dashboard calls this)
    // GET /api/pair/validate?code=123456
    if (req.url.includes('/api/pair/validate')) {
        const urlParams = new URLSearchParams(req.url.split('?')[1]);
        const code = urlParams.get('code');

        if (!code) return res.status(400).json({ error: 'Missing code' });

        // Check if we've seen this code recently (from TV polling)
        const isActive = memoryStore.has(code);

        if (isActive) {
            return res.status(200).json({ valid: true });
        } else {
            // For improved UX during stateless/cold-start scenarios:
            // logic: If we haven't seen the code, it's either invalid OR the TV hit a different server instance.
            // But we must return 404 to satisfy "Real Solution" request.
            return res.status(404).json({ valid: false, message: "Code not found. Make sure TV is on the pairing screen." });
        }
    }

    // 2.5 TV POLL BY CODE
    if (req.url.includes('/api/pair/poll')) {
        const urlParams = new URLSearchParams(req.url.split('?')[1]);
        const code = urlParams.get('code');

        if (!code) return res.status(400).json({ error: 'Missing code' });

        // IMPLICIT REGISTRATION:
        // If the TV is polling, the code is valid! Store it so validate() can find it.
        // But don't overwrite if actual config is waiting.
        if (!memoryStore.has(code)) {
            memoryStore.set(code, { status: 'waiting', timestamp: Date.now() });
        }

        const data = memoryStore.get(code);

        // Clean up old "waiting" entries to prevent memory leaks? 
        // Vercel recycles instances anyway, so not strictly critical for this demo.

        if (data && data.status !== 'waiting') {
            memoryStore.delete(code);
            return res.status(200).json(data);
        } else {
            return res.status(200).json({ status: 'waiting' });
        }
    }

    res.status(404).json({ error: 'Not found' });
};
