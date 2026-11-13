/*
 * Bolta Munshi - the screen: a brain drawn as a network.
 *
 * Points joined by straight lines fill the outline of a brain seen from the
 * side; signals travel from point to point along the lines. The lobes are
 * told apart because no line crosses the brain's main folds. Points sit at
 * different depths, so the nearer ones are larger and brighter and the whole
 * thing shifts slightly as if turning.
 *
 * The call sets the pace: a few slow signals when idle (grey), more while
 * listening (green), most while speaking (amber).
 *
 * Used by the web page and, inside a WebView, by the Android app.
 */
(function (root) {
  'use strict';
  var TAU = Math.PI * 2;
  var STATES = { idle: { c: [110, 128, 150], e: 0.08 }, listening: { c: [53, 211, 154], e: 0.5 }, speaking: { c: [245, 179, 77], e: 1 } };
  function rand(seed) { var s = seed; return function () { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; }

  // The outline of a brain from the side, facing left, clockwise from the forehead end.
  var OUTLINE = [[-0.80, 0.02], [-0.78, -0.16], [-0.70, -0.33], [-0.56, -0.47], [-0.38, -0.57], [-0.16, -0.63], [0.08, -0.63], [0.30, -0.58],
    [0.50, -0.48], [0.66, -0.34], [0.77, -0.16], [0.80, 0.03], [0.76, 0.17], [0.67, 0.25],                 // over the top to the back
    [0.73, 0.35], [0.70, 0.46], [0.58, 0.55], [0.42, 0.57], [0.30, 0.52],                                  // cerebellum
    [0.25, 0.57], [0.22, 0.73], [0.10, 0.74], [0.08, 0.57], [0.05, 0.44],                                  // stem
    [-0.08, 0.41], [-0.24, 0.39], [-0.40, 0.34], [-0.50, 0.26], [-0.46, 0.17],                             // under the temporal lobe
    [-0.60, 0.20], [-0.72, 0.14]];
  // The folds that divide it: lines of the network never cross these.
  var FOLDS = [
    [[-0.46, 0.17], [-0.30, 0.13], [-0.12, 0.08], [0.06, 0.03], [0.22, -0.03]],          // the side fissure, above the temporal lobe
    [[-0.04, -0.62], [-0.08, -0.45], [-0.06, -0.28], [-0.10, -0.12], [-0.12, 0.04]],     // the central fold, front from back
    [[0.67, 0.25], [0.52, 0.27], [0.38, 0.33], [0.30, 0.42], [0.30, 0.52]],              // cerebellum from the rest
    [[0.50, -0.46], [0.46, -0.28], [0.50, -0.10], [0.44, 0.06]]                          // the back lobe
  ];
  /** A smooth curve through the points (Catmull-Rom), as a list of short straight pieces. */
  function smoothPath(p, closed) {
    var out = [], n = p.length, last = closed ? n : n - 1;
    for (var i = 0; i < last; i++) {
      var a = p[closed ? (i - 1 + n) % n : Math.max(0, i - 1)], b = p[i], c = p[(i + 1) % n], d = p[closed ? (i + 2) % n : Math.min(n - 1, i + 2)];
      for (var t = 0; t < 1; t += 0.125) {
        var t2 = t * t, t3 = t2 * t;
        out.push([0.5 * (2 * b[0] + (c[0] - a[0]) * t + (2 * a[0] - 5 * b[0] + 4 * c[0] - d[0]) * t2 + (3 * b[0] - a[0] - 3 * c[0] + d[0]) * t3),
                  0.5 * (2 * b[1] + (c[1] - a[1]) * t + (2 * a[1] - 5 * b[1] + 4 * c[1] - d[1]) * t2 + (3 * b[1] - a[1] - 3 * c[1] + d[1]) * t3)]);
      }
    }
    if (!closed) out.push(p[n - 1]);
    return out;
  }
  var RIM = smoothPath(OUTLINE, true), CREASES = FOLDS.map(function (f) { return smoothPath(f, false); });
  function inBrain(x, y) {
    var inside = false;
    for (var i = 0, j = RIM.length - 1; i < RIM.length; j = i++) {
      var a = RIM[i], b = RIM[j];
      if ((a[1] > y) !== (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
    }
    return inside;
  }
  function crosses(ax, ay, bx, by, cx, cy, dx, dy) {
    function side(px, py, qx, qy, rx, ry) { return (qx - px) * (ry - py) - (qy - py) * (rx - px); }
    return side(ax, ay, bx, by, cx, cy) * side(ax, ay, bx, by, dx, dy) < 0 && side(cx, cy, dx, dy, ax, ay) * side(cx, cy, dx, dy, bx, by) < 0;
  }
  function crossesFold(a, b) {
    return CREASES.some(function (f) {
      for (var i = 1; i < f.length; i++) if (crosses(a.x, a.y, b.x, b.y, f[i - 1][0], f[i - 1][1], f[i][0], f[i][1])) return true;
      return false;
    });
  }

  function build() {
    var r = rand(7), nodes = [], edges = [], guard = 0;
    while (nodes.length < 240 && guard++ < 80000) {
      var x = r() * 1.7 - 0.85, y = r() * 1.5 - 0.7;
      if (!inBrain(x, y)) continue;
      if (nodes.some(function (n) { return Math.hypot(n.x - x, n.y - y) < 0.068; })) continue;
      var z = r();                                         // 0 at the back, 1 nearest the viewer
      nodes.push({ x: x, y: y, z: z, hub: r() < 0.07, glow: 0, links: [], ph: r() * TAU });
    }
    nodes.forEach(function (a, i) {
      nodes.map(function (b, j) { return { j: j, d: Math.hypot(a.x - b.x, a.y - b.y) }; })
        .filter(function (o) { return o.j !== i && o.d < 0.2 && !crossesFold(a, nodes[o.j]); })
        .sort(function (p, q) { return p.d - q.d; }).slice(0, a.hub ? 5 : 3)
        .forEach(function (o) { if (a.links.indexOf(o.j) === -1) { a.links.push(o.j); nodes[o.j].links.push(i); edges.push([i, o.j]); } });
    });
    var pulses = [];
    for (var k = 0; k < 44; k++) {
      var s;
      do { s = Math.floor(r() * nodes.length); } while (!nodes[s].links.length);
      pulses.push({ a: s, b: nodes[s].links[0], p: r(), v: 0.55 + r() * 0.6 });
    }
    return { nodes: nodes, edges: edges, pulses: pulses };
  }

  function Visual(canvas) {
    var ctx = canvas.getContext('2d'), m = build(), nodes = m.nodes;
    var state = 'idle', col = STATES.idle.c.slice(), energy = 0.08, calm = 0, level = 0, levelAt = -1e9;
    var W = 0, H = 0, dpr = 1, S = 1, CX = 0, CY = 0, clock = 0, last = 0, running = false;
    var still = root.matchMedia && root.matchMedia('(prefers-reduced-motion: reduce)').matches;
    function rgba(a, lift) {
      var l = lift || 0;
      return 'rgba(' + Math.round(col[0] + (255 - col[0]) * l) + ',' + Math.round(col[1] + (255 - col[1]) * l) + ',' + Math.round(col[2] + (255 - col[2]) * l) + ',' + Math.max(0, Math.min(1, a)) + ')';
    }
    function resize() {
      var box = canvas.getBoundingClientRect(), nd = Math.min(2, root.devicePixelRatio || 1);
      var nw = Math.max(1, Math.round(box.width * nd)), nh = Math.max(1, Math.round(box.height * nd));
      if (nw === canvas.width && nh === canvas.height && nd === dpr) return;
      dpr = nd; canvas.width = nw; canvas.height = nh; W = box.width; H = box.height;
      S = Math.min(W * 0.84 / 1.62, H * 0.42 / 1.4); CX = W / 2; CY = H / 2 - S * 0.05;
    }
    function loudness() {
      if (clock - levelAt < 0.35) return level;
      var t = clock;
      if (state === 'speaking') { var talk = Math.abs(Math.sin(t * 6.1) * 0.55 + Math.sin(t * 9.7 + 1) * 0.35 + Math.sin(t * 2.3) * 0.4); return Math.min(1, talk * (Math.sin(t * 0.9) > -0.55 ? 1 : 0.15)); }
      if (state === 'listening') return 0.18 + 0.12 * Math.abs(Math.sin(t * 2.2));
      return 0.04;
    }

    function draw(dt) {
      calm += (loudness() - calm) * Math.min(1, dt * 4);
      var k = Math.min(1, dt * 4), goal = STATES[state], loud = calm;
      for (var i = 0; i < 3; i++) col[i] += (goal.c[i] - col[i]) * k;
      energy += (goal.e - energy) * k;

      // nearer points shift a little more than farther ones, as if the brain were turning
      var turn = Math.sin(clock * 0.35) * 0.035, nod = Math.sin(clock * 0.27 + 1) * 0.012, breathe = 1 + 0.012 * Math.sin(clock * 1.1) + loud * 0.025;
      function PX(x, z) { return CX + (x + (z - 0.5) * turn) * S * breathe; }
      function PY(y, z) { return CY + (y + (z - 0.5) * nod) * S * breathe; }
      nodes.forEach(function (n) { n.sx = PX(n.x, n.z); n.sy = PY(n.y, n.z); n.glow = Math.max(0, n.glow - dt * 1.6); });

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      var back = ctx.createRadialGradient(CX, CY, 0, CX, CY, S * 1.35);
      back.addColorStop(0, rgba(0.13 + loud * 0.12)); back.addColorStop(1, rgba(0));
      ctx.fillStyle = back; ctx.fillRect(0, 0, W, H);

      // the outline and the folds, softly
      function path(list, close) { ctx.beginPath(); list.forEach(function (p, i) { if (i) ctx.lineTo(PX(p[0], 0.5), PY(p[1], 0.5)); else ctx.moveTo(PX(p[0], 0.5), PY(p[1], 0.5)); }); if (close) ctx.closePath(); }
      path(RIM, true); ctx.fillStyle = rgba(0.045 + energy * 0.03); ctx.fill();
      ctx.strokeStyle = rgba(0.10 + energy * 0.06); ctx.lineWidth = S * 0.035; ctx.stroke();
      ctx.strokeStyle = rgba(0.42 + energy * 0.2, 0.15); ctx.lineWidth = Math.max(0.8, S * 0.006); ctx.stroke();
      CREASES.forEach(function (f) { path(f, false); ctx.strokeStyle = rgba(0.16 + energy * 0.08); ctx.lineWidth = Math.max(0.7, S * 0.005); ctx.stroke(); });

      // lines: the far ones faint, the near ones clear
      [[0, 0.35, 0.13], [0.35, 0.7, 0.22], [0.7, 1.01, 0.36]].forEach(function (band) {
        ctx.beginPath();
        m.edges.forEach(function (e) {
          var a = nodes[e[0]], b = nodes[e[1]], z = (a.z + b.z) / 2;
          if (z < band[0] || z >= band[1]) return;
          ctx.moveTo(a.sx, a.sy); ctx.lineTo(b.sx, b.sy);
        });
        ctx.strokeStyle = rgba(band[2] + energy * 0.1); ctx.lineWidth = Math.max(0.5, S * 0.0042); ctx.stroke();
      });

      // signals, each drawing a short bright tail along its line
      var active = Math.round(5 + energy * 34), speed = 0.3 + energy * 1.1 + loud * 0.7;
      for (var s = 0; s < active; s++) {
        var p = m.pulses[s], a = nodes[p.a], b = nodes[p.b];
        p.p += dt * p.v * speed;
        if (p.p >= 1) {
          b.glow = 1; p.p = 0; var from = p.a; p.a = p.b;
          var next = b.links.filter(function (j) { return j !== from; });
          p.b = next.length ? next[Math.floor(Math.random() * next.length)] : from; a = nodes[p.a]; b = nodes[p.b];
        }
        var hx = a.sx + (b.sx - a.sx) * p.p, hy = a.sy + (b.sy - a.sy) * p.p, t0 = Math.max(0, p.p - 0.45);
        var tx = a.sx + (b.sx - a.sx) * t0, ty = a.sy + (b.sy - a.sy) * t0;
        var tail = ctx.createLinearGradient(tx, ty, hx, hy);
        tail.addColorStop(0, rgba(0)); tail.addColorStop(1, rgba(0.9, 0.35));
        ctx.strokeStyle = tail; ctx.lineWidth = Math.max(1, S * 0.008); ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(hx, hy); ctx.stroke();
        ctx.fillStyle = rgba(1, 0.65); ctx.beginPath(); ctx.arc(hx, hy, Math.max(1.2, S * 0.0095), 0, TAU); ctx.fill();
      }

      // points: small and dim at the back, larger in front; a few hubs glow; any point swells when a signal arrives
      nodes.forEach(function (n) {
        var g = n.glow * n.glow, base = S * (0.0055 + n.z * 0.0065) * (n.hub ? 1.9 : 1);
        var twinkle = n.hub ? 0.25 + 0.2 * Math.sin(clock * 0.9 + n.ph) + loud * 0.35 : 0;
        if (n.hub || g > 0.05) {
          var hr = base * (3.2 + g * 3 + twinkle * 2.5), halo = ctx.createRadialGradient(n.sx, n.sy, 0, n.sx, n.sy, hr);
          halo.addColorStop(0, rgba(0.26 + g * 0.4 + twinkle * 0.3, 0.25)); halo.addColorStop(1, rgba(0));
          ctx.fillStyle = halo; ctx.beginPath(); ctx.arc(n.sx, n.sy, hr, 0, TAU); ctx.fill();
        }
        ctx.fillStyle = rgba(0.3 + n.z * 0.5 + g * 0.4 + twinkle * 0.3, (n.hub ? 0.45 : 0.05) + g * 0.6);
        ctx.beginPath(); ctx.arc(n.sx, n.sy, Math.max(0.8, base * (1 + g * 0.9)), 0, TAU); ctx.fill();
      });
    }
    function frame(now) { if (!running) return; var dt = Math.min(0.05, (now - last) / 1000 || 0.016); last = now; clock += dt; resize(); draw(dt); root.requestAnimationFrame(frame); }
    function settle() { resize(); for (var i = 0; i < 40; i++) { clock += 0.05; draw(0.05); } }
    this.setState = function (s) { if (STATES[s]) state = s; if (still) settle(); };
    this.setLevel = function (v) { level = Math.max(0, Math.min(1, Number(v) || 0)); levelAt = clock; };
    this.start = function () { if (still) { settle(); root.addEventListener('resize', settle); return; } if (running) return; running = true; last = 0; root.requestAnimationFrame(frame); };
    this.stop = function () { running = false; };
  }
  root.MunshiVisual = { create: function (c) { return new Visual(c); } };
})(typeof self !== 'undefined' ? self : this);
