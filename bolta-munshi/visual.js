/*
 * Bolta Munshi - the screen: a brain built from neurons.
 *
 * Each neuron has a cell body, branching dendrites and axons that end in
 * small bulbs beside other cells. The whole brain breathes with a slow glow
 * that drifts across it, and a handful of signals travel along the axons;
 * the cell one reaches swells gently rather than flashing. It is meant to be
 * calm to look at. The call sets the mood: dim when idle, green while
 * listening, amber and a little livelier while speaking.
 *
 * Used by the web page and, inside a WebView, by the Android app.
 */
(function (root) {
  'use strict';

  var TAU = Math.PI * 2;
  var COLORS = { idle: [95, 113, 134], listening: [53, 211, 154], speaking: [245, 179, 77] };
  var ENERGY = { idle: 0.07, listening: 0.5, speaking: 1 };

  function rand(seed) { var s = seed; return function () { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; }

  /** Side view of a brain, facing left, inside a -1..1 box. */
  function inBrain(x, y) {
    var cerebrum = Math.pow((x + 0.02) / 0.80, 2) + Math.pow((y + 0.10) / 0.56, 2) < 1 && !(y > 0.30 && x > -0.05);
    var frontal = Math.pow((x + 0.50) / 0.34, 2) + Math.pow((y - 0.02) / 0.40, 2) < 1 && y < 0.34;
    var cerebellum = Math.pow((x - 0.44) / 0.30, 2) + Math.pow((y - 0.40) / 0.19, 2) < 1;
    var stem = x > 0.02 && x < 0.20 - (y - 0.3) * 0.12 && y > 0.30 && y < 0.74;
    return cerebrum || frontal || cerebellum || stem;
  }

  // ------------------------------------------------------------ anatomy ---

  /** Lay out the cells once. Everything is in brain units; drawing scales it. */
  function grow() {
    var r = rand(12), cells = [], guard = 0;
    while (cells.length < 110 && guard++ < 120000) {
      var x = r() * 2 - 1, y = r() * 2 - 1;
      if (!inBrain(x, y)) continue;
      if (cells.some(function (c) { return Math.hypot(c.x - x, c.y - y) < 0.105; })) continue;
      cells.push({ x: x, y: y, size: 0.6 + r() * 0.55, twigs: [], axons: [], flash: 0, glow: 0, rest: 0, phase: r() * TAU });
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
      var trunks = 5 + Math.floor(r() * 3), start = r() * TAU;
      for (var k = 0; k < trunks; k++) twig(c, c.x, c.y, start + k / trunks * TAU + (r() - 0.5) * 0.5, 0.042 + r() * 0.03, 1.3, 3);
    });

    // axons: each cell reaches two others a little way off, ending in a bulb beside them
    cells.forEach(function (c, i) {
      var near = cells.map(function (o, j) { return { j: j, d: Math.hypot(o.x - c.x, o.y - c.y) }; })
        .filter(function (o) { return o.j !== i; }).sort(function (p, q) { return p.d - q.d; });
      [near[Math.floor(r() * 2)], near[2 + Math.floor(r() * 3)], near[5 + Math.floor(r() * 5)], near[11 + Math.floor(r() * 14)]].forEach(function (pick) {
        if (!pick) return;
        var t = cells[pick.j], dx = t.x - c.x, dy = t.y - c.y, d = Math.hypot(dx, dy), side = (r() - 0.5) * 0.5;
        var ex = t.x - dx / d * 0.022, ey = t.y - dy / d * 0.022;                 // stop just short: the synapse
        c.axons.push({ to: pick.j, x1: c.x, y1: c.y, cx: (c.x + ex) / 2 - dy * side, cy: (c.y + ey) / 2 + dx * side, x2: ex, y2: ey, len: d });
      });
    });

    // outline of the whole brain, as points along the edge of its shape
    var rim = [], step = 0.014;
    for (var gx = -1; gx < 1; gx += step) {
      for (var gy = -1; gy < 1; gy += step) {
        var a = inBrain(gx, gy);
        if (a !== inBrain(gx + step, gy) || a !== inBrain(gx, gy + step)) rim.push([gx + step / 2, gy + step / 2]);
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
    var calm = 0;                                       // loudness with the jitter smoothed out
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
          b.strokeStyle = 'rgba(255,255,255,0.30)'; b.lineWidth = Math.max(0.5, S * 0.0034);
          b.beginPath(); b.moveTo(X(a.x1), Y(a.y1)); b.quadraticCurveTo(X(a.cx), Y(a.cy), X(a.x2), Y(a.y2)); b.stroke();
          b.fillStyle = 'rgba(255,255,255,0.7)'; b.beginPath(); b.arc(X(a.x2), Y(a.y2), Math.max(0.8, S * 0.0058), 0, TAU); b.fill();
        });
        c.twigs.forEach(function (t) {
          b.strokeStyle = 'rgba(255,255,255,0.5)'; b.lineWidth = Math.max(0.45, S * 0.0046 * t[6]);
          b.beginPath(); b.moveTo(X(t[0]), Y(t[1])); b.quadraticCurveTo(X(t[2]), Y(t[3]), X(t[4]), Y(t[5])); b.stroke();
        });
      });
    }

    function resize() {
      var box = canvas.getBoundingClientRect();
      var nd = Math.min(2, root.devicePixelRatio || 1), nw = Math.max(1, Math.round(box.width * nd)), nh = Math.max(1, Math.round(box.height * nd));
      if (nw === canvas.width && nh === canvas.height && nd === dpr) return;
      dpr = nd; canvas.width = nw; canvas.height = nh; W = box.width; H = box.height;
      S = Math.min(W * 0.92 / 1.66, H * 0.56 / 1.42); CX = W / 2 + S * 0.03; CY = H / 2 - S * 0.04;
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

    /** A cell sends one signal down one of its wires. */
    function fire(c) {
      if (c.rest > 0 || signals.length >= Math.round(3 + energy * 9)) return;
      c.flash = 1; c.rest = 2.5;
      signals.push({ a: c.axons[Math.floor(Math.random() * c.axons.length)], p: 0 });
    }

    function draw(dt) {
      calm += (loudness() - calm) * Math.min(1, dt * 2.5);
      var loud = calm, k = Math.min(1, dt * 2.2), goal = COLORS[state];
      for (var i = 0; i < 3; i++) col[i] += (goal[i] - col[i]) * k;
      energy += (ENERGY[state] - energy) * k;

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);

      var glow = ctx.createRadialGradient(CX, CY, 0, CX, CY, S * 1.25);
      glow.addColorStop(0, rgba(0.09 + loud * 0.08)); glow.addColorStop(1, rgba(0));
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
      ctx.globalAlpha = 0.66 + energy * 0.2 + loud * 0.08 + 0.05 * Math.sin(clock * 0.7); ctx.drawImage(tinted, 0, 0); ctx.globalAlpha = 1;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      // now and then one cell sends a signal; each cell's glow rises and falls slowly
      var rate = dt * (0.004 + energy * 0.012);
      cells.forEach(function (c) {
        c.rest = Math.max(0, c.rest - dt); c.flash = Math.max(0, c.flash - dt * 0.7);
        if (Math.random() < rate) fire(c);
        var swell = c.flash * c.flash * (3 - 2 * c.flash);                               // ease in and out
        var drift = 0.5 + 0.5 * Math.sin(clock * 0.55 + c.phase * 0.35 + c.x * 2.2 + c.y * 1.4);   // a slow tide across the brain
        c.glow = Math.min(1, swell * 0.7 + drift * (0.12 + energy * 0.2 + loud * 0.2));
      });

      // signals travel down the axons; arriving, they light the next cell and may set it off
      var speed = 0.16 + energy * 0.2;
      ctx.lineCap = 'round';
      for (var s = signals.length - 1; s >= 0; s--) {
        var sig = signals[s], a = sig.a;
        sig.p += dt * speed / Math.max(0.12, a.len);
        if (sig.p >= 1) {
          var target = cells[a.to];
          target.flash = Math.max(target.flash, 0.8);      // it swells, and passes nothing on
          signals.splice(s, 1); continue;
        }
        for (var tail = 0; tail < 4; tail++) {
          var u = Math.max(0, sig.p - tail * 0.035), v = 1 - u;
          var px = v * v * a.x1 + 2 * v * u * a.cx + u * u * a.x2, py = v * v * a.y1 + 2 * v * u * a.cy + u * u * a.y2;
          ctx.fillStyle = rgba(0.7 - tail * 0.15, 0.55 - tail * 0.1);
          ctx.beginPath(); ctx.arc(X(px), Y(py), Math.max(0.7, S * (0.0085 - tail * 0.0012)), 0, TAU); ctx.fill();
        }
      }

      cells.forEach(function (c) {
        var f = c.glow, r0 = S * 0.0125 * c.size;
        if (f > 0.3) {                                   // a firing cell lights its own branches
          ctx.strokeStyle = rgba((f - 0.3) * 0.7, 0.4);
          c.twigs.forEach(function (t) {
            ctx.lineWidth = Math.max(0.5, S * 0.0054 * t[6]);
            ctx.beginPath(); ctx.moveTo(X(t[0]), Y(t[1])); ctx.quadraticCurveTo(X(t[2]), Y(t[3]), X(t[4]), Y(t[5])); ctx.stroke();
          });
        }
        var hr = r0 * (2.6 + f * 2.2), g = ctx.createRadialGradient(X(c.x), Y(c.y), 0, X(c.x), Y(c.y), hr);
        g.addColorStop(0, rgba(0.28 + f * 0.4, 0.25 + f * 0.3)); g.addColorStop(1, rgba(0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(X(c.x), Y(c.y), hr, 0, TAU); ctx.fill();
        ctx.fillStyle = rgba(0.7 + f * 0.3, 0.2 + f * 0.45);
        ctx.beginPath(); ctx.arc(X(c.x), Y(c.y), r0 * (1 + f * 0.25), 0, TAU); ctx.fill();
      });
    }

    function frame(now) {
      if (!running) return;
      var dt = Math.min(0.05, (now - last) / 1000 || 0.016); last = now; clock += dt;
      resize(); draw(dt);
      root.requestAnimationFrame(frame);
    }
    /** With reduced motion there is no animation: settle into one picture of the state. */
    function settle() { resize(); for (var i = 0; i < 60; i++) { clock += 0.05; draw(0.05); } }

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
