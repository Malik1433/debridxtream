const json = (res: any, status: number, body: Record<string, unknown>) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
    res.status(status).json(body);
};

const normalizeServerUrl = (value: string): string => {
    return value.trim().replace(/\/+$/, '');
};

const buildVerificationUrl = (serverUrl: string, username: string, password: string): string => {
    const base = normalizeServerUrl(serverUrl);
    const root = base.toLowerCase().includes('/player_api.php') ? base : `${base}/player_api.php`;
    const url = new URL(root);
    url.searchParams.set('username', username.trim());
    url.searchParams.set('password', password.trim());
    return url.toString();
};

const readBody = async (req: any): Promise<any> => {
    if (req.body && typeof req.body === 'object') {
        return req.body;
    }

    const chunks: Buffer[] = [];
    for await (const chunk of req) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    }

    const raw = Buffer.concat(chunks).toString('utf8');
    return raw ? JSON.parse(raw) : {};
};

export default async function handler(req: any, res: any) {
    if (req.method === 'OPTIONS') {
        json(res, 204, {});
        return;
    }

    if (req.method !== 'POST') {
        json(res, 405, { ok: false, message: 'Method not allowed.' });
        return;
    }

    try {
        const body = await readBody(req);
        const serverUrl = typeof body?.serverUrl === 'string' ? body.serverUrl.trim() : '';
        const username = typeof body?.username === 'string' ? body.username.trim() : '';
        const password = typeof body?.password === 'string' ? body.password.trim() : '';

        if (!serverUrl || !username || !password) {
            json(res, 400, {
                ok: false,
                message: 'Server URL, username, and password are required.'
            });
            return;
        }

        if (!/^https?:\/\//i.test(serverUrl)) {
            json(res, 400, {
                ok: false,
                message: 'Server URL must start with http:// or https://.'
            });
            return;
        }

        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 12000);

        try {
            const response = await fetch(buildVerificationUrl(serverUrl, username, password), {
                method: 'GET',
                signal: controller.signal
            });

            const text = await response.text();
            let data: any = {};

            try {
                data = text ? JSON.parse(text) : {};
            } catch {
                data = {};
            }

            const userInfo = data?.user_info ?? {};
            const authValue = Number(userInfo?.auth ?? 0);
            const statusValue = String(userInfo?.status ?? '').toLowerCase();
            const isAuthorized = authValue === 1 || statusValue === 'active';

            if (!response.ok || !isAuthorized) {
                json(res, 401, {
                    ok: false,
                    message: 'Wrong IPTV login details.'
                });
                return;
            }

            json(res, 200, {
                ok: true,
                message: 'IPTV verified.'
            });
        } catch (error: any) {
            json(res, 502, {
                ok: false,
                message: error?.name === 'AbortError'
                    ? 'IPTV verification timed out.'
                    : 'Unable to verify IPTV provider.'
            });
        } finally {
            clearTimeout(timeout);
        }
    } catch {
        json(res, 400, {
            ok: false,
            message: 'Invalid request payload.'
        });
    }
}
