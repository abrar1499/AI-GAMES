// Replays a conversation file (bolta-munshi-baatein.txt) through the current brain, with every guess the
// phone offered, to see how each sentence would be answered now: node tests/replay.js <file>
const fs = require('fs'); const M = require(require('path').join(__dirname, '..', 'brain.js'));
const db = M.emptyDb(); let t = Date.parse('2026-11-13T04:48:45');
for (const line of fs.readFileSync(process.argv[2], 'utf8').split('\n')) {
  const heard = (line.match(/SUNA: (.*?)(?: \| |$)/) || [])[1]; if (!heard) continue;
  const others = ((line.match(/DOOSRE ANDAZE: (.*?) \| JAWAB/) || [])[1] || '').split(' / ').filter(Boolean);
  const old = (line.match(/JAWAB: (.*?)(?: \| \[|$)/) || [])[1];
  const r = M.handleBest([heard, ...others], db, new Date(t += 8000));
  console.log('> ' + heard + '\n   pehle: ' + old + '\n   ab:    ' + r.show + (r.weak ? '   [khali jawab]' : ''));
}
