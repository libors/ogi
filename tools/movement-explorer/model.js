// A JavaScript copy of cz.libors.ogi.move.MeleeMovement (the candidates, the risk, the team pull, the choice) with
// MeleeMovementParameters.DEFAULT and the constants of MeleeMovement and EnemyWaves, plus a simplified
// EnemyWaves.meleeDanger. parity.js checks the choice against the bot's logged decisions; keep it in step with the
// Java code (README.md). World coordinates: y up, radians, 0 = east, counter-clockwise positive (the bot's
// mathematical convention).
const P = {
  teammateWeight: 3, distanceExponent: 2, closerExponent: 1, closerCountsEnemies: true, perpendicularWeight: 1,
  minThreat: 0.5, maxThreat: 2, historyWeight: 400, keep: 0.9, waveCandidates: 8, waveWeight: 3, minWaveMedian: 0.1,
  teamPull: 0.5, helpDistance: 300, finishDistance: 350, finishEnergy: 30, finishPull: 1.0,
  // Not parameters of the bot, which repels teammates and our recent positions with 1/d² fixed: 2 plays as the bot.
  teammateExponent: 2, historyExponent: 2,
};
const C = {
  CANDIDATES: 40, MIN_REACH: 80, MAX_REACH: 220, CLOSEST_SHARE: 0.6, WALL_MARGIN: 45, ARRIVED: 20,
  HISTORY_EVERY: 5, HISTORY_SIZE: 20, GANG_SIZE: 2, GANG_RADIUS: 250, MELEE_HORIZON: 30, MIN_AIM_CHANCE: 0.1,
  AIM_EXPONENT: 2, HALF: 18,
};
const ROLE = {NONE: 0, GANGED: 1, HELPER: 2, FINISHER: 3};
/** Whether an enemy's shot can only have aimed at our robots (EnemyWaves.teamBattle); in a melee at anybody. */
const MODE = {teamBattle: true};

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const hyp = (dx, dy) => Math.sqrt(dx * dx + dy * dy);
const bearing = (x, y, tx, ty) => Math.atan2(ty - y, tx - x);
const normRel = a => {
  a = (a + Math.PI) % (2 * Math.PI);
  if (a < 0) a += 2 * Math.PI;
  return a - Math.PI;
};
const inField = (field, x, y, m) => x >= m && y >= m && x <= field.w - m && y <= field.h - m;
const bulletDamage = p => 4 * p + (p > 1 ? 2 * (p - 1) : 0);
const bulletSpeed = p => 20 - 3 * p;

/** The robots one of ours keeps away from: every enemy and the other robots of ours. */
function robotsFor(me, all) {
  const list = [];
  for (const r of all) {
    if (r === me || r.dead) continue;
    list.push({x: r.x, y: r.y, energy: r.energy, enemy: r.kind === 'enemy', ref: r});
  }
  return list;
}

function enemiesNear(x, y, robots) {
  let n = 0;
  for (const r of robots) if (r.enemy && hyp(r.x - x, r.y - y) <= C.GANG_RADIUS) n++;
  return n;
}

/** MeleeMovement.pull: who this turn's destinations are drawn to. */
function pull(me, robots, gangDefence) {
  const out = {x: NaN, y: NaN, floor: 0, strength: 0, role: ROLE.NONE, target: null};
  if (P.finishDistance > 0 && me.energy >= P.finishEnergy) {
    let last = null, enemies = 0, mates = false;
    for (const r of robots) {
      if (r.enemy) { enemies++; last = r; } else mates = true;
    }
    if (enemies === 1 && mates) {
      return {x: last.x, y: last.y, floor: P.finishDistance, strength: P.finishPull, role: ROLE.FINISHER,
        target: last.ref};
    }
  }
  if (P.teamPull === 0 || !gangDefence) return out;
  let nearestMate = null, nearestGanged = null;
  const d = r => hyp(r.x - me.x, r.y - me.y);
  for (const r of robots) {
    if (r.enemy) continue;
    if (!nearestMate || d(r) < d(nearestMate)) nearestMate = r;
    if (enemiesNear(r.x, r.y, robots) >= C.GANG_SIZE && (!nearestGanged || d(r) < d(nearestGanged))) {
      nearestGanged = r;
    }
  }
  if (nearestMate && enemiesNear(me.x, me.y, robots) >= C.GANG_SIZE) {
    return {x: nearestMate.x, y: nearestMate.y, floor: 0, strength: P.teamPull, role: ROLE.GANGED,
      target: nearestMate.ref};
  }
  if (nearestGanged) {
    return {x: nearestGanged.x, y: nearestGanged.y, floor: P.helpDistance, strength: P.teamPull,
      role: ROLE.HELPER, target: nearestGanged.ref};
  }
  return out;
}

