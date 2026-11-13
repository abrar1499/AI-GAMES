/*
 * Bolta Munshi - the screen: a person made of neurons.
 *
 * A head and shoulders seen from the front, filled with neurons. Each neuron
 * has a cell body, branching dendrites and axons that end in small bulbs
 * beside other cells. Signals run along the axons; a cell that receives one
 * lights up and may fire in turn, so activity spreads through the figure.
 * The eyes blink and the mouth moves with the voice. How busy it is follows the call: quiet when idle, steady while
 * listening (green), bursting with the voice while speaking (amber).
 *
 * Used by the web page and, inside a WebView, by the Android app.
 */
(function (root) {
  'use strict';

  var TAU = Math.PI * 2;
  var COLORS = { idle: [95, 113, 134], listening: [53, 211, 154], speaking: [245, 179, 77] };
  var ENERGY = { idle: 0.07, listening: 0.5, speaking: 1 };

  function rand(seed) { var s = seed; return function () { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; }

  // Where the face's features sit, in the same -1..1 box as the head.
  var EYE_X = 0.205, EYE_Y = -0.13, EYE_W = 0.125, EYE_H = 0.058, MOUTH_Y = 0.335, MOUTH_W = 0.2;

  /** Half the head's width at height y: a rounded skull narrowing to the chin. */
  function headHalf(y) {
    if (y < -0.82 || y > 0.6) return 0;
    if (y < -0.1) return 0.52 * Math.sqrt(1 - Math.pow((y + 0.1) / 0.72, 2));
    var w = 0.52 - 0.28 * Math.pow((y + 0.1) / 0.7, 1.8);
    return y > 0.44 ? w * Math.sqrt(Math.max(0, 1 - Math.pow((y - 0.44) / 0.16, 2))) : w;
  }
  /** A person seen from the front: head, ears, neck and shoulders. */
  function inBody(x, y) {
    var ax = Math.abs(x);
    if (ax < headHalf(y)) return true;
    if (Math.pow((ax - 0.545) / 0.065, 2) + Math.pow((y + 0.05) / 0.14, 2) < 1) return true;      // ears
    if (y > 0.5 && y < 0.84 && ax < 0.17) return true;                                           // neck
    return y >= 0.78 && y <= 1 && ax < Math.min(0.96, 0.17 + Math.pow((y - 0.78) / 0.2, 0.6) * 0.8);  // shoulders
  }
  /** The same person with the eyes and mouth left empty, so the neurons keep clear of them. */
  function inBrain(x, y) {
    if (!inBody(x, y)) return false;
    if (Math.pow((Math.abs(x) - EYE_X) / (EYE_W + 0.03), 2) + Math.pow((y - EYE_Y) / (EYE_H + 0.035), 2) < 1) return false;
    return !(Math.pow(x / (MOUTH_W + 0.04), 2) + Math.pow((y - MOUTH_Y) / 0.085, 2) < 1);
  }

  // ------------------------------------------------------------ anatomy ---

  /** Lay out the cells once. Everything is in brain units; drawing scales it. */
  function grow() {
    var r = rand(12), cells = [], guard = 0;
    while (cells.length < 64 && guard++ < 60000) {
      var x = r() * 2 - 1, y = r() * 2 - 1;
      if (!inBrain(x, y)) continue;
      if (cells.some(function (c) { return Math.hypot(c.x - x, c.y - y) < 0.15; })) continue;
      cells.push({ x: x, y: y, size: 0.75 + r() * 0.6, twigs: [], axons: [], flash: 0, rest: 0, fireAt: -1 });
    }

    // dendrites: a few trunks per cell, each forking twice, thinning as they go
    function twig(cell, x, y, angle, len, width, depth) {
      var bend = (r() - 0.5) * 0.9, a2 = angle + bend;
      var ex = x + Math.cos(a2) * len, ey = y + Math.sin(a2) * len;
      if (!inBrain(ex, ey)) { len *= 0.5; ex = x + Math.cos(a2) * len; ey = y + Math.sin(a2) * len; if (!inBrain(ex, ey)) return; }
      var mx = x + Math.cos(angle) * len * 0.55, my = y + Math.sin(angle) * len * 0.55;
      cell.twigs.push([x, y, mx, my, ex, ey, width]);
      if (depth <= 0) return;
      var forks = r() < 0.75 ? 2 : 1;
      for (var k = 0; k < forks; k++) twig(cell, ex, ey, a2 + (k ? -1 : 1) * (0.35 + r() * 0.5), len * (0.62 + r() * 0.2), width * 0.62, depth - 1);
    }
    cells.forEach(function (c) {
      var trunks = 4 + Math.floor(r() * 3), start = r() * TAU;
      for (var k = 0; k < trunks; k++) twig(c, c.x, c.y, start + k / trunks * TAU + (r() - 0.5) * 0.5, 0.055 + r() * 0.035, 1.5, 2);
    });

    // axons: each cell reaches two others a little way off, ending in a bulb beside them
    cells.forEach(function (c, i) {
      var near = cells.map(function (o, j) { return { j: j, d: Math.hypot(o.x - c.x, o.y - c.y) }; })
        .filter(function (o) { return o.j !== i; }).sort(function (p, q) { return p.d - q.d; });
      [near[1 + Math.floor(r() * 2)], near[3 + Math.floor(r() * 4)]].forEach(function (pick) {
        if (!pick) return;
        var t = cells[pick.j], dx = t.x - c.x, dy = t.y - c.y, d = Math.hypot(dx, dy), side = (r() - 0.5) * 0.5;
        var ex = t.x - dx / d * 0.03, ey = t.y - dy / d * 0.03;                 // stop just short: the synapse
        c.axons.push({ to: pick.j, x1: c.x, y1: c.y, cx: (c.x + ex) / 2 - dy * side, cy: (c.y + ey) / 2 + dx * side, x2: ex, y2: ey, len: d });
      });
    });

    // outline of the person, as points along the edge of the body's shape
    var rim = [], step = 0.014;
    for (var gx = -1; gx < 1; gx += step) {
      for (var gy = -1; gy < 1; gy += step) {
        var a = inBody(gx, gy);
        if (a !== inBody(gx + step, gy) || a !== inBody(gx, gy + step)) rim.push([gx + step / 2, gy + step / 2]);
      }
    }
    return { cells: cells, rim: rim };
  }

  // ------------------------------------------------------------- screen ---

  function Visual(canvas) {
    var ctx = canvas.getContext('2d');
    var anatomy = grow(), cells = anatomy.cells, signals = [];
    var state = 'idle', col = COLORS.idle.slice(), energy = ENERGY.idle;
    var level = 0, levelAt = -1e9;                      // loudness handed in by the app, when it has one
    var base = document.createElement('canvas'), tinted = document.createElement('canvas'), tintKey = '';
    var W = 0, H = 0, dpr = 1, S = 1, CX = 0, CY = 0, clock = 0, last = 0, running = false;
    var blinkAt = 2.5, mouth = 0;
    var still = root.matchMedia && root.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function X(x) { return CX + x * S; }
    function Y(y) { return CY + y * S; }
    function rgba(a, lift) {
      var l = lift || 0;
      return 'rgba(' + Math.round(col[0] + (255 - col[0]) * l) + ',' + Math.round(col[1] + (255 - col[1]) * l) + ',' + Math.round(col[2] + (255 - col[2]) * l) + ',' + Math.max(0, Math.min(1, a)) + ')';
    }

    /** The parts that never move are drawn once, in white, and tinted each frame. */
    function paintBase() {
      base.width = tinted.width = canvas.width; base.height = tinted.height = canvas.height; tintKey = '';
      var b = base.getContext('2d');
      b.setTransform(dpr, 0, 0, dpr, 0, 0);
      b.clearRect(0, 0, W, H);
      b.lineCap = 'round';
      b.fillStyle = 'rgba(255,255,255,0.30)';
      anatomy.rim.forEach(function (p) { b.beginPath(); b.arc(X(p[0]), Y(p[1]), Math.max(0.6, S * 0.0045), 0, TAU); b.fill(); });
      cells.forEach(function (c) {
        c.axons.forEach(function (a) {
          b.strokeStyle = 'rgba(255,255,255,0.34)'; b.lineWidth = Math.max(0.6, S * 0.0042);
          b.beginPath(); b.moveTo(X(a.x1), Y(a.y1)); b.quadraticCurveTo(X(a.cx), Y(a.cy), X(a.x2), Y(a.y2)); b.stroke();
          b.fillStyle = 'rgba(255,255,255,0.7)'; b.beginPath(); b.arc(X(a.x2), Y(a.y2), Math.max(0.9, S * 0.0075), 0, TAU); b.fill();
        });
        c.twigs.forEach(function (t) {
          b.strokeStyle = 'rgba(255,255,255,0.5)'; b.lineWidth = Math.max(0.5, S * 0.0052 * t[6]);
          b.beginPath(); b.moveTo(X(t[0]), Y(t[1])); b.quadraticCurveTo(X(t[2]), Y(t[3]), X(t[4]), Y(t[5])); b.stroke();
        });
      });
    }

    function resize() {
      var box = canvas.getBoundingClientRect();
      var nd = Math.min(2, root.devicePixelRatio || 1), nw = Math.max(1, Math.round(box.width * nd)), nh = Math.max(1, Math.round(box.height * nd));
      if (nw === canvas.width && nh === canvas.height && nd === dpr) return;
      dpr = nd; canvas.width = nw; canvas.height = nh; W = box.width; H = box.height;
      S = Math.min(W * 0.94 / 1.3, H * 0.66 / 1.82); CX = W / 2; CY = H / 2 - S * 0.09;   // the shoulders may run off the sides
      paintBase();
    }

    function loudness() {
      if (clock - levelAt < 0.35) return level;         // a fresh real reading
      var t = clock;
      if (state === 'speaking') {
        var talk = Math.abs(Math.sin(t * 6.1) * 0.55 + Math.sin(t * 9.7 + 1) * 0.35 + Math.sin(t * 2.3) * 0.4);
        return Math.min(1, talk * (Math.sin(t * 0.9) > -0.55 ? 1 : 0.15));
      }
      if (state === 'listening') return 0.16 + 0.12 * Math.abs(Math.sin(t * 2.2)) + 0.08 * Math.abs(Math.sin(t * 5.3));
      return 0.04 + 0.03 * Math.sin(t * 0.8);
    }

    function fire(c) {
      if (c.rest > 0) return;
      c.flash = 1; c.rest = 0.35; c.fireAt = -1;
      if (signals.length > 150) return;
      c.axons.forEach(function (a) { signals.push({ a: a, p: 0 }); });
    }

    function draw(dt) {
      var loud = loudness(), k = Math.min(1, dt * 5), goal = COLORS[state];
      for (var i = 0; i < 3; i++) col[i] += (goal[i] - col[i]) * k;
      energy += (ENERGY[state] - energy) * k;

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);

      var glow = ctx.createRadialGradient(CX, CY, 0, CX, CY, S * 1.25);
      glow.addColorStop(0, rgba(0.10 + loud * 0.14)); glow.addColorStop(1, rgba(0));
      ctx.fillStyle = glow; ctx.fillRect(0, 0, W, H);

      // the resting structure, tinted to the current colour
      var key = Math.round(col[0] / 3) + ',' + Math.round(col[1] / 3) + ',' + Math.round(col[2] / 3);
      if (key !== tintKey) {
        var tc = tinted.getContext('2d');
        tc.globalCompositeOperation = 'source-over'; tc.clearRect(0, 0, tinted.width, tinted.height); tc.drawImage(base, 0, 0);
        tc.globalCompositeOperation = 'source-in'; tc.fillStyle = 'rgb(' + col.map(Math.round).join(',') + ')'; tc.fillRect(0, 0, tinted.width, tinted.height);
        tintKey = key;
      }
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.globalAlpha = 0.5 + energy * 0.3 + loud * 0.2; ctx.drawImage(tinted, 0, 0); ctx.globalAlpha = 1;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      // cells fire on their own now and then, more often the busier the call is
      var rate = dt * (0.05 + energy * 0.5 + loud * energy * 2.6);
      cells.forEach(function (c) {
        c.rest = Math.max(0, c.rest - dt); c.flash = Math.max(0, c.flash - dt * 2.4);
        if (Math.random() < rate) fire(c);
        if (c.fireAt >= 0 && clock >= c.fireAt) fire(c);
      });

      // signals travel down the axons; arriving, they light the next cell and may set it off
      var speed = 0.5 + energy * 1.0 + loud * 0.6;
      ctx.lineCap = 'round';
      for (var s = signals.length - 1; s >= 0; s--) {
        var sig = signals[s], a = sig.a;
        sig.p += dt * speed / Math.max(0.12, a.len);
        if (sig.p >= 1) {
          var target = cells[a.to];
          target.flash = Math.max(target.flash, 0.7);
          if (target.fireAt < 0 && Math.random() < 0.28 + energy * 0.3) target.fireAt = clock + 0.06;
          signals.splice(s, 1); continue;
        }
        for (var tail = 0; tail < 5; tail++) {
          var u = Math.max(0, sig.p - tail * 0.035), v = 1 - u;
          var px = v * v * a.x1 + 2 * v * u * a.cx + u * u * a.x2, py = v * v * a.y1 + 2 * v * u * a.cy + u * u * a.y2;
          ctx.fillStyle = rgba(0.95 - tail * 0.18, 0.75 - tail * 0.12);
          ctx.beginPath(); ctx.arc(X(px), Y(py), Math.max(0.8, S * (0.0105 - tail * 0.0015)), 0, TAU); ctx.fill();
        }
      }

      cells.forEach(function (c) {
        var f = c.flash, r0 = S * 0.016 * c.size;
        if (f > 0.02) {                                   // a firing cell lights its own branches
          ctx.strokeStyle = rgba(f * 0.9, 0.55);
          c.twigs.forEach(function (t) {
            ctx.lineWidth = Math.max(0.6, S * 0.0062 * t[6]);
            ctx.beginPath(); ctx.moveTo(X(t[0]), Y(t[1])); ctx.quadraticCurveTo(X(t[2]), Y(t[3]), X(t[4]), Y(t[5])); ctx.stroke();
          });
        }
        var hr = r0 * (2.6 + f * 3.2 + loud * 1.2), g = ctx.createRadialGradient(X(c.x), Y(c.y), 0, X(c.x), Y(c.y), hr);
        g.addColorStop(0, rgba(0.38 + f * 0.5, 0.3 + f * 0.4)); g.addColorStop(1, rgba(0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(X(c.x), Y(c.y), hr, 0, TAU); ctx.fill();
        ctx.fillStyle = rgba(0.85 + f * 0.15, 0.25 + f * 0.6);
        ctx.beginPath(); ctx.arc(X(c.x), Y(c.y), r0 * (1 + f * 0.5), 0, TAU); ctx.fill();
      });
    }

    /** Eyes that blink and look awake when listening; a mouth that opens with the voice. */
    function face(dt, loud) {
      if (clock > blinkAt + 0.16) blinkAt = clock + 2.2 + Math.random() * 3.5;
      var shut = clock >= blinkAt ? Math.sin((clock - blinkAt) / 0.16 * Math.PI) : 0;
      var open = (1 - shut * 0.92) * (0.72 + energy * 0.28);
      [-1, 1].forEach(function (side) {
        var ex = X(side * EYE_X), ey = Y(EYE_Y), ew = S * EYE_W, eh = Math.max(0.6, S * EYE_H * open);
        var g = ctx.createRadialGradient(ex, ey, 0, ex, ey, ew * 1.9);
        g.addColorStop(0, rgba(0.22 + energy * 0.25 + loud * 0.15)); g.addColorStop(1, rgba(0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(ex, ey, ew * 1.9, 0, TAU); ctx.fill();
        ctx.beginPath();                                   // almond: two arcs meeting at the corners
        ctx.moveTo(ex - ew, ey); ctx.quadraticCurveTo(ex, ey - eh * 2, ex + ew, ey); ctx.quadraticCurveTo(ex, ey + eh * 2, ex - ew, ey);
        ctx.strokeStyle = rgba(0.85, 0.35); ctx.lineWidth = Math.max(1, S * 0.008); ctx.stroke();
        ctx.save(); ctx.clip();
        ctx.fillStyle = rgba(0.95, 0.55); ctx.beginPath(); ctx.arc(ex, ey, S * 0.036, 0, TAU); ctx.fill();
        ctx.fillStyle = '#000'; ctx.beginPath(); ctx.arc(ex, ey, S * (0.017 - loud * 0.004), 0, TAU); ctx.fill();
        ctx.restore();
      });

      // nose: only hinted at
      ctx.strokeStyle = rgba(0.35 + energy * 0.2); ctx.lineWidth = Math.max(0.8, S * 0.006); ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(X(-0.012), Y(0.0)); ctx.quadraticCurveTo(X(-0.05), Y(0.15), X(-0.04), Y(0.19));
      ctx.quadraticCurveTo(X(0), Y(0.215), X(0.04), Y(0.19)); ctx.stroke();

      // mouth: a calm line that opens and closes with the voice while speaking
      mouth += ((state === 'speaking' ? loud : 0) - mouth) * Math.min(1, dt * 18);
      var mx = X(0), my = Y(MOUTH_Y), mw = S * MOUTH_W, gap = S * 0.075 * mouth, smile = S * 0.018;
      ctx.beginPath();
      ctx.moveTo(mx - mw, my); ctx.quadraticCurveTo(mx, my - gap * 0.7 + smile * 0.4, mx + mw, my);
      ctx.quadraticCurveTo(mx, my + gap * 1.5 + smile * 2, mx - mw, my); ctx.closePath();
      ctx.fillStyle = rgba(0.10 + mouth * 0.3, 0.2); ctx.fill();
      ctx.strokeStyle = rgba(0.75 + mouth * 0.25, 0.3 + mouth * 0.3); ctx.lineWidth = Math.max(1, S * 0.009); ctx.stroke();
    }

    function frame(now) {
      if (!running) return;
      var dt = Math.min(0.05, (now - last) / 1000 || 0.016); last = now; clock += dt;
      resize(); draw(dt); face(dt, loudness());
      root.requestAnimationFrame(frame);
    }
    /** With reduced motion there is no animation: settle into one picture of the state. */
    function settle() { resize(); for (var i = 0; i < 60; i++) { clock += 0.05; draw(0.05); } blinkAt = clock + 9; face(0.05, loudness()); }

    this.setState = function (s) { if (COLORS[s]) state = s; if (still) settle(); };
    this.setLevel = function (v) { level = Math.max(0, Math.min(1, Number(v) || 0)); levelAt = clock; };
    this.start = function () {
      if (still) { settle(); root.addEventListener('resize', settle); return; }
      if (running) return;
      running = true; last = 0; root.requestAnimationFrame(frame);
    };
    this.stop = function () { running = false; };
  }

  root.MunshiVisual = { create: function (canvas) { return new Visual(canvas); } };
})(typeof self !== 'undefined' ? self : this);
