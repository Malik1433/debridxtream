const fs = require('fs');
const files = fs.readdirSync('.').filter(f => f.endsWith('.jsonl'));

let results = {
  directSuccess: 0,
  directFail: 0,
  resolverSuccess: 0,
  resolverFail: 0
};

for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  if (!text.includes('AIOStreams')) continue;

  let direct = null;
  let hasReady = text.includes('"playerState":"READY"');
  let hasError = text.includes('"eventType":"player_error"') || text.includes('"eventType":"playback_failed_terminal"');

  const lines = text.split('\n');
  for (const line of lines) {
    if (line.includes('"eventType":"playback_launch"')) {
       try {
         const ev = JSON.parse(line);
         if (ev.fields && ev.fields.provider.includes('AIOStreams')) {
            direct = ev.fields.directDebridPlayback;
         }
       } catch (e) {}
    }
  }

  if (direct === true) {
     if (hasReady && !hasError) results.directSuccess++;
     else results.directFail++;
  } else if (direct === false) {
     if (hasReady && !hasError) results.resolverSuccess++;
     else results.resolverFail++;
  }
}
console.log(results);
