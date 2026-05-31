const fs = require('fs');
const path = require('path');
const readline = require('readline');

const dir = path.join(__dirname, 'playback-diagnostics');
const files = fs.readdirSync(dir).filter(f => f.endsWith('.jsonl'));

let totalSessions = 0;
let successCount = 0;
let failureCount = 0;
let providerClusters = {};
let terminalFailures = [];
let cacheStatusStats = {};
let trackStats = { hasAudio: 0, hasText: 0, none: 0 };
let retryPatterns = {};

async function processFiles() {
    for (const file of files) {
        totalSessions++;
        const fileStream = fs.createReadStream(path.join(dir, file));
        const rl = readline.createInterface({ input: fileStream, crlfDelay: Infinity });

        let sessionState = { 
            hasReady: false, 
            hasError: false, 
            lastProvider: null,
            lastSourceName: null,
            lastError: null,
            lastCacheStatus: null,
            retries: 0
        };

        for await (const line of rl) {
            if (!line.trim()) continue;
            let ev;
            try { ev = JSON.parse(line); } catch(e) { continue; }
            const f = ev.fields;
            if (!f) continue;

            if (f.provider) {
                sessionState.lastProvider = f.provider;
                sessionState.lastSourceName = f.sourceName;
                providerClusters[f.provider] = (providerClusters[f.provider] || 0) + 1;
            }
            if (f.cacheStatus) {
                sessionState.lastCacheStatus = f.cacheStatus;
                cacheStatusStats[f.cacheStatus] = (cacheStatusStats[f.cacheStatus] || 0) + 1;
            }

            if (ev.eventType === 'player_state_changed' && f.playerState === 'READY') {
                sessionState.hasReady = true;
            }
            if (ev.eventType === 'playback_error' || ev.eventType === 'player_error' || ev.eventType === 'playback_failed_terminal' || (ev.eventType === 'player_state_changed' && f.playerState === 'IDLE' && f.error)) {
                sessionState.hasError = true;
                sessionState.lastError = f.error || f.reason || ev.eventType;
            }
            if (ev.eventType === 'track_discovery') {
                if (f.supportedAudioTrackCount > 0) trackStats.hasAudio++;
                if (f.supportedTextTrackCount > 0) trackStats.hasText++;
                if (f.supportedAudioTrackCount === 0 && f.supportedTextTrackCount === 0) trackStats.none++;
            }
            if (f.retryCount > 0) {
                sessionState.retries = Math.max(sessionState.retries, f.retryCount);
                retryPatterns[f.retryCount] = (retryPatterns[f.retryCount] || 0) + 1;
            }
            if (ev.eventType === 'playback_failed_terminal') {
                terminalFailures.push({
                    file, provider: f.provider, sourceName: f.sourceName,
                    cacheStatus: f.cacheStatus, error: f.error || f.reason
                });
            }
        }

        if (sessionState.hasReady && !sessionState.hasError) {
            successCount++;
        } else if (sessionState.hasError || !sessionState.hasReady) {
            failureCount++;
            if (!sessionState.hasReady && !sessionState.hasError) {
                terminalFailures.push({ file, note: 'Never reached READY state', provider: sessionState.lastProvider, sourceName: sessionState.lastSourceName, cacheStatus: sessionState.lastCacheStatus });
            } else if (sessionState.hasError && terminalFailures.findIndex(x => x.file === file) === -1) {
                terminalFailures.push({ file, note: 'Error encountered', provider: sessionState.lastProvider, sourceName: sessionState.lastSourceName, cacheStatus: sessionState.lastCacheStatus, error: sessionState.lastError });
            }
        }
    }

    console.log("=== Playback Diagnostics Analysis ===");
    console.log(`Total Sessions Analyzed: ${totalSessions}`);
    console.log(`Successful Playbacks: ${successCount}`);
    console.log(`Failed Playbacks: ${failureCount}`);
    console.log("\nProvider/Source Clusters:");
    console.table(providerClusters);
    console.log("\nDirect vs Verified-Cached:");
    console.table(cacheStatusStats);
    console.log("\nRetry/Timeout/Error Patterns:");
    console.table(retryPatterns);
    console.log("\nSubtitle/Track Discovery:");
    console.table(trackStats);
    console.log("\nTerminal Failures:");
    console.log(JSON.stringify(terminalFailures, null, 2));
}

processFiles();
