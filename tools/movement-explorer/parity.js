#!/usr/bin/env node
// Replays the melee movement's logged decisions (the `meleemove` debug property of OgiGunLog in full recordings, as
// the position bench reads them) through model.js and checks that it chooses the logged destination, as the position
// bench's own parity check does for the Java code.
//
// Usage: node tools/movement-explorer/parity.js <recording.rec.gz | directory> ... [key=value ...]
//   key=value overrides a parameter of model.js (P or C) with what the recorded bot played with.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const M = require('./model.js');

function files(arg) {
  const st = fs.statSync(arg);
  if (!st.isDirectory()) return [arg];
  return fs.readdirSync(arg).filter(f => f.endsWith('.rec.gz')).sort().map(f => path.join(arg, f));
}

/** One logged decision: now;x;y;heading;energy;gangDefence;robots;candidates;destination (MeleeMovement.logLine). */
function parse(value) {
  const f = value.split(';');
  const robots = f[6] === '' ? [] : f[6].split(' ').map(r => {
    const v = r.split(',');
    return {x: Number(v[0]), y: Number(v[1]), energy: Number(v[2]), enemy: v[3] === 'E'};
  });
  const candidates = f[7] === '' ? [] : f[7].split(' ').map(c => {
    const v = c.split(',');
    return {x: Number(v[0]), y: Number(v[1]), kept: v[2] === '1', waves: Number(v[3])};
  });
  const chosen = f[8].split(',').map(Number);
  return {turn: Number(f[0]), x: Number(f[1]), y: Number(f[2]), energy: Number(f[4]), gang: f[5] === '1', robots,
    candidates, chosen};
}

const args = process.argv.slice(2);
const overrides = args.filter(a => a.includes('='));
for (const o of overrides) {
  const [k, v] = o.split('=');
  const store = k in M.P ? M.P : k in M.C ? M.C : null;
  if (!store) throw new Error('unknown parameter ' + k);
  store[k] = v === 'true' ? true : v === 'false' ? false : Number(v);
}
const inputs = args.filter(a => !a.includes('=')).flatMap(files);
if (inputs.length === 0) {
  console.error('usage: node parity.js <recording.rec.gz | directory> ... [key=value ...]');
  process.exit(2);
}

let total = 0, differ = 0;
const examples = [];
for (const file of inputs) {
  const lines = zlib.gunzipSync(fs.readFileSync(file)).toString('utf8').split('\n');
  let width = NaN, height = NaN;
  const groups = new Map();
  for (const line of lines) {
    if (line.startsWith('battle\t')) {
      for (const kv of line.split('\t').slice(1)) {
        const [k, v] = kv.split('=');
        if (k === 'width') width = Number(v);
        if (k === 'height') height = Number(v);
      }
    } else if (line.startsWith('d\t')) {
      const f = line.split('\t');
      if (f[4] !== 'meleemove') continue;
      const key = f[1] + ':' + f[3];
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(parse(f.slice(5).join('\t')));
    }
  }
  // Per round and robot, as the bench: the gang defence's switch, the position into the history, then the choice.
  for (const [key, decisions] of groups) {
    const me = {x: 0, y: 0, energy: 0, history: []};
    for (const d of decisions) {
      Object.assign(me, {x: d.x, y: d.y, energy: d.energy});
      if (d.turn % M.C.HISTORY_EVERY === 0) {
        me.history.unshift([d.x, d.y]);
        while (me.history.length > M.C.HISTORY_SIZE) me.history.pop();
      }
      const pts = d.candidates.map(c => ({x: c.x, y: c.y, kept: c.kept, waves: c.waves}));
      const out = M.choose(me, d.robots, pts, c => ({damage: c.waves, parts: []}), d.gang);
      const best = out.best ? [out.best.x, out.best.y] : [width / 2, height / 2];
      total++;
      if (best[0] !== d.chosen[0] || best[1] !== d.chosen[1]) {
        differ++;
        if (examples.length < 5) {
          examples.push(`${path.basename(file)} round:robot ${key} turn ${d.turn}: ${best} instead of ${d.chosen}`);
        }
      }
    }
  }
}
console.log(`Parity: model.js chose the logged destination in ${total - differ} of ${total} decisions`
  + ` (${inputs.length} recording${inputs.length > 1 ? 's' : ''}).`);
for (const e of examples) console.log('  ' + e);
process.exit(total > 0 && differ === 0 ? 0 : 1);
