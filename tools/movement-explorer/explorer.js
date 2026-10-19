// The movement explorer's page: a battlefield with the melee movement's risk map, its candidates and its choice, and
// the controls around it. The page that loads it sets window.EXPLORER = {mode: 'team' | 'melee', title, field: {w, h},
// scenarios} first, then loads model.js and this file.
const CFG = window.EXPLORER;
const TEAM = CFG.mode === 'team';
MODE.teamBattle = TEAM;

function teamOnly(html) {
  return TEAM ? html : '';
}

// ---------- Layout ----------
const REFERENCE = `
<p><b>When:</b> ${TEAM
    ? 'while more than one enemy lives, and in a team\'s endgame (one enemy left, teammates alive). Alone against one enemy the bot surfs instead.'
    : 'while more than one other robot lives. With one left the bot surfs it as in a duel.'}</p>
<p class="muted">The numbers below are the bot's values; the <i>Parameters</i> card changes them.</p>
<p><b>1. Candidates.</b> 40 sectors of 9&deg; around us, one point per sector at a random angle within it, at a random distance 80&ndash;220 px, clamped to <code>reach = min(220, 0.6 &times; closest enemy)</code>. Points within 45 px of a wall are dropped. Last turn's destination is a 41st candidate (until we are within 20 px of it) at 0.9 &times; its risk or score.</p>
<p><b>2. Risk of a point D</b> (from where we are now):</p>
<div class="formula mono">${TEAM
    ? 'risk(D) = [ &Sigma;<sub>enemies</sub> threat&middot;(1 + |cos a|) / (d&sup2;&middot;(1 + n)) + &Sigma;<sub>teammates</sub> 3 / d&sup2; ]<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&times; (1 + &Sigma;<sub>last 20 positions</sub> 400 / d&sup2;) &times; e<sup>s&middot;(to &minus; from)/100</sup>'
    : 'risk(D) = &Sigma;<sub>enemies</sub> threat&middot;(1 + |cos a|) / (d&sup2;&middot;(1 + n))<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&times; (1 + &Sigma;<sub>last 20 positions</sub> 400 / d&sup2;)'}</div>
<ul>
  <li><b>threat</b> = enemy energy / our energy, clamped to [0.5, 2];</li>
  <li><b>a</b> = angle between our way to D and the line from D to the enemy (straight at/away from it: &times;2);</li>
  <li><b>n</b> = ${TEAM ? 'robots closer to that enemy than D: ours <i>and its own teammates</i>' : 'the other robots closer to that enemy than D'};</li>
  <li><b>history:</b> our position every 5 turns, the last 20;</li>
  ${teamOnly('<li><b>pull:</b> to = max(floor, |target &minus; D|), from = max(floor, |target &minus; us|). Finisher s = 1.0, floor 350 (the last enemy, our energy &ge; 30); ganged s = 0.5, floor 0 (nearest teammate); helper s = 0.5, floor 300 (nearest ganged teammate).</li>')}
</ul>
<p><b>3. Waves.</b> Last turn's destination, whatever its risk, and the candidates with the least risk, 8 in all, are predicted: drive straight there and stand, up to 30 turns. Each enemy wave adds bullet damage &times; the share of the shooter's predicted aim inside our hit range &times; the chance it aimed at us, <code>1/(1+n)&sup2;</code> with n ${TEAM
    ? 'our teammates it had closer than us (an enemy never aims at its own)'
    : 'the other robots it had closer than us (most melee robots aim at the closest)'}; waves under 0.1 are skipped.${teamOnly(' A teammate\'s bullet adds its whole damage if the way crosses its line.')}</p>
<div class="formula mono">score = risk / median(risk of the 8) + 3 &times; damage / max(0.1, median(damage of the 8))</div>
<p>The least score wins; the bot drives straight at it (backwards if that turns less).</p>
${teamOnly('<p><b>Gang defence switch</b> (<code>GangWatch</code>): a robot of ours is ganged with &ge; 2 enemies within 250 px. The pull for ganged robots and helpers is on once &ge; 4 % of our robot-turns in the battle were ganged (&ge; 200 counted).</p>')}`;

const SIMPLIFIES = `
<ul>
  <li><b>Exact copies</b> of the bot: the candidates' rule, the risk${teamOnly(', the pull and roles')}, the 8-candidate wave stage and its score. <code>parity.js</code> checks the choice against the bot's logged decisions. The random numbers differ (another generator).</li>
  <li><b>An enemy wave's aim</b> is a normal distribution around the drawn aim line. The bot uses the shooter's learned danger model (an ensemble of the KNN profile and simple guns).</li>
  <li><b>The path prediction</b> drives straight with acceleration 1, braking 2 and 8 px/turn, without turning the body or walls' collisions; the bot uses the engine's physics.</li>
  <li><b>The aim chance</b> uses the robots' current positions; the bot uses where each was when the shot left.</li>
  <li><b>Play:</b> ${TEAM
    ? 'our robots run the decision every turn; enemies stand, or follow one of ours down to a distance. Teammates see each other without the bot\'s one-turn report lag.'
    : 'only our robot moves; the enemies stand, or follow it down to a distance (in a real melee every robot moves).'} Nobody fires new bullets.</li>
  ${teamOnly('<li>The gang defence is a manual switch here; in the bot <code>GangWatch</code> decides it from the battle so far.</li>')}
  <li>${TEAM ? 'Two parameters exist only here: the teammates\' and the history\'s distance exponents.' : 'One parameter exists only here: the history\'s distance exponent.'} The bot has 2.</li>
</ul>`;

document.getElementById('app').innerHTML = `
<header>
  <h1>${CFG.title}</h1>
  <p>Ogi's ${TEAM ? 'team' : 'melee'} movement (<code>MeleeMovement</code>) on a ${CFG.field.w}&times;${CFG.field.h} field. Drag anything; hover for the risk of a point. ${TEAM ? '<a href="melee.html">Melee version</a>' : '<a href="team.html">Team version</a>'}</p>
</header>
<main class="layout">
  <section class="stage">
    <div class="toolbar">
      <button id="step" title="One turn: every robot of ours decides and moves one turn">Step</button>
      <button id="play" class="primary">Play</button>
      <button id="reset" title="Back to the scenario's start">Reset</button>
      <button id="reroll" title="Draw other candidates">New candidates</button>
      <label class="muted">speed <input id="speed" type="range" min="0" max="8" value="3" style="width:80px"> <span id="speedLabel">10 turns/s</span></label>
      <span class="turn" id="turn">turn 0</span>
    </div>
    <div class="canvas-wrap">
      <canvas id="field" width="800" height="800" aria-label="Battlefield with the robots, bullets and the risk map"></canvas>
      <div id="tooltip"></div>
    </div>
    <div class="legend" id="legend">
      <span><i class="sw" style="background:var(--view)"></i>viewed robot</span>
      ${teamOnly('<span><i class="sw" style="background:var(--ours)"></i>teammate</span>')}
      <span><i class="sw" style="background:var(--enemy)"></i>enemy</span>
      <span><i class="ramp" id="rampSwatch"></i>risk: low &rarr; high (log, per view)</span>
      <span>&#9675; candidate &middot; numbered ring: one of the 8 checked against the waves, #1 last turn's destination while it competes (&#9633;), then the others with the least risk in order of risk &middot; &#9670; least risk (when the waves overrule it) &middot; <b style="color:var(--gold)">&#9733;</b> destination &middot; &#8853; field minimum: the safest point anywhere, if the robot could get there &middot; &#9633; kept destination &middot; grey dots: history</span>
    </div>
  </section>

  <section class="controls panel">
    <div class="card">
      <h2>Switches</h2>
      <div class="switches">
        ${teamOnly('<label><input type="checkbox" id="gang"> gang defence on</label>')}
        <label><input type="checkbox" id="lHeat" checked> risk map</label>
        <label><input type="checkbox" id="lCands" checked> candidates</label>
        <label><input type="checkbox" id="lDamage"> waves' damage map</label>
        <label><input type="checkbox" id="lHistory" checked> history &amp; trails</label>
        <label><input type="checkbox" id="lGuides" checked> ${TEAM ? 'radii &amp; pull' : 'radii'}</label>
      </div>
      <div class="row">
        <button id="addEnemy">+ enemy</button>
        ${teamOnly('<button id="addMate">+ teammate</button>')}
        <button id="addWave">+ enemy wave</button>
        ${teamOnly('<button id="addBullet">+ teammate bullet</button>')}
        <button id="clearMap" class="danger" title="Remove everything but the viewed robot">Clear map</button>
      </div>
      <div class="muted" style="font-size:12px">Remove an object: select it, then <b>Delete</b> or <i>remove</i> under <i>Selected</i>.</div>
    </div>

    <div class="card" id="decision"></div>

    <div class="card" id="inspector"></div>

    <div class="card">
      <details id="paramsCard">
        <summary id="paramsSummary">Parameters</summary>
        <div class="body">
          <div class="muted" style="font-size:12px">They change the formula live${teamOnly(' for every robot of ours')}. The scenario texts assume the bot's values (<code>MeleeMovementParameters.DEFAULT</code> and the constants in <code>MeleeMovement</code> / <code>EnemyWaves</code>). A blue edge marks a changed value; &#8634; resets it.</div>
          <div id="params"></div>
          <div class="row"><button id="paramsReset">Reset all to the bot's values</button></div>
        </div>
      </details>
    </div>
  </section>

  <aside class="explain panel">
    <div class="card">
      <div class="tabs" id="tabs"></div>
      <div id="paramChip" class="chip" style="display:none"></div>
      <div class="story" id="story"></div>
    </div>

    <div class="card">
      <details>
        <summary>How the movement decides (reference)</summary>
        <div class="body">${REFERENCE}</div>
      </details>
    </div>

    <div class="card">
      <details>
        <summary>What this page simplifies</summary>
        <div class="body">${SIMPLIFIES}</div>
      </details>
    </div>
  </aside>
</main>`;