function distances(robots) {
  const n = robots.length;
  const d = [];
  for (let i = 0; i < n; i++) d.push(new Float64Array(n));
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      d[i][j] = d[j][i] = hyp(robots[i].x - robots[j].x, robots[i].y - robots[j].y);
    }
  }
  return d;
}

/**
 * MeleeMovement.risk of driving from me to (px, py). With detail, also the terms: each robot's share of the sum,
 * the history factor and the pull factor.
 */
function risk(me, px, py, robots, dist, pl, detail) {
  const way = bearing(me.x, me.y, px, py);
  let sum = 0;
  const terms = detail ? [] : null;
  for (let i = 0; i < robots.length; i++) {
    const e = robots[i];
    const d2 = (e.x - px) * (e.x - px) + (e.y - py) * (e.y - py);
    if (!e.enemy) {
      const t = P.teammateWeight / Math.pow(Math.max(d2, 1), P.teammateExponent / 2);
      sum += t;
      if (detail) terms.push({robot: e.ref, enemy: false, d: Math.sqrt(d2), value: t});
      continue;
    }
    let closer = 0;
    for (let j = 0; j < robots.length; j++) {
      if (j !== i && (P.closerCountsEnemies || !robots[j].enemy) && dist[i][j] * dist[i][j] < d2) closer++;
    }
    const threat = Math.max(P.minThreat, Math.min(P.maxThreat, e.energy / Math.max(me.energy, 0.1)));
    const line = Math.abs(Math.cos(way - bearing(px, py, e.x, e.y)));
    const t = threat * (1 + P.perpendicularWeight * line)
      / (Math.pow(Math.max(d2, 1), P.distanceExponent / 2) * Math.pow(1 + closer, P.closerExponent));
    sum += t;
    if (detail) terms.push({robot: e.ref, enemy: true, d: Math.sqrt(d2), threat, line, closer, value: t});
  }
  let history = 1;
  for (const p of me.history) {
    const d2 = (p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py);
    history += P.historyWeight / Math.pow(Math.max(d2, 1), P.historyExponent / 2);
  }
  let pullFactor = 1;
  if (!Number.isNaN(pl.x)) {
    const from = Math.max(pl.floor, hyp(pl.x - me.x, pl.y - me.y));
    const to = Math.max(pl.floor, hyp(pl.x - px, pl.y - py));
    pullFactor = Math.exp(pl.strength * (to - from) / 100);
  }
  const value = sum * history * pullFactor;
  return detail ? {value, sum, history, pullFactor, terms} : value;
}

/** MeleeMovement.command's candidates: {x, y, kept}. */
function candidates(me, robots, field, rng) {
  let closest = Infinity;
  for (const r of robots) if (r.enemy) closest = Math.min(closest, hyp(r.x - me.x, r.y - me.y));
  const pts = [];
  if (me.dest && hyp(me.dest[0] - me.x, me.dest[1] - me.y) > C.ARRIVED
    && inField(field, me.dest[0], me.dest[1], C.WALL_MARGIN)) {
    pts.push({x: me.dest[0], y: me.dest[1], kept: true});
  }
  const reach = Math.min(C.MAX_REACH, C.CLOSEST_SHARE * closest);
  for (let i = 0; i < C.CANDIDATES; i++) {
    const angle = 2 * Math.PI * (i + rng()) / C.CANDIDATES;
    const d = Math.min(reach, C.MIN_REACH + (C.MAX_REACH - C.MIN_REACH) * rng());
    const px = me.x + d * Math.cos(angle), py = me.y + d * Math.sin(angle);
    if (inField(field, px, py, C.WALL_MARGIN)) pts.push({x: px, y: py, kept: false});
  }
  return {pts, reach};
}

/**
 * The aim chance of an enemy wave against me: 1 / (1 + the robots the shooter had closer than me)^e. In a team battle
 * those are our other robots, since an enemy never aims at its own; in a melee everybody else but the shooter.
 */
function aimChance(me, wave, all) {
  const ours = hyp(me.x - wave.x, me.y - wave.y);
  let closer = 0;
  for (const r of all) {
    if (r === me || r.dead || r.id === wave.by || (MODE.teamBattle && r.kind === 'enemy')) continue;
    if (hyp(r.x - wave.x, r.y - wave.y) < ours) closer++;
  }
  return 1 / Math.pow(1 + closer, C.AIM_EXPONENT);
}

function normalCdf(z) {
  // Abramowitz-Stegun 7.1.26 on erf.
  const t = 1 / (1 + 0.3275911 * Math.abs(z) / Math.SQRT2);
  const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
    * Math.exp(-z * z / 2);
  return z >= 0 ? (1 + y) / 2 : (1 - y) / 2;
}

