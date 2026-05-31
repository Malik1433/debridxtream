const fs = require('fs');
const path = require('path');
const readline = require('readline');

const dir = path.join(__dirname, 'temp_qa', 'playback-diagnostics');
const files = fs.readdirSync(dir).filter(f => f.endsWith('.jsonl'));

async function verify() {
    let results = [];
    
    for (const file of files) {
        const fileStream = fs.createReadStream(path.join(dir, file));
        const rl = readline.createInterface({ input: fileStream, crlfDelay: Infinity });

        let session = {
            id: file,
            hasReady: false,
            hasError: false,
            provider: null,
            contentType: null,
            isDirect: false,
            errors: [],
            terminalFailure: null,
            returnToSources: false,
            retryCount: 0,
            has404: false,
            has500: false,
        };

        for await (const line of rl) {
            if (!line.trim()) continue;
            let ev;
            try { ev = JSON.parse(line); } catch(e) { continue; }
            
            if (ev.eventType === 'playback_launch' && ev.fields) {
                if (ev.fields.provider) session.provider = ev.fields.provider;
                if (ev.fields.contentKind) session.contentType = ev.fields.contentKind;
                session.isDirect = ev.fields.directDebridPlayback;
            }
            if (ev.eventType === 'player_state_changed' && ev.fields && ev.fields.playerState === 'READY') {
                session.hasReady = true;
            }
            if (ev.eventType === 'player_error') {
                session.hasError = true;
                const status = ev.fields.httpStatusCode;
                if (status >= 500 && status < 600) {
                    session.has500 = true;
                }
                if (status === 404) {
                    session.has404 = true;
                }
                session.errors.push(status || 'unknown_error');
            }
            if (ev.eventType === 'retry_triggered') {
                session.retryCount++;
            }
            if (ev.eventType === 'terminal_failure') {
                session.terminalFailure = ev.fields.reasonCode || 'unknown_terminal';
            }
            if (ev.eventType === 'return_to_sources') {
                session.returnToSources = true;
            }
        }
        
        if (session.provider || session.hasError || session.terminalFailure) {
            results.push(session);
        }
    }
    
    results.sort((a, b) => a.id.localeCompare(b.id));
    
    for (const r of results.slice(-20)) {
        console.log(`[${r.id}] ${r.provider || '?'} | Direct: ${r.isDirect} | READY: ${r.hasReady} | 500: ${r.has500} | 404: ${r.has404} | Retries: ${r.retryCount} | Terminal: ${r.terminalFailure} | R2S: ${r.returnToSources}`);
    }
}
verify();