// ---------- Scenarios ----------
// A page's scenarios: robots [id, kind ('ours' | 'enemy'), x, y, energy], y growing upwards as in Robocode; options
// per robot ({pinned}, {follow, keep}); waves {team, from, at, age, power, spread}; the robot viewed; the gang
// defence's switch; whether the waves' damage map starts on; the story beside the map.
const SCENARIOS = CFG.scenarios.concat([{
  id: 'sandbox', tab: 'Empty', title: 'Empty map: build your own', gang: false, view: 'me',
  robots: [['me', 'ours', CFG.field.w / 2, CFG.field.h / 2, 100]], options: {}, waves: [],
  story: `
<p>Only the viewed robot. With nobody around every point has zero risk, and the first candidate wins.</p>
<ul>
<li><b>Add</b> with the <i>+</i> buttons under <i>Switches</i>. New robots land on a free random spot; a wave starts at the enemy nearest to the viewed robot and aims at it${teamOnly('; a teammate\'s bullet flies from the nearest teammate at its nearest enemy')}.</li>
<li><b>Move</b> anything by dragging; a wave's aim by its handle${teamOnly(', a bullet\'s heading by its dot')}.</li>
<li><b>Remove</b> the selected object with <b>Delete</b> (or <i>remove</i> under <i>Selected</i>). <i>Clear map</i> empties any scenario but the viewed robot.</li>
<li><b>Change the formula</b> in <i>Parameters</i>: it applies at once, also in <i>Play</i>.</li>
</ul>`,
}]);

// ---------- State ----------
const FIELD = CFG.field;
const canvas = document.getElementById('field');
const ctx = canvas.getContext('2d');
const tooltip = document.getElementById('tooltip');
let scale = 0.5;
let state = null;
let scenario = null;
let viewId = 'me';
let focus = null;
let gangDefence = false;
let reroll = 0;
let playing = false;
/** Turns per second of Play by the speed slider's position (30: the Robocode GUI's default); turns owed so far. */
const SPEEDS = [1, 2, 5, 10, 20, 30, 60, 120, 240];
let owed = 0;
let dragging = null;
let hover = null;
/** The row of the candidates' table under the mouse (an index into the decision's waved candidates), or -1. */
let tableHighlight = -1;
let dirty = true;
let colors = {};
const layers = {heat: true, cands: true, damage: false, history: true, guides: true};

function loadScenario(sc) {
  scenario = sc;
  let seed = 1;
  const robots = sc.robots.map(([id, kind, x, y, energy]) => Object.assign(
    {id, kind, x, y, energy, history: [], seed: seed++, v: 0, trail: [], stats: []}, sc.options[id] || {}));
  const byId = id => robots.find(r => r.id === id);
  let k = 0;
  const waves = sc.waves.map(w => {
    const from = byId(w.from), at = byId(w.at);
    return {id: 'w' + (++k), team: w.team, x: from.x, y: from.y, age: w.age, power: w.power,
      heading: bearing(from.x, from.y, at.x, at.y), spread: w.spread || 0.08, by: w.from};
  });
  state = {robots, waves, turn: 0};
  viewId = sc.view;
  focus = {type: 'robot', obj: byId(sc.view)};
  gangDefence = TEAM && !!sc.gang;
  if (TEAM) document.getElementById('gang').checked = gangDefence;
  layers.damage = !!sc.damageLayer;
  document.getElementById('lDamage').checked = layers.damage;
  reroll = 0;
  document.getElementById('inspector').dataset.key = '';
  setPlaying(false);
  document.querySelectorAll('#tabs button').forEach(b => b.classList.toggle('active', b.dataset.id === sc.id));
  document.getElementById('story').innerHTML = `<h2 style="margin-top:10px">${sc.title}</h2>${sc.story}`;
  dirty = true;
}

const viewed = () => state.robots.find(r => r.id === viewId && !r.dead) || state.robots.find(r => r.kind === 'ours');

// ---------- Geometry on the canvas ----------
// The map: as high as the window allows and as wide as the columns beside it leave. The breakpoints are
// explorer.css's: three columns from 1400 px, two from 980 px, one below.
function resize() {
  const layout = document.querySelector('.layout');
  const stage = document.querySelector('.stage');
  const content = layout.clientWidth - 32;
  const w = window.innerWidth;
  const beside = w >= 1400 ? 16 + 380 + 16 + 300 : w >= 980 ? 16 + 380 : 0;
  const css = Math.max(260, Math.floor(beside ? Math.min(window.innerHeight - 130, content - beside) : content));
  stage.style.width = beside ? css + 'px' : '';
  const dpr = window.devicePixelRatio || 1;
  canvas.style.width = css + 'px';
  canvas.style.height = css + 'px';
  canvas.width = Math.round(css * dpr);
  canvas.height = Math.round(css * dpr);
  scale = canvas.width / FIELD.w;
  dirty = true;
}

const cx = x => x * scale;
const cy = y => (FIELD.h - y) * scale;
function eventWorld(ev) {
  const rect = canvas.getBoundingClientRect();
  const px = (ev.clientX - rect.left) * canvas.width / rect.width;
  const py = (ev.clientY - rect.top) * canvas.height / rect.height;
  return [px / scale, FIELD.h - py / scale];
}

function readColors() {
  const s = getComputedStyle(document.documentElement);
  for (const k of ['field', 'grid', 'wall', 'ink', 'muted', 'ours', 'view', 'enemy', 'gold', 'panel', 'line', 'accent']) {
    colors[k] = s.getPropertyValue('--' + k).trim();
  }
}

// Risk ramp: low (safe, blue) to high (red), on log(risk) between the 3rd and 97th percentile of the view.
const RAMP = [[0, [33, 84, 160]], [0.28, [64, 146, 190]], [0.52, [226, 226, 206]], [0.76, [242, 162, 82]],
  [1, [196, 46, 36]]];
function rampColor(t) {
  t = Math.max(0, Math.min(1, t));
  for (let i = 1; i < RAMP.length; i++) {
    if (t <= RAMP[i][0]) {
      const [t0, c0] = RAMP[i - 1], [t1, c1] = RAMP[i];
      const f = (t - t0) / (t1 - t0);
      return [0, 1, 2].map(j => Math.round(c0[j] + (c1[j] - c0[j]) * f));
    }
  }
  return RAMP[RAMP.length - 1][1];
}
document.getElementById('rampSwatch').style.background =
  `linear-gradient(90deg, ${RAMP.map(([t, c]) => `rgb(${c.join(',')}) ${t * 100}%`).join(', ')})`;

// ---------- Layers ----------
const GRID = 10;
const heatCanvas = document.createElement('canvas');
heatCanvas.width = FIELD.w / GRID;
heatCanvas.height = FIELD.h / GRID;

function computeHeat(me, dec) {
  const n = FIELD.w / GRID;
  const logs = new Float64Array(n * n);
  let min = null;
  for (let j = 0; j < n; j++) {
    for (let i = 0; i < n; i++) {
      const x = (i + 0.5) * GRID, y = FIELD.h - (j + 0.5) * GRID;
      const r = risk(me, x, y, dec.robots, dec.dist, dec.pull, false);
      logs[j * n + i] = Math.log(Math.max(r, 1e-30));
      if (inField(FIELD, x, y, C.WALL_MARGIN) && (!min || r < min[2])) min = [x, y, r];
    }
  }
  const sorted = Array.from(logs).sort((a, b) => a - b);
  const lo = sorted[Math.floor(sorted.length * 0.03)], hi = sorted[Math.floor(sorted.length * 0.97)];
  const g = heatCanvas.getContext('2d');
  const img = g.createImageData(n, n);
  for (let k = 0; k < n * n; k++) {
    const c = rampColor((logs[k] - lo) / Math.max(hi - lo, 1e-9));
    img.data[4 * k] = c[0];
    img.data[4 * k + 1] = c[1];
    img.data[4 * k + 2] = c[2];
    img.data[4 * k + 3] = 205;
  }
  g.putImageData(img, 0, 0);
  return {min, lo, hi};
}

// Only within this turn's reach: farther points are never candidates, and all those in one direction share the way.
function drawDamage(me, reach) {
  if (!state.waves.some(w => !w.passed)) return;
  const step = 16;
  ctx.fillStyle = colors.enemy;
  for (let x = step / 2; x < FIELD.w; x += step) {
    for (let y = step / 2; y < FIELD.h; y += step) {
      if (!inField(FIELD, x, y, C.WALL_MARGIN) || hyp(x - me.x, y - me.y) > reach + step / 2) continue;
      const d = wavesDamage(me, x, y, state.waves, state.robots).damage;
      if (d < 0.05) continue;
      ctx.globalAlpha = Math.min(0.9, 0.25 + d / 6);
      ctx.beginPath();
      ctx.arc(cx(x), cy(y), Math.min(step * 0.45, 1.5 + Math.sqrt(d) * 2.2) * scale, 0, 2 * Math.PI);
      ctx.fill();
    }
  }
  ctx.globalAlpha = 1;
}