/** The angular span {min, max} (relative to ref) of the box at (x, y) seen from (ox, oy). */
function boxSpan(ox, oy, x, y, ref) {
  let min = Infinity, max = -Infinity;
  for (const [cx, cy] of [[x - C.HALF, y - C.HALF], [x + C.HALF, y - C.HALF], [x - C.HALF, y + C.HALF],
    [x + C.HALF, y + C.HALF]]) {
    const a = normRel(bearing(ox, oy, cx, cy) - ref);
    min = Math.min(min, a);
    max = Math.max(max, a);
  }
  return [min, max];
}

function boxDistances(ox, oy, x, y) {
  const nx = Math.max(x - C.HALF, Math.min(ox, x + C.HALF)), ny = Math.max(y - C.HALF, Math.min(oy, y + C.HALF));
  const near = hyp(nx - ox, ny - oy);
  const fx = Math.abs(ox - (x - C.HALF)) > Math.abs(ox - (x + C.HALF)) ? x - C.HALF : x + C.HALF;
  const fy = Math.abs(oy - (y - C.HALF)) > Math.abs(oy - (y + C.HALF)) ? y - C.HALF : y + C.HALF;
  return [near, hyp(fx - ox, fy - oy)];
}

/**
 * A simplified EnemyWaves.meleeDanger: we drive straight to (px, py) and stand (acceleration 1, braking 2, at most
 * 8 px/turn, no turning), at most 30 turns. An enemy wave counts its bullet's damage times the share of its aim
 * profile (a normal distribution around its aim, sd `spread`) inside our hit range, times the chance that it aimed
 * at us; one below 0.1 does not count. A teammate's bullet counts its whole damage if the hit range covers its line.
 */
function wavesDamage(me, px, py, waves, all) {
  const active = [];
  for (const w of waves) {
    if (w.passed) continue;
    const chance = w.team ? 1 : aimChance(me, w, all);
    if (!w.team && chance < C.MIN_AIM_CHANCE) continue;
    active.push({w, chance, min: Infinity, max: -Infinity, passed: false});
  }
  if (active.length === 0) return {damage: 0, parts: []};
  let x = me.x, y = me.y, v = me.v || 0;
  const observe = t => {
    for (const a of active) {
      if (a.passed) continue;
      const speed = bulletSpeed(a.w.power);
      const inner = speed * (a.w.age + t), outer = inner + speed;
      const [near, far] = boxDistances(a.w.x, a.w.y, x, y);
      if (near <= outer && far >= inner) {
        const [lo, hi] = boxSpan(a.w.x, a.w.y, x, y, a.w.heading);
        a.min = Math.min(a.min, lo);
        a.max = Math.max(a.max, hi);
      }
      a.passed = inner > far;
    }
  };
  observe(0);
  for (let t = 1; t <= C.MELEE_HORIZON && active.some(a => !a.passed); t++) {
    const rem = hyp(px - x, py - y);
    const stop = -1 + Math.sqrt(1 + 4 * rem);
    v = Math.max(0, Math.min(v + 1, 8, stop, rem));
    if (rem > 1e-9) {
      x += (px - x) / rem * v;
      y += (py - y) / rem * v;
    }
    observe(t);
  }
  let damage = 0;
  const parts = [];
  for (const a of active) {
    let d = 0;
    if (a.min <= a.max) {
      if (a.w.team) {
        d = a.min <= 0 && a.max >= 0 ? bulletDamage(a.w.power) : 0;
      } else {
        const share = normalCdf(a.max / a.w.spread) - normalCdf(a.min / a.w.spread);
        d = a.chance * share * bulletDamage(a.w.power);
      }
    }
    damage += d;
    parts.push({wave: a.w, chance: a.chance, damage: d});
  }
  return {damage, parts};
}

function median(values) {
  const v = values.slice().sort((a, b) => a - b);
  const m = v.length >> 1;
  return v.length % 2 ? v[m] : (v[m - 1] + v[m]) / 2;
}

/**
 * MeleeMovement.choose: the destination among the candidates ({x, y, kept}). Each gets its risk; then the
 * waveCandidates with the least risk (the kept one first) get the waves' damage from damageOf(c), {damage, parts},
 * and the least risk / median + waveWeight · damage / max(minWaveMedian, median) wins, the kept one at keep × its
 * score. Without damageOf the least risk wins (keep × for the kept one). best is null without candidates.
 */
