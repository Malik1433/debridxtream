const fs = require('fs');
const path = require('path');
const readline = require('readline');

const dir = path.join(__dirname, 'playback-diagnostics');
const files = fs.readdirSync(dir).filter(f => f.endsWith('.jsonl'));

async function analyze() {
    let successByProvider = {};
    let failureByProvider = {};
    
    for (const file of files) {
        const fileStream = fs.createReadStream(path.join(dir, file));
        const rl = readline.createInterface({ input: fileStream, crlfDelay: Infinity });

        let sessionState = { hasReady: false, hasError: false, provider: 'Unknown' };

        for await (const line of rl) {
            if (!line.trim()) continue;
            let ev;
            try { ev = JSON.parse(line); } catch(e) { continue; }
            if (ev.fields && ev.fields.provider) sessionState.provider = ev.fields.provider;
            if (ev.eventType === 'player_state_changed' && ev.fields && ev.fields.playerState === 'READY') sessionState.hasReady = true;
            if (ev.eventType === 'playback_error' || ev.eventType === 'player_error' || ev.eventType === 'playback_failed_terminal' || (ev.eventType === 'player_state_changed' && ev.fields && ev.fields.playerState === 'IDLE' && ev.fields.error)) sessionState.hasError = true;
        }

        if (sessionState.hasReady && !sessionState.hasError) {
            successByProvider[sessionState.provider] = (successByProvider[sessionState.provider] || 0) + 1;
        } else {
            failureByProvider[sessionState.provider] = (failureByProvider[sessionState.provider] || 0) + 1;
        }
    }

    console.log("Success by provider:", successByProvider);
    console.log("Failure by provider:", failureByProvider);
}
analyze();