function circle(x, y, r, stroke, dash, width) {
  ctx.beginPath();
  ctx.arc(cx(x), cy(y), r * scale, 0, 2 * Math.PI);
  ctx.strokeStyle = stroke;
  ctx.lineWidth = (width || 1.2) * Math.max(1, scale * 1.6);
  ctx.setLineDash(dash ? dash.map(v => v * scale * 1.6) : []);
  ctx.stroke();
  ctx.setLineDash([]);
}

function label(text, x, y, color, size, align, bold) {
  ctx.font = `${bold ? '600 ' : ''}${Math.round((size || 12) * scale * 1.6)}px system-ui, sans-serif`;
  ctx.fillStyle = color;
  ctx.textAlign = align || 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, cx(x), cy(y));
}

function roleName(role) {
  return ['', 'GANGED', 'HELPER', 'FINISHER'][role] || '';
}

// ---------- Render ----------
let lastDecision = null;
let lastHeat = null;

function render() {
  dirty = false;
  readColors();
  const me = viewed();
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = colors.field;
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  if (!me) {
    document.getElementById('decision').innerHTML =
      '<h2>Decision</h2><div class="muted">No robot of ours on the map: add one with <i>+ teammate</i>.</div>';
    renderInspector();
    tooltip.style.display = 'none';
    return;
  }
  // Show the decision the next Step makes: on every fifth turn the current position joins the history first (as in
  // stepTurn and in the bot). In place, since robotsFor and aimChance tell the robots apart by identity.
  const stored = me.history;
  me.history = historyAt(me, state.turn);
  try {
    renderView(me);
  } finally {
    me.history = stored;
  }
}