function choose(me, robots, pts, damageOf, gangDefence) {
  const dist = distances(robots);
  const pl = pull(me, robots, gangDefence);
  for (const c of pts) c.risk = risk(me, c.x, c.y, robots, dist, pl, false);
  const out = {dist, pull: pl, waved: [], best: null, riskBest: null};
  if (pts.length === 0) return out;
  let rb = null;
  for (const c of pts) if (!rb || c.risk * (c.kept ? P.keep : 1) < rb.risk * (rb.kept ? P.keep : 1)) rb = c;
  out.riskBest = rb;
  if (!damageOf) {
    out.best = rb;
    return out;
  }
  // Stable, as the bot's insertion sort: the kept destination first, then by risk.
  const sorted = pts.slice().sort((a, b) => (b.kept - a.kept) || (a.risk - b.risk));
  const k = Math.min(sorted.length, P.waveCandidates);
  const top = sorted.slice(0, k);
  for (const c of top) {
    const wd = damageOf(c);
    c.damage = wd.damage;
    c.parts = wd.parts;
  }
  const riskMedian = median(top.map(c => c.risk));
  const waveMedian = Math.max(P.minWaveMedian, median(top.map(c => c.damage)));
  let best = top[0], bestScore = Infinity;
  for (const c of top) {
    c.riskRatio = c.risk / riskMedian;
    c.waveTerm = P.waveWeight * c.damage / waveMedian;
    c.score = (c.riskRatio + c.waveTerm) * (c.kept ? P.keep : 1);
    if (c.score < bestScore) { bestScore = c.score; best = c; }
  }
  out.waved = top;
  out.riskMedian = riskMedian;
  out.waveMedian = waveMedian;
  out.best = best;
  return out;
}

/** One robot's decision: its candidates, chosen against the waves in the air; everything the page draws. */
function decide(me, all, waves, field, gangDefence, rng) {
  const robots = robotsFor(me, all);
  const {pts, reach} = candidates(me, robots, field, rng);
  const out = choose(me, robots, pts, c => wavesDamage(me, c.x, c.y, waves, all), gangDefence);
  out.robots = robots;
  out.reach = reach;
  out.candidates = pts;
  if (!out.best) {
    // Every candidate was too close to a wall: the bot heads for the middle.
    out.best = {x: field.w / 2, y: field.h / 2, risk: NaN};
  }
  return out;
}

/** The random generator of a robot's candidates in a turn. */
function seedFor(r, turn, offset) {
  return mulberry32(r.seed * 7919 + turn * 104729 + 3 + offset * 31337);
}

/**
 * The history a decision of this turn uses: every HISTORY_EVERY turns the current position joins it first, as
 * MeleeMovement.remember does before the choice. A new array then; r.history itself is not changed.
 */
function historyAt(r, turn) {
  if (turn % C.HISTORY_EVERY !== 0) return r.history;
  const h = [[r.x, r.y], ...r.history];
  if (h.length > C.HISTORY_SIZE) h.length = C.HISTORY_SIZE;
  return h;
}

/** One turn of our robots: each decides from the others' current positions, then all move (enemies stand). */
function stepTurn(state, field, gangDefence, seedOffset = 0) {
  const decisions = new Map();
  for (const r of state.robots) {
    if (r.kind === 'enemy' || r.dead) continue;
    r.history = historyAt(r, state.turn);
    const rng = seedFor(r, state.turn, seedOffset);
    decisions.set(r, decide(r, state.robots, state.waves, field, gangDefence, rng));
  }
  for (const [r, dec] of decisions) {
    r.dest = [dec.best.x, dec.best.y];
    if (r.pinned) continue;
    const rem = hyp(r.dest[0] - r.x, r.dest[1] - r.y);
    const stop = -1 + Math.sqrt(1 + 4 * rem);
    r.v = Math.max(0, Math.min((r.v || 0) + 1, 8, stop, rem));
    if (rem > 1e-9) {
      r.x += (r.dest[0] - r.x) / rem * r.v;
      r.y += (r.dest[1] - r.y) / rem * r.v;
    }
    (r.trail || (r.trail = [])).push([r.x, r.y]);
    if (r.trail.length > 400) r.trail.shift();
  }
  // Enemies that follow one of ours close in on it at full speed down to their distance; the others stand.
  for (const e of state.robots) {
    if (e.kind !== 'enemy' || e.dead || !e.follow) continue;
    const t = state.robots.find(r => r.id === e.follow && !r.dead);
    if (!t) continue;
    const d = hyp(t.x - e.x, t.y - e.y);
    const step = Math.min(8, Math.max(0, d - (e.keep || 150)));
    if (step > 0) {
      e.x += (t.x - e.x) / d * step;
      e.y += (t.y - e.y) / d * step;
    }
    (e.trail || (e.trail = [])).push([e.x, e.y]);
    if (e.trail.length > 400) e.trail.shift();
  }
  for (const w of state.waves) {
    w.age++;
    const speed = bulletSpeed(w.power);
    if (speed * w.age > 2000) w.passed = true;
  }
  state.turn++;
  return decisions;
}
if (typeof module !== 'undefined') {
  module.exports = {P, C, ROLE, MODE, mulberry32, hyp, bearing, robotsFor, pull, distances, risk, candidates,
    aimChance, wavesDamage, choose, decide, historyAt, stepTurn, enemiesNear, seedFor};
}
