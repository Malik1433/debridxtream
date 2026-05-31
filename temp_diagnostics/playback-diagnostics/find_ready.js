const fs = require('fs');
const files = fs.readdirSync('.');
for (let f of files) {
  if (f.endsWith('.jsonl')) {
    const text = fs.readFileSync(f, 'utf8');
    if (text.includes('AIOStreams') && text.includes('"playerState":"READY"')) {
      console.log(f);
    }
  }
}