/** The viewed robot's decision of this turn, with its history as the decision uses it. */
function renderView(me) {
  const dec = decide(me, state.robots, state.waves, FIELD, gangDefence, seedFor(me, state.turn, reroll));
  lastDecision = dec;
  const heat = computeHeat(me, dec);
  lastHeat = heat;
  if (layers.heat) {
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(heatCanvas, 0, 0, canvas.width, canvas.height);
  }
  // Grid every 100 px.
  ctx.strokeStyle = colors.grid;
  ctx.lineWidth = 1;
  for (let v = 100; v < FIELD.w; v += 100) {
    ctx.beginPath();
    ctx.moveTo(cx(v), 0);
    ctx.lineTo(cx(v), canvas.height);
    ctx.moveTo(0, cy(v));
    ctx.lineTo(canvas.width, cy(v));
    ctx.stroke();
  }
  // Wall margin: no candidates there.
  ctx.fillStyle = colors.wall;
  const m = C.WALL_MARGIN * scale;
  ctx.fillRect(0, 0, canvas.width, m);
  ctx.fillRect(0, canvas.height - m, canvas.width, m);
  ctx.fillRect(0, m, m, canvas.height - 2 * m);
  ctx.fillRect(canvas.width - m, m, m, canvas.height - 2 * m);

  if (layers.damage) drawDamage(me, dec.reach);

  // Trails and the viewed robot's history.
  if (layers.history) {
    for (const r of state.robots) {
      if (r.trail.length < 2) continue;
      ctx.beginPath();
      ctx.moveTo(cx(r.trail[0][0]), cy(r.trail[0][1]));
      for (const p of r.trail) ctx.lineTo(cx(p[0]), cy(p[1]));
      ctx.strokeStyle = r.kind === 'enemy' ? colors.enemy : (r === me ? colors.view : colors.ours);
      ctx.globalAlpha = 0.45;
      ctx.lineWidth = 1.2 * Math.max(1, scale * 1.6);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    ctx.fillStyle = colors.muted;
    for (const p of me.history) {
      ctx.beginPath();
      ctx.arc(cx(p[0]), cy(p[1]), 3.5 * scale * 1.6, 0, 2 * Math.PI);
      ctx.fill();
    }
  }

  const ours = state.robots.filter(r => r.kind === 'ours' && !r.dead);
  const enemyList = state.robots.filter(r => r.kind === 'enemy' && !r.dead);
  if (layers.guides) {
    // Gang circles around our ganged robots.
    for (const r of TEAM ? ours : []) {
      const near = enemyList.filter(e => hyp(e.x - r.x, e.y - r.y) <= C.GANG_RADIUS).length;
      if (near >= C.GANG_SIZE) circle(r.x, r.y, C.GANG_RADIUS, colors.enemy, [6, 5], 1.2);
    }
    // Reach ring(s).
    if (dec.reach < C.MIN_REACH) {
      circle(me.x, me.y, dec.reach, colors.ink, [2, 4], 1);
    } else {
      circle(me.x, me.y, C.MIN_REACH, colors.ink, [2, 4], 1);
      circle(me.x, me.y, dec.reach, colors.ink, [2, 4], 1);
    }
    // Pull: line, floor and the balance radius 200/s.
    const pl = dec.pull;
    if (!Number.isNaN(pl.x)) {
      ctx.beginPath();
      ctx.moveTo(cx(me.x), cy(me.y));
      ctx.lineTo(cx(pl.x), cy(pl.y));
      ctx.strokeStyle = colors.gold;
      ctx.setLineDash([8 * scale * 1.6, 6 * scale * 1.6]);
      ctx.lineWidth = 1.6 * Math.max(1, scale * 1.6);
      ctx.stroke();
      ctx.setLineDash([]);
      if (pl.floor > 0) {
        circle(pl.x, pl.y, pl.floor, colors.gold, [3, 4], 1.4);
        label(`floor ${pl.floor}`, pl.x, pl.y + pl.floor + 14, colors.gold, 11);
      }
      // Where the pull balances a 1/d^k repulsion from the target alone: d = 100·k/s.
      const k = pl.target && pl.target.kind === 'enemy' ? P.distanceExponent : P.teammateExponent;
      const balance = 100 * k / pl.strength;
      if (Number.isFinite(balance) && balance > pl.floor + 1) {
        ctx.globalAlpha = 0.55;
        circle(pl.x, pl.y, balance, colors.gold, [1, 6], 1);
        label(`${+(100 * k).toFixed(1)}/s = ${balance.toFixed(0)}`, pl.x, pl.y - balance - 14, colors.gold, 11);
        ctx.globalAlpha = 1;
      }
    }
  }

  // Waves and teammates' bullets.
  for (const w of state.waves) {
    if (w.passed) continue;
    const speed = bulletSpeed(w.power);
    const r = speed * w.age;
    const col = w.team ? colors.ours : colors.enemy;
    if (w.team) {
      const bx = w.x + r * Math.cos(w.heading), by = w.y + r * Math.sin(w.heading);
      const ex = w.x + (r + speed * C.MELEE_HORIZON) * Math.cos(w.heading);
      const ey = w.y + (r + speed * C.MELEE_HORIZON) * Math.sin(w.heading);
      ctx.beginPath();
      ctx.moveTo(cx(w.x), cy(w.y));
      ctx.lineTo(cx(bx), cy(by));
      ctx.strokeStyle = col;
      ctx.globalAlpha = 0.35;
      ctx.lineWidth = 1.5 * Math.max(1, scale * 1.6);
      ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.beginPath();
      ctx.moveTo(cx(bx), cy(by));
      ctx.lineTo(cx(ex), cy(ey));
      ctx.setLineDash([6 * scale * 1.6, 5 * scale * 1.6]);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = col;
      ctx.beginPath();
      ctx.arc(cx(bx), cy(by), 5 * scale * 1.6, 0, 2 * Math.PI);
      ctx.fill();
      label(`${w.by}'s bullet p${w.power}`, bx, by - 22, col, 11);
    } else {
      ctx.globalAlpha = 0.5;
      circle(w.x, w.y, Math.max(r, 1), col, null, 1);
      ctx.globalAlpha = 1;
      // The aim's likely sector: ±2 sd.
      const a0 = w.heading - 2 * w.spread, a1 = w.heading + 2 * w.spread;
      ctx.beginPath();
      ctx.moveTo(cx(w.x), cy(w.y));
      ctx.arc(cx(w.x), cy(w.y), Math.max(r, 1) * scale, -a1, -a0);
      ctx.closePath();
      ctx.fillStyle = col;
      ctx.globalAlpha = 0.18;
      ctx.fill();
      ctx.globalAlpha = 1;
      ctx.beginPath();
      ctx.arc(cx(w.x), cy(w.y), Math.max(r, 1) * scale, -a1, -a0);
      ctx.strokeStyle = col;
      ctx.lineWidth = 3 * Math.max(1, scale * 1.6);
      ctx.stroke();
      // The aim line and its handle.
      const hx = w.x + (r + 40) * Math.cos(w.heading), hy = w.y + (r + 40) * Math.sin(w.heading);
      ctx.beginPath();
      ctx.moveTo(cx(w.x), cy(w.y));
      ctx.lineTo(cx(hx), cy(hy));
      ctx.lineWidth = 1 * Math.max(1, scale * 1.6);
      ctx.setLineDash([3 * scale * 1.6, 4 * scale * 1.6]);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.beginPath();
      ctx.arc(cx(hx), cy(hy), 6 * scale * 1.6, 0, 2 * Math.PI);
      ctx.fillStyle = colors.panel;
      ctx.fill();
      ctx.stroke();
      const chance = aimChance(me, w, state.robots);
      label(`${w.by} p${w.power} · chance ${chance.toFixed(2)}${chance < C.MIN_AIM_CHANCE ? ' (skipped)' : ''}`,
        hx, hy + 18, col, 11);
    }
    // The origin (the shooter's position at the shot).
    ctx.strokeStyle = col;
    ctx.lineWidth = 2 * Math.max(1, scale * 1.6);
    const o = 6 * scale * 1.6;
    ctx.beginPath();
    ctx.moveTo(cx(w.x) - o, cy(w.y) - o);
    ctx.lineTo(cx(w.x) + o, cy(w.y) + o);
    ctx.moveTo(cx(w.x) - o, cy(w.y) + o);
    ctx.lineTo(cx(w.x) + o, cy(w.y) - o);
    ctx.stroke();
  }

  // Candidates and markers.
  if (layers.cands) {
    const waved = new Set(dec.waved);
    for (const c of dec.candidates) {
      ctx.beginPath();
      ctx.arc(cx(c.x), cy(c.y), 3.2 * scale * 1.6, 0, 2 * Math.PI);
      ctx.fillStyle = colors.panel;
      ctx.fill();
      ctx.strokeStyle = colors.ink;
      ctx.lineWidth = 1;
      ctx.stroke();
      if (c.kept) {
        ctx.strokeRect(cx(c.x) - 6 * scale * 1.6, cy(c.y) - 6 * scale * 1.6, 12 * scale * 1.6, 12 * scale * 1.6);
      }
    }
    dec.waved.forEach((c, i) => {
      ctx.beginPath();
      ctx.arc(cx(c.x), cy(c.y), 8 * scale * 1.6, 0, 2 * Math.PI);
      ctx.strokeStyle = colors.ink;
      ctx.lineWidth = 1.5 * Math.max(1, scale * 1.6);
      ctx.stroke();
      label(String(i + 1), c.x + 15, c.y + 12, colors.ink, 10, 'center', true);
    });
    if (dec.riskBest && dec.riskBest !== dec.best) {
      const s = 9 * scale * 1.6;
      ctx.beginPath();
      ctx.moveTo(cx(dec.riskBest.x), cy(dec.riskBest.y) - s);
      ctx.lineTo(cx(dec.riskBest.x) + s, cy(dec.riskBest.y));
      ctx.lineTo(cx(dec.riskBest.x), cy(dec.riskBest.y) + s);
      ctx.lineTo(cx(dec.riskBest.x) - s, cy(dec.riskBest.y));
      ctx.closePath();
      ctx.strokeStyle = colors.ink;
      ctx.lineWidth = 2 * Math.max(1, scale * 1.6);
      ctx.stroke();
    }
  }
  if (heat.min && layers.heat) {
    const s = 9 * scale * 1.6;
    ctx.beginPath();
    ctx.arc(cx(heat.min[0]), cy(heat.min[1]), s, 0, 2 * Math.PI);
    ctx.moveTo(cx(heat.min[0]) - s, cy(heat.min[1]));
    ctx.lineTo(cx(heat.min[0]) + s, cy(heat.min[1]));
    ctx.moveTo(cx(heat.min[0]), cy(heat.min[1]) - s);
    ctx.lineTo(cx(heat.min[0]), cy(heat.min[1]) + s);
    ctx.strokeStyle = colors.ink;
    ctx.lineWidth = 1.5 * Math.max(1, scale * 1.6);
    ctx.stroke();
  }
  // The candidate under the mouse, or the one whose row in the table is under the mouse.
  const hc = hover && !dragging ? candidateAt(hover, dec) : null;
  const lit = hc || dec.waved[tableHighlight] || null;
  if (lit && layers.cands) {
    ctx.beginPath();
    ctx.arc(cx(lit.x), cy(lit.y), 13 * scale * 1.6, 0, 2 * Math.PI);
    ctx.strokeStyle = colors.accent;
    ctx.lineWidth = 3 * Math.max(1, scale * 1.6);
    ctx.stroke();
  }
  // The way to the destination and the star.
  if (dec.best && !Number.isNaN(dec.best.x)) {
    ctx.beginPath();
    ctx.moveTo(cx(me.x), cy(me.y));
    ctx.lineTo(cx(dec.best.x), cy(dec.best.y));
    ctx.strokeStyle = colors.gold;
    ctx.lineWidth = 2.5 * Math.max(1, scale * 1.6);
    ctx.stroke();
    star(dec.best.x, dec.best.y, 13);
  }

  // Robots.
  const roles = new Map();
  for (const r of ours) {
    const rb = robotsFor(r, state.robots);
    roles.set(r, pull(r, rb, gangDefence).role);
  }
  for (const r of state.robots) {
    if (r.dead) continue;
    const isView = r === me;
    const col = r.kind === 'enemy' ? colors.enemy : (isView ? colors.view : colors.ours);
    const s = C.HALF * scale;
    ctx.fillStyle = col;
    ctx.globalAlpha = TEAM && r.kind === 'ours' && r.energy < P.finishEnergy ? 0.55 : 1;
    roundRect(cx(r.x) - s, cy(r.y) - s, 2 * s, 2 * s, 5 * scale);
    ctx.fill();
    ctx.globalAlpha = 1;
    if (isView || (focus && focus.obj === r)) {
      ctx.strokeStyle = isView ? colors.ink : colors.gold;
      ctx.lineWidth = 2.5 * Math.max(1, scale * 1.6);
      roundRect(cx(r.x) - s - 3, cy(r.y) - s - 3, 2 * s + 6, 2 * s + 6, 7 * scale);
      ctx.stroke();
    }
    label(r.id, r.x, r.y, '#fff', 12, 'center', true);
    label(`${Math.round(r.energy)}${r.pinned ? ' · pinned' : ''}${r.follow ? ' → ' + r.follow : ''}`,
      r.x, r.y - C.HALF - 13, colors.ink, 11);
    const role = roles.get(r);
    if (role) label(roleName(role), r.x, r.y + C.HALF + 13, colors.gold, 11, 'center', true);
  }

  // Hover crosshair.
  if (hover && !dragging) {
    ctx.strokeStyle = colors.ink;
    ctx.globalAlpha = 0.6;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(cx(hover[0]), cy(hover[1]), 5 * scale * 1.6, 0, 2 * Math.PI);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }

  document.getElementById('turn').textContent = `turn ${state.turn}`;
  renderDecision(me, dec, heat, hc ? dec.waved.indexOf(hc) : tableHighlight);
  renderInspector();
  renderTooltip(hc);
}

/** The candidate within a few screen pixels of the world point p, or null. */
function candidateAt(p, dec) {
  const tol = 10 * FIELD.w / canvas.getBoundingClientRect().width;
  let found = null, best = tol;
  for (const c of dec.candidates) {
    const d = hyp(c.x - p[0], c.y - p[1]);
    if (d <= best) {
      best = d;
      found = c;
    }
  }
  return found;
}

function roundRect(x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function star(x, y, size) {
  const R = size * scale * 1.6, r = R * 0.45;
  ctx.beginPath();
  for (let i = 0; i < 10; i++) {
    const a = -Math.PI / 2 + i * Math.PI / 5;
    const rr = i % 2 ? r : R;
    const px = cx(x) + rr * Math.cos(a), py = cy(y) + rr * Math.sin(a);
    if (i) ctx.lineTo(px, py); else ctx.moveTo(px, py);
  }
  ctx.closePath();
  ctx.fillStyle = colors.gold;
  ctx.fill();
  ctx.strokeStyle = colors.ink;
  ctx.lineWidth = 1.2;
  ctx.stroke();
}

// ---------- Panel: decision ----------
const fmt = (v, d = 2) => Number.isFinite(v) ? v.toFixed(d) : '–';
const pct = v => `${v >= 0 ? '+' : ''}${(100 * v).toFixed(0)} %`;

/** One row per robot of a point's risk: its share of the sum and the parts of its term, largest first. */
function termRows(det) {
  const rows = det.terms.map(t => ({
    name: t.robot.id, enemy: t.enemy, value: t.value, share: t.value / det.sum, d: t.d,
    weight: t.enemy ? t.threat : P.teammateWeight,
    way: t.enemy ? 1 + P.perpendicularWeight * t.line : NaN,
    shields: t.enemy ? Math.pow(1 + t.closer, P.closerExponent) : NaN,
    detail: t.enemy
      ? `d ${t.d.toFixed(0)} · threat ${t.threat.toFixed(2)} · ×${(1 + P.perpendicularWeight * t.line).toFixed(2)} way · ÷${
        Math.pow(1 + t.closer, P.closerExponent).toFixed(2)} shields (${t.closer} closer)`
      : `d ${t.d.toFixed(0)} · teammate ${P.teammateWeight}/d^${P.teammateExponent}`,
  }));
  rows.sort((a, b) => b.value - a.value);
  return rows;
}

/** How clear the choice is: the runner-up's score against the winner's. */
function margin(dec) {
  const best = dec.best;
  const others = dec.waved.filter(c => c !== best);
  if (!best || !others.length) return '';
  const ru = others.reduce((a, b) => a.score <= b.score ? a : b);
  const gap = ru.score / best.score - 1;
  const kind = gap < 0.1 ? 'A near tie' : gap < 0.3 ? 'Close' : 'Clear';
  let text = `<b>${kind}:</b> the runner-up #${dec.waved.indexOf(ru) + 1} scores ${(100 * gap).toFixed(0)} % more than <b style="color:var(--gold)">&#9733;</b>.`;
  if (best.kept) {
    text += ' The winner is last turn\'s destination, its score already &times;0.9.';
  } else if (gap < 0.1) {
    text += ' Without the &times;0.9 a kept destination gets, the bot could switch between them from turn to turn.';
  }
  return `<div class="verdict">${text}</div>`;
}

function renderDecision(me, dec, heat, highlight) {
  const el = document.getElementById('decision');
  const pl = dec.pull;
  const target = pl.target ? pl.target.id : '';
  const roleText = {
    0: 'none: plain minimum risk',
    1: `ganged: drawn to the nearest teammate ${target}, s = ${P.teamPull}, no floor`,
    2: `helper: drawn to the ganged teammate ${target}, s = ${P.teamPull}, floor ${P.helpDistance} px`,
    3: `finisher: drawn to the last enemy ${target}, s = ${P.finishPull}, floor ${P.finishDistance} px`,
  }[pl.role];
  const best = dec.best;
  let html = `<h2>Decision of ${me.id}${teamOnly(' <span class="muted" style="font-weight:400">(click one of ours to switch)</span>')}</h2>`;
  html += teamOnly(`<div>Role: <span class="role">${roleName(pl.role) || 'NONE'}</span> <span class="muted">${roleText}</span></div>`);

  // The candidates' table: the kept destination first, then those with the least risk, numbered as on the map.
  const anyWaves = dec.waved.some(c => c.parts && c.parts.length);
  const keptIn = dec.waved.some(c => c.kept);
  html += keptIn
    ? `<h3>The kept destination and the ${dec.waved.length - 1} other candidates with the least risk</h3>`
    : `<h3>The ${dec.waved.length} candidates with the least risk</h3>`;
  html += anyWaves
    ? '<div class="tablewrap"><table class="cands"><tr><th>#</th><th title="the position\'s risk over the median risk of these candidates">risk</th><th title="the damage expected from the waves on the way there">dmg</th><th title="3 &times; dmg / max(0.1, median dmg)">+waves</th><th title="risk + waves (&times;0.9 for the kept destination)">=score</th><th title="the score against the destination\'s">vs &#9733;</th></tr>'
    : '<div class="tablewrap"><table class="cands"><tr><th>#</th><th title="the position\'s risk over the median risk of these candidates (&times;0.9 for the kept destination)">risk = score</th><th title="the score against the destination\'s">vs &#9733;</th></tr>';
  dec.waved.forEach((c, i) => {
    const vs = c === best ? '<b style="color:var(--gold)">&#9733;</b>' : pct(c.score / best.score - 1);
    const name = `${i + 1}${c.kept ? ' <span class="muted">kept</span>' : ''}${c === dec.riskBest && c !== best ? ' &#9670;' : ''}`;
    const cls = `${c === best ? 'win' : ''} ${i === highlight ? 'hl' : ''}`;
    html += anyWaves
      ? `<tr class="${cls}" data-i="${i}"><td>${name}</td><td>${fmt(c.riskRatio)}</td><td>${fmt(c.damage)}</td><td>${fmt(c.waveTerm)}</td><td>${fmt(c.score)}</td><td>${vs}</td></tr>`
      : `<tr class="${cls}" data-i="${i}"><td>${name}</td><td>${fmt(c.score)}</td><td>${vs}</td></tr>`;
  });
  html += '</table></div>';
  html += margin(dec);
  html += `<div class="muted" style="font-size:12px">${anyWaves
    ? `<b>risk:</b> the position's risk over the median of these ${dec.waved.length}; <b>dmg:</b> the waves' damage on the way there; <b>+waves:</b> 3 &times; dmg over the median dmg (${fmt(median(dec.waved.map(c => c.damage)))}, counted as at least 0.1: ${fmt(dec.waveMedian)}); <b>score</b> = risk + waves, &times;0.9 for the kept destination; the least wins.`
    : 'No wave can reach us, so the score is the position\'s risk alone (over the median of these candidates, &times;0.9 for the kept destination); the least wins.'}
    The other ${Math.max(0, dec.candidates.length - dec.waved.length)} candidates had more risk than these${keptIn ? ' (the kept destination aside)' : ''} and were not scored further. Hover a row to find its point, or a point to find its row.</div>`;

  // Why this destination.
  if (best && Number.isFinite(best.risk)) {
    const det = risk(me, best.x, best.y, dec.robots, dec.dist, dec.pull, true);
    const rows = termRows(det);
    html += `<h3>Why &#9733;: who makes its risk</h3><div class="bars">`;
    for (const r of rows) {
      html += `<div class="bar" title="${r.detail}"><span>${r.name}</span><span class="track"><span class="fill" style="display:block;width:${
        Math.max(1, 100 * r.share)}%;background:${r.enemy ? 'var(--enemy)' : 'var(--ours)'}"></span></span><span class="pct">${(100 * r.share).toFixed(1)} %</span></div>`;
    }
    const mateShare = rows.filter(r => !r.enemy).reduce((sum, r) => sum + r.share, 0);
    html += `</div><div class="muted" style="font-size:12px;margin-top:4px">${TEAM ? `Teammates ${(100 * mateShare).toFixed(0)} %, enemies ${
      (100 * (1 - mateShare)).toFixed(0)} % of the sum; the sum then &times; history ${fmt(det.history)}` : `The sum then &times; history ${fmt(det.history)}`}${
      TEAM ? ` &times; pull ${fmt(det.pullFactor, 3)}` : ''}. Hover a bar for its parts.</div>`;
    if (heat.min) {
      const far = hyp(heat.min[0] - me.x, heat.min[1] - me.y) > dec.reach;
      html += `<div class="muted" style="font-size:12px;margin-top:4px">&#8853; The field minimum (${heat.min[0].toFixed(0)}, ${heat.min[1].toFixed(0)}) has ${
        fmt(heat.min[2] / best.risk)}&times; the destination's risk${far ? ', beyond this turn\'s reach: the robot heads that way over several turns if nothing changes' : ''}.</div>`;
    }
  }

  // Distances, with medians over Play.
  const enemies = state.robots.filter(r => r.kind === 'enemy' && !r.dead);
  const mates = state.robots.filter(r => r.kind === 'ours' && !r.dead && r !== me);
  const dE = enemies.length ? Math.min(...enemies.map(e => hyp(e.x - me.x, e.y - me.y))) : NaN;
  const dM = mates.length ? Math.min(...mates.map(e => hyp(e.x - me.x, e.y - me.y))) : NaN;
  const dP = Number.isNaN(pl.x) ? NaN : hyp(pl.x - me.x, pl.y - me.y);
  const recent = me.stats.slice(-100);
  const medE = median(recent.map(st => st.e)), medM = median(recent.map(st => st.m));
  const pullStats = recent.filter(st => Number.isFinite(st.p));
  const medP = median(pullStats.map(st => st.p));
  html += `<h3>Distances</h3><div class="muted" style="font-size:12px">Reach ${dec.reach.toFixed(0)} px &middot; ${dec.candidates.length} candidates (the wall margin drops some)${
    dec.candidates.some(c => c.kept) ? ' &middot; last destination kept as a candidate (&times;0.9)' : ''}</div>`;
  html += `<table><tr><th></th><th>now</th><th>${recent.length ? `median, last ${recent.length} turns` : 'median in Play'}</th></tr>
    <tr><td>nearest enemy</td><td>${fmt(dE, 0)}</td><td>${fmt(medE, 0)}</td></tr>
    ${teamOnly(`<tr><td>nearest teammate</td><td>${fmt(dM, 0)}</td><td>${fmt(medM, 0)}</td></tr>
    <tr><td>pull target ${target}</td><td>${fmt(dP, 0)}</td><td>${pullStats.length ? fmt(medP, 0) : '–'}</td></tr>`)}</table>`;
  el.innerHTML = html;
}

// A row of the candidates' table under the mouse lights its point on the map.
document.getElementById('decision').addEventListener('mousemove', ev => {
  const tr = ev.target.closest('tr[data-i]');
  const i = tr ? Number(tr.dataset.i) : -1;
  if (i !== tableHighlight) {
    tableHighlight = i;
    dirty = true;
  }
});
document.getElementById('decision').addEventListener('mouseleave', () => {
  tableHighlight = -1;
  dirty = true;
});

// ---------- Panel: inspector ----------
function renderInspector() {
  const el = document.getElementById('inspector');
  // Rebuilt only when the selection changes: a rebuild in every frame would swallow clicks and slider drags.
  if (el.dataset.key === focusKey()) return;
  el.dataset.key = focusKey();
  if (!focus || !focus.obj || focus.obj.dead || focus.obj.passed) {
    el.innerHTML = `<h2>Selected</h2><div class="muted">Click a robot, a wave's origin (&times;) or its aim handle.</div>`;
    return;
  }
  const o = focus.obj;
  let html;
  if (focus.type === 'robot') {
    html = `<h2>${o.kind === 'enemy' ? 'Enemy' : 'Our robot'} ${o.id}</h2>
      <div class="row"><label>energy <input type="range" min="1" max="200" value="${o.energy}" data-k="energy"> <span class="mono" data-v="energy">${Math.round(o.energy)}</span></label></div>`;
    if (o.kind === 'ours') {
      html += `<div class="row"><label><input type="checkbox" data-k="pinned" ${o.pinned ? 'checked' : ''}> pinned (stands in Play)</label>
        <button data-a="view">view from ${o.id}</button></div>`;
    } else {
      const opts = state.robots.filter(r => r.kind === 'ours' && !r.dead)
        .map(r => `<option value="${r.id}" ${o.follow === r.id ? 'selected' : ''}>${r.id}</option>`).join('');
      html += `<div class="row"><label>in Play follows <select data-k="follow"><option value="">nobody (stands)</option>${opts}</select></label>
        <label>down to <input type="range" min="60" max="500" value="${o.keep || 150}" data-k="keep"> <span class="mono" data-v="keep">${o.keep || 150}</span> px</label></div>`;
    }
    html += `<div class="row"><button class="danger" data-a="delete">remove ${o.id}</button></div>`;
  } else {
    html = `<h2>${o.team ? 'Teammate bullet' : 'Enemy wave'} from ${o.by}</h2>
      <div class="row"><label>power <input type="range" min="0.1" max="3" step="0.1" value="${o.power}" data-k="power"> <span class="mono" data-v="power">${o.power}</span></label></div>
      <div class="row"><label>age (turns in the air) <input type="range" min="0" max="60" value="${o.age}" data-k="age"> <span class="mono" data-v="age">${o.age}</span></label></div>`;
    if (!o.team) {
      html += `<div class="row"><label>aim spread (sd, rad) <input type="range" min="0.01" max="0.4" step="0.01" value="${o.spread}" data-k="spread"> <span class="mono" data-v="spread">${o.spread}</span></label></div>
        <div class="row"><button data-a="aim">aim at ${viewed().id}</button></div>`;
    }
    html += `<div class="row"><button class="danger" data-a="delete">remove</button></div>`;
  }
  el.innerHTML = html;
}
function focusKey() {
  return focus && focus.obj && !focus.obj.dead && !focus.obj.passed && state.waves.concat(state.robots).includes(focus.obj)
    ? focus.obj.id + ':' + focus.type : 'none';
}

document.getElementById('inspector').addEventListener('input', ev => {
  const k = ev.target.dataset.k;
  if (!k || !focus) return;
  const o = focus.obj;
  if (ev.target.type === 'checkbox') o[k] = ev.target.checked;
  else if (ev.target.tagName === 'SELECT') o[k] = ev.target.value || null;
  else o[k] = Number(ev.target.value);
  const v = document.querySelector(`#inspector [data-v="${k}"]`);
  if (v) v.textContent = ev.target.value;
  dirty = true;
});
document.getElementById('inspector').addEventListener('click', ev => {
  const a = ev.target.dataset.a;
  if (!a || !focus) return;
  const o = focus.obj;
  if (a === 'delete') {
    removeFocused();
  } else if (a === 'view') {
    viewId = o.id;
  } else if (a === 'aim') {
    const me = viewed();
    o.heading = bearing(o.x, o.y, me.x, me.y);
  }
  document.getElementById('inspector').dataset.key = '';
  dirty = true;
});

function removeFocused() {
  const o = focus.obj;
  if (focus.type === 'robot') {
    state.robots = state.robots.filter(r => r !== o);
    for (const r of state.robots) if (r.follow === o.id) r.follow = null;
    if (o.id === viewId) {
      const next = state.robots.find(r => r.kind === 'ours');
      viewId = next ? next.id : null;
    }
  } else {
    state.waves = state.waves.filter(w => w !== o);
  }
  focus = null;
  document.getElementById('inspector').dataset.key = '';
  dirty = true;
}

document.addEventListener('keydown', ev => {
  if (ev.key !== 'Delete' && ev.key !== 'Backspace') return;
  const t = ev.target;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA')) return;
  if (!focus || !focus.obj) return;
  ev.preventDefault();
  removeFocused();
});

document.getElementById('clearMap').addEventListener('click', () => {
  const me = viewed();
  state.robots = me ? [me] : [];
  state.waves = [];
  if (me) Object.assign(me, {history: [], trail: [], stats: [], dest: null, v: 0});
  focus = me ? {type: 'robot', obj: me} : null;
  document.getElementById('inspector').dataset.key = '';
  dirty = true;
});

// ---------- Parameters ----------
// The bot's values, kept for the resets: P is MeleeMovementParameters.DEFAULT, C the constants of MeleeMovement and
// EnemyWaves.
const P_BOT = Object.assign({}, P);
const C_BOT = Object.assign({}, C);
const PARAM_GROUPS = [
  {title: 'Enemy term', note: 'threat · (1 + w·|cos a|) / (d<sup>k</sup> · (1+n)<sup>c</sup>)', items: [
    {obj: 'P', key: 'distanceExponent', label: 'distance exponent k', min: 0, max: 4, step: 0.1,
      help: 'an enemy\'s term falls with d^k; with 2, twice as far is a quarter'},
    {obj: 'P', key: 'minThreat', label: 'threat at least', min: 0, max: 2, step: 0.05,
      help: 'the enemy\'s energy over ours counts from this'},
    {obj: 'P', key: 'maxThreat', label: 'threat at most', min: 0.1, max: 5, step: 0.1, help: '... up to this'},
    {obj: 'P', key: 'perpendicularWeight', label: 'way weight w', min: 0, max: 4, step: 0.1,
      help: 'driving straight at or away from an enemy costs 1 + w times as much'},
    {obj: 'P', key: 'closerExponent', label: 'shield exponent c', min: 0, max: 3, step: 0.1,
      help: 'n robots closer to the enemy than the point divide its term by (1+n)^c'},
    {obj: 'P', key: 'closerCountsEnemies', type: 'bool',
      label: TEAM ? 'the enemy\'s teammates are shields too' : 'the other enemies are shields (else nobody)'},
  ]},
  {title: 'Teammates', team: true, note: 'w / d<sup>k</sup>', items: [
    {obj: 'P', key: 'teammateWeight', label: 'weight w', min: 0, max: 10, step: 0.1},
    {obj: 'P', key: 'teammateExponent', label: 'distance exponent k', min: 0, max: 4, step: 0.1, pageOnly: true,
      help: 'the bot has 2'},
  ]},
  {title: 'History', note: 'risk × (1 + &Sigma; w / d<sup>k</sup>) over the last N positions, one every M turns', items: [
    {obj: 'P', key: 'historyWeight', label: 'weight w', min: 0, max: 2000, step: 10},
    {obj: 'P', key: 'historyExponent', label: 'distance exponent k', min: 0, max: 4, step: 0.1, pageOnly: true,
      help: 'the bot has 2'},
    {obj: 'C', key: 'HISTORY_EVERY', label: 'one position every M turns', min: 1, max: 20, step: 1, int: true,
      minValue: 1},
    {obj: 'C', key: 'HISTORY_SIZE', label: 'positions kept N', min: 0, max: 60, step: 1, int: true, minValue: 0},
  ]},
  {title: 'Candidates', note: 'N points at a random distance between the two limits, at most the share &times; the closest enemy\'s distance', items: [
    {obj: 'C', key: 'CANDIDATES', label: 'candidates N', min: 1, max: 120, step: 1, int: true, minValue: 1},
    {obj: 'C', key: 'MIN_REACH', label: 'distance from', min: 0, max: 400, step: 5},
    {obj: 'C', key: 'MAX_REACH', label: 'distance to', min: 10, max: 600, step: 5},
    {obj: 'C', key: 'CLOSEST_SHARE', label: 'share of the closest enemy', min: 0.05, max: 2, step: 0.05},
    {obj: 'C', key: 'WALL_MARGIN', label: 'wall margin', min: 18, max: 200, step: 1},
    {obj: 'C', key: 'ARRIVED', label: 'arrived within', min: 0, max: 100, step: 1,
      help: 'the last destination stays a candidate until we are this near'},
    {obj: 'P', key: 'keep', label: 'last destination &times;', min: 0.5, max: 1.5, step: 0.01,
      help: 'its risk or score times this; under 1 it is favoured'},
  ]},
  {title: 'Waves', note: 'score = risk / median + weight &times; damage / max(floor, median damage), over the K candidates with the least risk', items: [
    {obj: 'P', key: 'waveCandidates', label: 'candidates checked K', min: 1, max: 60, step: 1, int: true, minValue: 1},
    {obj: 'P', key: 'waveWeight', label: 'weight', min: 0, max: 20, step: 0.1},
    {obj: 'P', key: 'minWaveMedian', label: 'median damage floor', min: 0.01, max: 10, step: 0.01},
    {obj: 'C', key: 'AIM_EXPONENT', label: 'aim chance exponent', min: 0, max: 4, step: 0.1,
      help: TEAM ? 'chance = 1/(1+n)^e, n: our teammates the shooter had closer than us'
        : 'chance = 1/(1+n)^e, n: the other robots the shooter had closer than us'},
    {obj: 'C', key: 'MIN_AIM_CHANCE', label: 'aim chance cut-off', min: 0, max: 1, step: 0.01},
    {obj: 'C', key: 'MELEE_HORIZON', label: 'prediction turns', min: 1, max: 90, step: 1, int: true, minValue: 1},
  ]},
  {title: 'Team pull', team: true, note: 'risk &times; e<sup>s&middot;&Delta;d/100</sup>, both distances clamped at the floor', items: [
    {obj: 'P', key: 'teamPull', label: 'gang pull s', min: 0, max: 3, step: 0.05,
      help: 'ganged robots and helpers; 0: no gang defence'},
    {obj: 'P', key: 'helpDistance', label: 'helper floor', min: 0, max: 800, step: 10},
    {obj: 'C', key: 'GANG_SIZE', label: 'ganged with enemies', min: 1, max: 6, step: 1, int: true, minValue: 1,
      help: 'this many enemies within the gang radius'},
    {obj: 'C', key: 'GANG_RADIUS', label: 'gang radius', min: 50, max: 700, step: 10},
    {obj: 'P', key: 'finishPull', label: 'finisher pull s', min: 0, max: 3, step: 0.05},
    {obj: 'P', key: 'finishDistance', label: 'finisher floor', min: 0, max: 800, step: 10, help: '0: no finisher'},
    {obj: 'P', key: 'finishEnergy', label: 'finisher energy at least', min: 0, max: 200, step: 1},
  ]},
];
// The melee page shows no teammates' or team pull's parameters.
for (let g = PARAM_GROUPS.length - 1; g >= 0; g--) if (PARAM_GROUPS[g].team && !TEAM) PARAM_GROUPS.splice(g, 1);
const paramStore = it => it.obj === 'P' ? P : C;
const paramBot = it => (it.obj === 'P' ? P_BOT : C_BOT)[it.key];
const paramItem = id => {
  const [g, i] = id.split('-').map(Number);
  return PARAM_GROUPS[g].items[i];
};

function buildParams() {
  let html = '';
  PARAM_GROUPS.forEach((g, gi) => {
    html += `<div class="pgroup"><h3>${g.title}</h3><div class="pnote">${g.note}</div>`;
    g.items.forEach((it, ii) => {
      const id = `${gi}-${ii}`;
      const v = paramStore(it)[it.key];
      const tip = `${it.help ? it.help + ' · ' : ''}the bot: ${paramBot(it)}`;
      const name = `<span class="pname">${it.label}${it.pageOnly ? ' <small>(page only)</small>' : ''}</span>`;
      const reset = `<button data-reset="${id}" title="back to ${paramBot(it)}">&#8634;</button>`;
      html += it.type === 'bool'
        ? `<div class="prow" data-row="${id}" title="${tip}">${name}<span></span><input type="checkbox" data-p="${id}" ${v ? 'checked' : ''}>${reset}</div>`
        : `<div class="prow" data-row="${id}" title="${tip}">${name}<input type="range" data-p="${id}" min="${it.min}" max="${it.max}" step="${it.step}" value="${v}"><input type="number" data-p="${id}" step="${it.step}" value="${v}">${reset}</div>`;
    });
    html += '</div>';
  });
  document.getElementById('params').innerHTML = html;
  refreshParams();
}

function showParam(id) {
  const it = paramItem(id);
  const v = paramStore(it)[it.key];
  for (const inp of document.querySelectorAll(`#params input[data-p="${id}"]`)) {
    if (inp.type === 'checkbox') inp.checked = v;
    else inp.value = v;
  }
}

function refreshParams() {
  let changed = 0;
  PARAM_GROUPS.forEach((g, gi) => g.items.forEach((it, ii) => {
    const differs = paramStore(it)[it.key] !== paramBot(it);
    changed += differs ? 1 : 0;
    document.querySelector(`#params [data-row="${gi}-${ii}"]`).classList.toggle('changed', differs);
  }));
  document.getElementById('paramsSummary').textContent = changed ? `Parameters · ${changed} changed` : 'Parameters';
  const chip = document.getElementById('paramChip');
  chip.style.display = changed ? 'inline-block' : 'none';
  chip.textContent = `${changed} parameter${changed > 1 ? 's differ' : ' differs'} from the bot's; the texts assume the bot's values`;
}

function onParam(ev) {
  const id = ev.target.dataset.p;
  if (!id) return;
  // A number field applies when it is left or confirmed, not on every keystroke.
  if (ev.type === 'input' && ev.target.type === 'number') return;
  const it = paramItem(id);
  let v;
  if (it.type === 'bool') {
    v = ev.target.checked;
  } else {
    v = Number(ev.target.value);
    if (ev.target.value === '' || !Number.isFinite(v)) {
      showParam(id);
      return;
    }
    if (it.int) v = Math.round(v);
    if (it.minValue !== undefined) v = Math.max(it.minValue, v);
  }
  paramStore(it)[it.key] = v;
  showParam(id);
  refreshParams();
  dirty = true;
}
document.getElementById('params').addEventListener('input', onParam);
document.getElementById('params').addEventListener('change', onParam);
document.getElementById('params').addEventListener('click', ev => {
  const id = ev.target.dataset.reset;
  if (!id) return;
  const it = paramItem(id);
  paramStore(it)[it.key] = paramBot(it);
  showParam(id);
  refreshParams();
  dirty = true;
});
document.getElementById('paramsReset').addEventListener('click', () => {
  Object.assign(P, P_BOT);
  Object.assign(C, C_BOT);
  PARAM_GROUPS.forEach((g, gi) => g.items.forEach((it, ii) => showParam(`${gi}-${ii}`)));
  refreshParams();
  dirty = true;
});
buildParams();

// ---------- Tooltip ----------
function renderTooltip(hc) {
  const me = viewed();
  if (!hover || dragging || !lastDecision || !me) {
    tooltip.style.display = 'none';
    return;
  }
  const dec = lastDecision;
  const [x, y] = hc ? [hc.x, hc.y] : hover;
  const det = risk(me, x, y, dec.robots, dec.dist, dec.pull, true);
  const best = dec.best && Number.isFinite(dec.best.risk) ? dec.best : null;
  let html;
  if (hc) {
    const i = dec.waved.indexOf(hc);
    if (hc === best) {
      html = `<div class="tt-head">Candidate #${i + 1}: the destination <b style="color:var(--gold)">&#9733;</b></div>`;
    } else if (i >= 0) {
      html = `<div class="tt-head">Candidate #${i + 1}: score ${fmt(hc.score)}, ${pct(hc.score / best.score - 1)} against <b style="color:var(--gold)">&#9733;</b></div>`;
    } else {
      // Ranked as the wave stage takes them: the kept destination has its place whatever its risk.
      const others = dec.candidates.filter(c => !c.kept).sort((a, b) => a.risk - b.risk);
      const kept = dec.candidates.length - others.length;
      html = `<div class="tt-head">Candidate, ${others.indexOf(hc) + 1}. least risk of the ${others.length}${kept ? ' besides the kept destination' : ''}: not among the ${dec.waved.length} scored against the waves</div>`;
    }
  } else {
    html = '<div class="tt-head">Point under the mouse</div>';
  }
  if (best) {
    html += `<div>Risk here: <b>${(det.value / best.risk).toFixed(2)}&times;</b> the destination's <span class="muted">(less is safer)</span></div>`;
  }
  const rows = termRows(det);
  const shown = rows.filter((r, k) => k < 8 && r.share >= 0.01);
  html += `<div class="muted" style="margin-top:4px">Who makes it, as a share of the sum:</div>
    <table><tr><th>robot</th><th>share</th><th>dist</th><th>weight</th><th>&times;way</th><th>&divide;shields</th></tr>`;
  for (const r of shown) {
    html += `<tr><td><span style="color:${r.enemy ? 'var(--enemy)' : 'var(--ours)'}">&#9632;</span> ${r.name}</td><td>${(100 * r.share).toFixed(0)} %</td><td>${
      r.d.toFixed(0)}</td><td>${r.enemy ? fmt(r.weight) : `${r.weight} mate`}</td><td>${r.enemy ? fmt(r.way) : ''}</td><td>${r.enemy ? fmt(r.shields, 0) : ''}</td></tr>`;
  }
  html += '</table>';
  if (rows.length > shown.length) {
    const rest = rows.slice(shown.length).reduce((sum, r) => sum + r.share, 0);
    html += `<div class="muted">${rows.length - shown.length} more: ${(100 * rest).toFixed(0)} %</div>`;
  }
  html += `<div class="muted">The sum then &times; history ${det.history.toFixed(2)}${TEAM ? ` &times; pull ${det.pullFactor.toFixed(3)}` : ''}</div>`;
  const wd = wavesDamage(me, x, y, state.waves, state.robots);
  if (wd.parts.length) {
    html += `<div><b>Waves on the way:</b> ${wd.damage.toFixed(2)} damage <span class="muted">(${
      wd.parts.map(p => `${p.wave.by}${p.wave.team ? '\'s bullet' : `, chance ${p.chance.toFixed(2)}`}: ${p.damage.toFixed(2)}`).join('; ')})</span></div>`;
  }
  const dist = hyp(x - me.x, y - me.y);
  if (!inField(FIELD, x, y, C.WALL_MARGIN)) {
    html += `<div class="muted">Within ${C.WALL_MARGIN} px of a wall: never a candidate.</div>`;
  } else if (!hc && dist > dec.reach + 1) {
    html += `<div class="muted">Beyond this turn's reach (${dec.reach.toFixed(0)} px): not a candidate now.</div>`;
  }
  tooltip.innerHTML = html;
  tooltip.style.display = 'block';
  const rect = canvas.getBoundingClientRect();
  const px = cx(hover[0]) * rect.width / canvas.width, py = cy(hover[1]) * rect.height / canvas.height;
  const tw = tooltip.offsetWidth, th = tooltip.offsetHeight;
  tooltip.style.left = Math.max(0, Math.min(rect.width - tw - 4, px + 14)) + 'px';
  tooltip.style.top = (py + 14 + th > rect.height ? Math.max(0, py - th - 10) : py + 14) + 'px';
}

// ---------- Interaction ----------
function hitTest(x, y) {
  const tol = 12 * FIELD.w / canvas.getBoundingClientRect().width;
  for (const w of state.waves) {
    if (w.passed || w.team) continue;
    const r = bulletSpeed(w.power) * w.age;
    const hx = w.x + (r + 40) * Math.cos(w.heading), hy = w.y + (r + 40) * Math.sin(w.heading);
    if (hyp(hx - x, hy - y) < tol) return {type: 'aim', obj: w};
  }
  for (const w of state.waves) {
    if (!w.passed && w.team) {
      const r = bulletSpeed(w.power) * w.age;
      const bx = w.x + r * Math.cos(w.heading), by = w.y + r * Math.sin(w.heading);
      if (hyp(bx - x, by - y) < tol) return {type: 'aim', obj: w};
    }
  }
  for (const r of state.robots) {
    if (!r.dead && Math.abs(r.x - x) <= C.HALF + 4 && Math.abs(r.y - y) <= C.HALF + 4) return {type: 'robot', obj: r};
  }
  for (const w of state.waves) {
    if (!w.passed && hyp(w.x - x, w.y - y) < tol) return {type: 'origin', obj: w};
  }
  return null;
}

canvas.addEventListener('pointerdown', ev => {
  const [x, y] = eventWorld(ev);
  const hit = hitTest(x, y);
  if (!hit) return;
  canvas.setPointerCapture(ev.pointerId);
  dragging = hit;
  if (hit.type === 'robot') {
    focus = {type: 'robot', obj: hit.obj};
    if (hit.obj.kind === 'ours') viewId = hit.obj.id;
  } else {
    focus = {type: 'wave', obj: hit.obj};
  }
  document.getElementById('inspector').dataset.key = '';
  dirty = true;
});
canvas.addEventListener('pointermove', ev => {
  const [x, y] = eventWorld(ev);
  hover = [x, y];
  if (dragging) {
    const o = dragging.obj;
    const clampX = v => Math.max(C.HALF, Math.min(FIELD.w - C.HALF, v));
    const clampY = v => Math.max(C.HALF, Math.min(FIELD.h - C.HALF, v));
    if (dragging.type === 'robot' || dragging.type === 'origin') {
      o.x = clampX(x);
      o.y = clampY(y);
      if (dragging.type === 'robot') o.trail = [];
    } else if (dragging.type === 'aim') {
      o.heading = bearing(o.x, o.y, x, y);
    }
  }
  canvas.style.cursor = dragging ? 'grabbing' : (hitTest(x, y) ? 'grab' : 'crosshair');
  dirty = true;
});
canvas.addEventListener('pointerup', () => { dragging = null; dirty = true; });
canvas.addEventListener('pointerleave', () => { hover = null; dirty = true; });

function setPlaying(on) {
  playing = on;
  // The first turn at once, even at 1 turn/s.
  owed = on ? 1 : 0;
  const b = document.getElementById('play');
  b.textContent = on ? 'Pause' : 'Play';
}

function simTurn() {
  const decisions = stepTurn(state, FIELD, gangDefence, reroll);
  for (const [r, dec] of decisions) {
    const enemies = state.robots.filter(e => e.kind === 'enemy' && !e.dead);
    const mates = state.robots.filter(e => e.kind === 'ours' && !e.dead && e !== r);
    const pl = dec.pull;
    r.stats.push({
      e: enemies.length ? Math.min(...enemies.map(e => hyp(e.x - r.x, e.y - r.y))) : NaN,
      m: mates.length ? Math.min(...mates.map(e => hyp(e.x - r.x, e.y - r.y))) : NaN,
      p: Number.isNaN(pl.x) || !pl.target ? NaN : hyp(pl.target.x - r.x, pl.target.y - r.y),
    });
    if (r.stats.length > 400) r.stats.shift();
  }
  state.waves = state.waves.filter(w => !w.passed);
}

document.getElementById('step').addEventListener('click', () => { setPlaying(false); simTurn(); dirty = true; });
document.getElementById('play').addEventListener('click', () => setPlaying(!playing));
document.getElementById('reset').addEventListener('click', () => loadScenario(scenario));
document.getElementById('reroll').addEventListener('click', () => { reroll++; dirty = true; });
if (TEAM) document.getElementById('gang').addEventListener('change', ev => { gangDefence = ev.target.checked; dirty = true; });
for (const [id, key] of [['lHeat', 'heat'], ['lCands', 'cands'], ['lDamage', 'damage'], ['lHistory', 'history'],
  ['lGuides', 'guides']]) {
  document.getElementById(id).addEventListener('change', ev => { layers[key] = ev.target.checked; dirty = true; });
}

let added = 0;
function freeSpot() {
  for (let i = 0; i < 200; i++) {
    const x = 100 + Math.random() * (FIELD.w - 200), y = 100 + Math.random() * (FIELD.h - 200);
    if (state.robots.every(r => hyp(r.x - x, r.y - y) > 120)) return [x, y];
  }
  return [FIELD.w / 2, FIELD.h / 2];
}
document.getElementById('addEnemy').addEventListener('click', () => {
  const [x, y] = freeSpot();
  const r = {id: 'X' + (++added), kind: 'enemy', x, y, energy: 100, history: [], seed: 50 + added, v: 0, trail: [], stats: []};
  state.robots.push(r);
  focus = {type: 'robot', obj: r};
  dirty = true;
});
if (TEAM) document.getElementById('addMate').addEventListener('click', () => {
  const [x, y] = freeSpot();
  const r = {id: 'T' + (++added), kind: 'ours', x, y, energy: 100, history: [], seed: 50 + added, v: 0, trail: [], stats: []};
  state.robots.push(r);
  focus = {type: 'robot', obj: r};
  dirty = true;
});
document.getElementById('addWave').addEventListener('click', () => {
  const me = viewed();
  const enemies = state.robots.filter(r => r.kind === 'enemy' && !r.dead);
  if (!me || !enemies.length) return;
  const e = enemies.reduce((a, b) => hyp(a.x - me.x, a.y - me.y) < hyp(b.x - me.x, b.y - me.y) ? a : b);
  const w = {id: 'w' + (++added), team: false, x: e.x, y: e.y, age: 6, power: 2, heading: bearing(e.x, e.y, me.x, me.y),
    spread: 0.08, by: e.id};
  state.waves.push(w);
  focus = {type: 'wave', obj: w};
  dirty = true;
});
if (TEAM) document.getElementById('addBullet').addEventListener('click', () => {
  const me = viewed();
  const mates = state.robots.filter(r => r.kind === 'ours' && !r.dead && r !== me);
  const enemies = state.robots.filter(r => r.kind === 'enemy' && !r.dead);
  if (!me || !mates.length || !enemies.length) return;
  const t = mates.reduce((a, b) => hyp(a.x - me.x, a.y - me.y) < hyp(b.x - me.x, b.y - me.y) ? a : b);
  const e = enemies.reduce((a, b) => hyp(a.x - t.x, a.y - t.y) < hyp(b.x - t.x, b.y - t.y) ? a : b);
  const w = {id: 'w' + (++added), team: true, x: t.x, y: t.y, age: 4, power: 2.5, heading: bearing(t.x, t.y, e.x, e.y),
    spread: 0, by: t.id};
  state.waves.push(w);
  focus = {type: 'wave', obj: w};
  dirty = true;
});

// ---------- Loop ----------
const tabs = document.getElementById('tabs');
for (const sc of SCENARIOS) {
  const b = document.createElement('button');
  b.textContent = sc.tab;
  b.dataset.id = sc.id;
  b.addEventListener('click', () => { history.replaceState(null, '', '#' + sc.id); loadScenario(sc); });
  tabs.appendChild(b);
}

let lastFrame = null;
function loop(now) {
  if (playing) {
    const tps = SPEEDS[Number(document.getElementById('speed').value)];
    // A hidden tab gets no frames: do not catch up on the time it was away.
    owed += lastFrame === null ? 0 : Math.min(now - lastFrame, 250) / 1000 * tps;
    const turns = Math.floor(owed);
    owed -= turns;
    for (let i = 0; i < turns; i++) simTurn();
    if (turns > 0) dirty = true;
  }
  lastFrame = now;
  if (dirty) render();
  requestAnimationFrame(loop);
}
document.getElementById('speed').addEventListener('input', ev => {
  document.getElementById('speedLabel').textContent = `${SPEEDS[Number(ev.target.value)]} turns/s`;
});
window.addEventListener('resize', resize);
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { dirty = true; });
function fromHash() {
  const [id, turns] = location.hash.slice(1).split(':');
  const sc = SCENARIOS.find(s => s.id === id) || SCENARIOS[0];
  loadScenario(sc);
  for (let i = 0; i < (Number(turns) || 0); i++) simTurn();
  dirty = true;
}
resize();
fromHash();
requestAnimationFrame(loop);
