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

  // The face follows the usual drawing proportions: eyes halfway down the
  // head, one eye-width apart, each a fifth of the head's width; the nose ends
  // halfway from the eyes to the chin; the mouth sits a third of the way below it.
  var EYE_X = 0.2, EYE_Y = -0.11, MOUTH_Y = 0.378;

  // half-width of the head at each height, crown to chin: [y, half width]
  var SKULL = [[-0.85, 0], [-0.83, 0.13], [-0.78, 0.25], [-0.70, 0.36], [-0.58, 0.445], [-0.42, 0.492], [-0.25, 0.51],
               [-0.08, 0.505], [0.08, 0.485], [0.22, 0.45], [0.34, 0.40], [0.44, 0.325], [0.52, 0.235], [0.58, 0.145], [0.615, 0.06], [0.625, 0]];
  function headHalf(y) {
    if (y <= SKULL[0][0] || y >= SKULL[SKULL.length - 1][0]) return 0;
    var i = 1;
    while (SKULL[i][0] < y) i++;
    var p0 = SKULL[Math.max(0, i - 2)], p1 = SKULL[i - 1], p2 = SKULL[i], p3 = SKULL[Math.min(SKULL.length - 1, i + 1)];
    var t = (y - p1[0]) / (p2[0] - p1[0]), t2 = t * t, t3 = t2 * t;                      // Catmull-Rom through the points
    return Math.max(0, 0.5 * (2 * p1[1] + (p2[1] - p0[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (3 * p1[1] - p0[1] - 3 * p2[1] + p3[1]) * t3));
  }
  /** Neck, then shoulders sloping away, as a half-width below the jaw. */
  function trunkHalf(y) {
    if (y < 0.42 || y > 1.02) return 0;
    if (y < 0.82) return 0.235 + 0.02 * Math.pow((y - 0.62) / 0.2, 2);
    return 0.255 + 0.95 * Math.pow((y - 0.82) / 0.2, 1.35);
  }
  var EAR = { x: 0.535, y: 0.03, rx: 0.058, ry: 0.19 };       // brow line down to the base of the nose
  /** A person seen from the front: head, ears, neck and shoulders. */
  function inBody(x, y) {
    var ax = Math.abs(x);
    return ax < headHalf(y) || ax < trunkHalf(y) || Math.pow((ax - EAR.x) / EAR.rx, 2) + Math.pow((y - EAR.y) / EAR.ry, 2) < 1;
  }
  /** The same person with room left for the features, so the neurons keep clear of them. */
  function inBrain(x, y) {
    if (!inBody(x, y)) return false;
    var ax = Math.abs(x);
    if (Math.pow((ax - EYE_X) / 0.15, 2) + Math.pow((y - EYE_Y + 0.045) / 0.115, 2) < 1) return false;    // eye and brow
    if (Math.pow(x / 0.095, 2) + Math.pow((y - 0.13) / 0.17, 2) < 1) return false;                         // nose
    return !(Math.pow(x / 0.21, 2) + Math.pow((y - MOUTH_Y - 0.03) / 0.1, 2) < 1);                         // lips
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

    return { cells: cells };
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
      // the silhouette: skull and jaw, ears, neck and shoulders
      b.strokeStyle = 'rgba(255,255,255,0.55)'; b.lineWidth = Math.max(1, S * 0.008); b.lineJoin = 'round';
      [-1, 1].forEach(function (side) {
        var y;
        b.beginPath();
        for (y = -0.85; y <= 0.626; y += 0.01) { if (y === -0.85) b.moveTo(X(0), Y(y)); else b.lineTo(X(side * headHalf(y)), Y(y)); }
        b.lineTo(X(0), Y(0.625)); b.stroke();
        b.beginPath();
        for (y = 0.5; y <= 1.02; y += 0.01) { if (y === 0.5) b.moveTo(X(side * trunkHalf(y)), Y(y)); else b.lineTo(X(side * trunkHalf(y)), Y(y)); }
        b.stroke();
        b.beginPath(); b.ellipse(X(side * EAR.x), Y(EAR.y), S * EAR.rx, S * EAR.ry, 0, side > 0 ? -1.75 : 1.39, side > 0 ? 1.75 : 4.89); b.stroke();
        b.beginPath(); b.ellipse(X(side * (EAR.x + 0.004)), Y(EAR.y + 0.01), S * EAR.rx * 0.5, S * EAR.ry * 0.62, 0, side > 0 ? -1.4 : 1.74, side > 0 ? 1.4 : 4.54);
        b.strokeStyle = 'rgba(255,255,255,0.3)'; b.stroke(); b.strokeStyle = 'rgba(255,255,255,0.55)';
      });
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
      S = Math.min(W * 0.94 / 1.24, H * 0.66 / 1.87); CX = W / 2; CY = H / 2 - S * 0.085;   // the shoulders may run off the sides
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

    /**
     * The features, drawn in head units so they scale with the figure: brows,
     * lidded eyes that blink, a nose, and two lips that part with the voice.
     */
    function face(dt, loud) {
      if (clock > blinkAt + 0.16) blinkAt = clock + 2.2 + Math.random() * 3.5;
      var shut = clock >= blinkAt ? Math.sin((clock - blinkAt) / 0.16 * Math.PI) : 0;
      var open = Math.max(0.06, (1 - shut) * (0.78 + energy * 0.22));
      var px = 1 / S;                                       // one screen pixel, in head units
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';

      // fade the shoulders out at the bottom rather than cutting them off
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      var fade = ctx.createLinearGradient(0, Y(0.84), 0, Y(1.03));
      fade.addColorStop(0, 'rgba(0,0,0,0)'); fade.addColorStop(1, 'rgba(0,0,0,1)');
      ctx.fillStyle = fade; ctx.fillRect(0, Y(0.84), W, H - Y(0.84));

      [-1, 1].forEach(function (side) {
        ctx.save();
        ctx.translate(X(side * EYE_X), Y(EYE_Y)); ctx.scale(side * S, S);   // inner corner is at -x on both sides

        // brow: thick near the nose, tapering to the temple; lifts a little when attentive
        var lift = -0.01 * energy;
        ctx.beginPath(); ctx.moveTo(-0.118, -0.108 + lift); ctx.quadraticCurveTo(0.02, -0.178 + lift, 0.142, -0.116 + lift);
        ctx.quadraticCurveTo(0.03, -0.146 + lift, -0.112, -0.082 + lift); ctx.closePath();
        ctx.fillStyle = rgba(0.6, 0.15); ctx.fill();

        // the crease above the lid
        ctx.beginPath(); ctx.moveTo(-0.088, -0.03); ctx.bezierCurveTo(-0.04, -0.088, 0.05, -0.09, 0.108, -0.03);
        ctx.strokeStyle = rgba(0.3); ctx.lineWidth = 1.2 * px; ctx.stroke();

        // the opening between the lids
        function lids() {
          ctx.beginPath(); ctx.moveTo(-0.1, 0.012);
          ctx.bezierCurveTo(-0.06, -0.012 - 0.062 * open, 0.04, -0.012 - 0.066 * open, 0.1, -0.006);
          ctx.bezierCurveTo(0.05, 0.004 + 0.036 * open, -0.04, 0.008 + 0.038 * open, -0.1, 0.012);
        }
        lids(); ctx.fillStyle = rgba(0.16 + energy * 0.1, 0.5); ctx.fill();
        ctx.save(); lids(); ctx.clip();                     // iris and pupil sit behind the lids
        var iris = ctx.createRadialGradient(0, -0.006, 0.012, 0, -0.006, 0.05);
        iris.addColorStop(0, rgba(0.95, 0.15)); iris.addColorStop(0.75, rgba(0.9, 0.0)); iris.addColorStop(1, rgba(0.95, 0.0));
        ctx.fillStyle = iris; ctx.beginPath(); ctx.arc(0, -0.006, 0.05, 0, TAU); ctx.fill();
        ctx.strokeStyle = 'rgba(0,0,0,0.55)'; ctx.lineWidth = 1.4 * px; ctx.beginPath(); ctx.arc(0, -0.006, 0.049, 0, TAU); ctx.stroke();
        ctx.fillStyle = '#000'; ctx.beginPath(); ctx.arc(0, -0.006, 0.021 - loud * 0.004, 0, TAU); ctx.fill();
        ctx.fillStyle = 'rgba(255,255,255,0.9)'; ctx.beginPath(); ctx.arc(-0.016 * side, -0.022, 0.0075, 0, TAU); ctx.fill();
        ctx.restore();

        // lash line on top, a lighter line underneath
        ctx.beginPath(); ctx.moveTo(-0.1, 0.012); ctx.bezierCurveTo(-0.06, -0.012 - 0.062 * open, 0.04, -0.012 - 0.066 * open, 0.1, -0.006);
        ctx.lineTo(0.112, -0.014);
        ctx.strokeStyle = rgba(0.95, 0.35); ctx.lineWidth = 2.6 * px; ctx.stroke();
        ctx.beginPath(); ctx.moveTo(0.1, -0.006); ctx.bezierCurveTo(0.05, 0.004 + 0.036 * open, -0.04, 0.008 + 0.038 * open, -0.1, 0.012);
        ctx.strokeStyle = rgba(0.5); ctx.lineWidth = 1.2 * px; ctx.stroke();
        ctx.restore();
      });

      // from here on, head units centred on the face
      ctx.save(); ctx.translate(CX, CY); ctx.scale(S, S);

      // nose: the sides of the bridge, the wings, the nostrils and the tip
      [-1, 1].forEach(function (k) {
        ctx.beginPath(); ctx.moveTo(k * 0.03, -0.06); ctx.quadraticCurveTo(k * 0.042, 0.08, k * 0.058, 0.168);
        ctx.strokeStyle = rgba(0.26); ctx.lineWidth = 1.2 * px; ctx.stroke();
        ctx.beginPath(); ctx.moveTo(k * 0.058, 0.168); ctx.bezierCurveTo(k * 0.108, 0.172, k * 0.108, 0.246, k * 0.064, 0.244);
        ctx.strokeStyle = rgba(0.6, 0.1); ctx.lineWidth = 1.6 * px; ctx.stroke();
        ctx.beginPath(); ctx.ellipse(k * 0.037, 0.239, 0.019, 0.0095, -k * 0.35, 0, TAU);
        ctx.fillStyle = rgba(0.4); ctx.fill();
        ctx.beginPath(); ctx.moveTo(k * 0.016, 0.278); ctx.lineTo(k * 0.026, 0.328);             // philtrum
        ctx.strokeStyle = rgba(0.16); ctx.lineWidth = 1 * px; ctx.stroke();
      });
      ctx.beginPath(); ctx.moveTo(-0.064, 0.244); ctx.quadraticCurveTo(-0.03, 0.262, 0, 0.268); ctx.quadraticCurveTo(0.03, 0.262, 0.064, 0.244);
      ctx.strokeStyle = rgba(0.5, 0.1); ctx.lineWidth = 1.5 * px; ctx.stroke();

      // lips: an upper lip with its bow, a fuller lower lip, and the gap that opens between them
      mouth += ((state === 'speaking' ? loud : 0) - mouth) * Math.min(1, dt * 18);
      var cx = 0.168, cy = MOUTH_Y, g = mouth * 0.055, up = mouth * 0.006;
      function upperBottom() { ctx.bezierCurveTo(0.09, cy - 0.002 - up, 0.04, cy + 0.005 - up, 0, cy - up); ctx.bezierCurveTo(-0.04, cy + 0.005 - up, -0.09, cy - 0.002 - up, -cx, cy); }
      function lowerTop() { ctx.bezierCurveTo(-0.09, cy + g, -0.04, cy + 0.007 + g, 0, cy + 0.002 + g); ctx.bezierCurveTo(0.04, cy + 0.007 + g, 0.09, cy + g, cx, cy); }

      ctx.beginPath(); ctx.moveTo(cx, cy); upperBottom(); lowerTop(); ctx.closePath();          // inside of the mouth
      ctx.fillStyle = '#000'; ctx.fill();

      ctx.beginPath(); ctx.moveTo(-cx, cy);                                                     // upper lip
      ctx.bezierCurveTo(-0.11, cy - 0.026 - up, -0.07, cy - 0.048 - up, -0.04, cy - 0.046 - up);
      ctx.quadraticCurveTo(-0.018, cy - 0.042 - up, 0, cy - 0.031 - up); ctx.quadraticCurveTo(0.018, cy - 0.042 - up, 0.04, cy - 0.046 - up);
      ctx.bezierCurveTo(0.07, cy - 0.048 - up, 0.11, cy - 0.026 - up, cx, cy);
      upperBottom(); ctx.closePath();
      ctx.fillStyle = rgba(0.34 + mouth * 0.1, 0.05); ctx.fill();
      ctx.strokeStyle = rgba(0.85, 0.25); ctx.lineWidth = 1.5 * px; ctx.stroke();

      ctx.beginPath(); ctx.moveTo(-cx, cy); lowerTop();                                         // lower lip
      ctx.bezierCurveTo(0.115, cy + 0.066 + g * 0.9, 0.055, cy + 0.084 + g, 0, cy + 0.082 + g);
      ctx.bezierCurveTo(-0.055, cy + 0.084 + g, -0.115, cy + 0.066 + g * 0.9, -cx, cy); ctx.closePath();
      ctx.fillStyle = rgba(0.42 + mouth * 0.1, 0.12); ctx.fill();
      ctx.strokeStyle = rgba(0.85, 0.25); ctx.lineWidth = 1.5 * px; ctx.stroke();
      ctx.beginPath(); ctx.ellipse(0, cy + 0.04 + g, 0.05, 0.012, 0, 0, TAU);                    // the shine on it
      ctx.fillStyle = rgba(0.22, 0.6); ctx.fill();

      ctx.beginPath(); ctx.moveTo(-0.07, cy + 0.118 + g); ctx.quadraticCurveTo(0, cy + 0.104 + g, 0.07, cy + 0.118 + g);   // above the chin
      ctx.strokeStyle = rgba(0.18); ctx.lineWidth = 1.1 * px; ctx.stroke();
      ctx.restore();
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
