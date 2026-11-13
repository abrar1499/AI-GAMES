/*
 * Bolta Munshi - the screen: a wireframe face with its brain showing.
 *
 * A head seen from the front, built as a mesh of points and triangles like a
 * 3D scan. The brain sits inside the top of the skull as glowing folds, the
 * eyes glow, and the head sways slightly so the mesh reads as solid.
 *
 * The call drives it: quiet and blue when idle; green while listening, with
 * ripples running in from the ears; amber while speaking, with the jaw moving
 * and ripples running out from the mouth.
 *
 * Used by the web page and, inside a WebView, by the Android app.
 */
(function (root) {
  'use strict';

  var TAU = Math.PI * 2;
  var STATES = { idle: { c: [110, 190, 235], e: 0.08 }, listening: { c: [53, 211, 154], e: 0.5 }, speaking: { c: [245, 179, 77], e: 1 } };
  var MESH = [168, 200, 255], PINK = [255, 214, 208];

  function rand(seed) { var s = seed; return function () { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; }
  function smooth(a, b, v) { var t = Math.max(0, Math.min(1, (v - a) / (b - a))); return t * t * (3 - 2 * t); }

  // ---------------------------------------------------------------- shape ---
  // Everything is in "head units": x and y in a -1..1 box, y downwards. The
  // face follows the usual drawing proportions: eyes halfway down the head,
  // one eye-width apart; the nose ends halfway from eyes to chin.

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
  var EAR = { x: 0.535, y: 0.03, rx: 0.058, ry: 0.19 }, NECK = 0.2, NECK_END = 0.8;
  function inHead(x, y) {
    var ax = Math.abs(x);
    return ax < headHalf(y) || Math.pow((ax - EAR.x) / EAR.rx, 2) + Math.pow((y - EAR.y) / EAR.ry, 2) < 1 || (y > 0.45 && y < NECK_END && ax < NECK);
  }
  function inEye(x, y) { return Math.pow((Math.abs(x) - EYE_X) / 0.098, 2) + Math.pow((y - EYE_Y) / 0.042, 2) < 1; }
  /** The brain, seen from the front: two lobes side by side under the top of the skull. */
  function inLobe(x, y) {
    var ax = Math.abs(x);
    return ax > 0.014 && y > -0.815 && y < -0.3 + 0.05 * Math.pow(ax / 0.45, 2) && ax < headHalf(y) - 0.035;
  }
  /** How far a point of the face stands out towards the viewer. */
  function depth(x, y) {
    var z = 0.55 * Math.sqrt(Math.max(0, 1 - Math.pow(x / 0.57, 2) - Math.pow((y + 0.1) / 0.83, 2)));
    z += 0.2 * Math.exp(-Math.pow(x / 0.065, 2) - Math.pow((y - 0.15) / 0.13, 2));          // nose
    z += 0.04 * Math.exp(-Math.pow(x / 0.16, 2) - Math.pow((y - MOUTH_Y - 0.02) / 0.06, 2)); // lips
    z -= 0.05 * Math.exp(-Math.pow((Math.abs(x) - EYE_X) / 0.1, 2) - Math.pow((y - EYE_Y) / 0.06, 2));   // eye sockets
    return z;
  }

  // ----------------------------------------------------------------- mesh ---

  /** Bowyer-Watson triangulation; returns triangles as index triples. */
  function triangulate(pts) {
    var n = pts.length, all = pts.concat([{ x: -20, y: -20 }, { x: 20, y: -20 }, { x: 0, y: 20 }]);
    function tri(a, b, c) {
      var A = all[a], B = all[b], C = all[c];
      var d = 2 * (A.x * (B.y - C.y) + B.x * (C.y - A.y) + C.x * (A.y - B.y));
      var ux = ((A.x * A.x + A.y * A.y) * (B.y - C.y) + (B.x * B.x + B.y * B.y) * (C.y - A.y) + (C.x * C.x + C.y * C.y) * (A.y - B.y)) / d;
      var uy = ((A.x * A.x + A.y * A.y) * (C.x - B.x) + (B.x * B.x + B.y * B.y) * (A.x - C.x) + (C.x * C.x + C.y * C.y) * (B.x - A.x)) / d;
      return { v: [a, b, c], x: ux, y: uy, r: (A.x - ux) * (A.x - ux) + (A.y - uy) * (A.y - uy) };
    }
    var tris = [tri(n, n + 1, n + 2)];
    for (var i = 0; i < n; i++) {
      var p = pts[i], keep = [], edges = {};
      tris.forEach(function (t) {
        if ((p.x - t.x) * (p.x - t.x) + (p.y - t.y) * (p.y - t.y) >= t.r) { keep.push(t); return; }
        for (var k = 0; k < 3; k++) {
          var a = t.v[k], b = t.v[(k + 1) % 3], key = a < b ? a + '_' + b : b + '_' + a;
          if (edges[key]) edges[key] = null; else if (edges[key] === undefined) edges[key] = [a, b];   // shared edges cancel out
        }
      });
      Object.keys(edges).forEach(function (k) { if (edges[k]) keep.push(tri(edges[k][0], edges[k][1], i)); });
      tris = keep;
    }
    return tris.filter(function (t) { return t.v[0] < n && t.v[1] < n && t.v[2] < n; }).map(function (t) { return t.v; });
  }

  function build() {
    var r = rand(23), pts = [], lines = {};
    function add(x, y, tag) {
      var p = { x: x + (r() - 0.5) * 2e-4, y: y + (r() - 0.5) * 2e-4, tag: tag || '', glow: 0 };
      pts.push(p); return pts.length - 1;
    }
    function both(x, y, tag) { return [add(x, y, tag), add(-x, y, tag)]; }

    // the outline: down one side of the skull, round the chin, up the other
    var right = [], left = [], y;
    var crown = add(0, -0.85);
    for (y = -0.82; y < 0.62; y += (y > 0.4 || y < -0.7 ? 0.03 : 0.052)) { right.push(add(headHalf(y), y)); left.push(add(-headHalf(y), y)); }
    var chin = add(0, 0.625);
    lines.outline = [crown].concat(right, [chin], left.reverse(), [crown]);

    // ears and a short neck
    lines.ears = [];
    [-1, 1].forEach(function (side) {
      var ear = [];
      for (var a = -1.5; a <= 1.51; a += 0.375) ear.push(add(side * (EAR.x + Math.cos(a) * EAR.rx), EAR.y + Math.sin(a) * EAR.ry));
      lines.ears.push(ear);
      for (var ny = 0.58; ny < NECK_END; ny += 0.07) add(side * NECK, ny);
    });

    // eyes: a ring of points round each opening, which close when it blinks
    lines.eyes = [-1, 1].map(function (side) {
      var ring = [];
      for (var k = 0; k < 14; k++) {
        var a = k / 14 * TAU, up = Math.sin(a) > 0;
        ring.push(add(side * (EYE_X + Math.cos(a) * 0.1), EYE_Y - Math.sin(a) * (up ? 0.046 : 0.03) - Math.cos(a) * side * 0.006, 'eye'));
      }
      return ring.concat([ring[0]]);
    });
    // brows
    lines.brows = [-1, 1].map(function (side) {
      return [[-0.115, -0.105], [-0.06, -0.14], [0.0, -0.158], [0.06, -0.156], [0.11, -0.135], [0.145, -0.112]].map(function (q) { return add(side * (EYE_X + q[0]), EYE_Y + q[1]); });
    });
    // nose: ridge, sides, wings and tip
    [[0, -0.06], [0, 0.03], [0, 0.11], [0, 0.185], [0, 0.232]].forEach(function (q) { add(q[0], q[1]); });
    var n1 = both(0.034, -0.05), n2 = both(0.044, 0.06), n3 = both(0.062, 0.15);
    var wing = both(0.103, 0.212), base = both(0.064, 0.246), mid = both(0.03, 0.262), tip = add(0, 0.27);
    lines.nose = [n1[0], n2[0], n3[0], wing[0], base[0], mid[0], tip, mid[1], base[1], wing[1], n3[1], n2[1], n1[1]];
    // lips: the upper edge with its bow, the line where they meet, and the lower lip, which moves with the jaw
    var cR = add(0.168, MOUTH_Y), cL = add(-0.168, MOUTH_Y);
    var upR = [[0.115, -0.022], [0.07, -0.042], [0.038, -0.046], [0.016, -0.04]].map(function (q) { return add(q[0], MOUTH_Y + q[1]); });
    var upL = [[-0.016, -0.04], [-0.038, -0.046], [-0.07, -0.042], [-0.115, -0.022]].map(function (q) { return add(q[0], MOUTH_Y + q[1]); });
    var bow = add(0, MOUTH_Y - 0.031);
    var meet = [0.11, 0.055, 0, -0.055, -0.11].map(function (x) { return add(x, MOUTH_Y + (x === 0 ? -0.001 : 0.003)); });
    var lowTop = [0.11, 0.055, 0, -0.055, -0.11].map(function (x) { return add(x, MOUTH_Y + 0.011, 'jaw'); });
    var low = [[0.12, 0.052], [0.065, 0.078], [0, 0.085], [-0.065, 0.078], [-0.12, 0.052]].map(function (q) { return add(q[0], MOUTH_Y + q[1], 'jaw'); });
    lines.lips = [[cR].concat(upR, [bow], upL, [cL]), [cR].concat(meet, [cL]), [cR].concat(lowTop, [cL]), [cR].concat(low, [cL])];

    // then fill the rest of the face evenly
    var guard = 0;
    while (pts.length < 470 && guard++ < 90000) {
      var x = r() * 1.3 - 0.65, yy = r() * 1.75 - 0.9;
      if (!inHead(x, yy) || inEye(x, yy)) continue;
      if (pts.some(function (p) { return Math.hypot(p.x - x, p.y - yy) < 0.058; })) continue;
      add(x, yy);
    }

    pts.forEach(function (p) {
      var ax = Math.abs(p.x), hw = headHalf(p.y);
      p.z = depth(p.x, p.y);
      p.fade = 1 - smooth(0.56, NECK_END, p.y) * (ax < hw ? 0.3 : 1);                    // the neck dissolves downwards
      // how much this point follows the jaw: the lower lip fully, the cheeks hardly at all
      p.jaw = p.tag === 'jaw' ? 1 : (ax < hw ? smooth(MOUTH_Y + 0.01, MOUTH_Y + 0.07, p.y) * (1 - 0.55 * Math.pow(ax / hw, 2)) : 0);
      p.dMouth = Math.hypot(p.x, p.y - MOUTH_Y);
      p.dEar = Math.hypot(Math.abs(p.x) - EAR.x, p.y - EAR.y);
      p.lobe = inLobe(p.x, p.y);
    });

    // triangles that lie on the face, not across the eyes or the gaps beside the neck
    var seen = {}, groups = { front: [], mid: [], rim: [], lobe: [], neck: [] };
    triangulate(pts).forEach(function (t) {
      var A = pts[t[0]], B = pts[t[1]], C = pts[t[2]], cx = (A.x + B.x + C.x) / 3, cy = (A.y + B.y + C.y) / 3;
      if (!inHead(cx, cy) || inEye(cx, cy)) return;
      for (var k = 0; k < 3; k++) {
        var a = t[k], b = t[(k + 1) % 3], P = pts[a], Q = pts[b], key = a < b ? a + '_' + b : b + '_' + a;
        if (seen[key] || Math.hypot(P.x - Q.x, P.y - Q.y) > 0.2) continue;
        seen[key] = 1;
        var z = (P.z + Q.z) / 2, my = (P.y + Q.y) / 2;
        (my > 0.6 ? groups.neck : P.lobe && Q.lobe ? groups.lobe : z > 0.42 ? groups.front : z > 0.24 ? groups.mid : groups.rim).push(a, b);
      }
    });

    // the brain's folds: wandering paths that stay inside each lobe
    var folds = [];
    [-1, 1].forEach(function (side) {
      for (var w = 0; w < 30; w++) {
        var fx, fy, tries = 0;
        do { fx = side * (0.02 + r() * 0.46); fy = -0.83 + r() * 0.56; } while (!inLobe(fx, fy) && tries++ < 200);
        if (!inLobe(fx, fy)) continue;
        var ang = r() * TAU, path = [{ x: fx, y: fy }], steps = 24 + Math.floor(r() * 26);
        for (var s = 0; s < steps; s++) {
          ang += (r() - 0.5) * 1.3;
          var ok = false;
          for (var turn = 0; turn < 8 && !ok; turn++) {
            var nx = fx + Math.cos(ang) * 0.02, ny = fy + Math.sin(ang) * 0.02;
            if (inLobe(nx, ny)) { fx = nx; fy = ny; ok = true; } else ang += 0.7;
          }
          if (!ok) break;
          path.push({ x: fx, y: fy });
        }
        path.forEach(function (q) { q.z = depth(q.x, q.y) * 0.92; });
        if (path.length > 6) folds.push({ path: path, at: r(), speed: 0.3 + r() * 0.5, live: false });
      }
    });
    return { pts: pts, lines: lines, groups: groups, folds: folds };
  }

  // --------------------------------------------------------------- screen ---

  function Visual(canvas) {
    var ctx = canvas.getContext('2d'), model = build(), pts = model.pts;
    var state = 'idle', col = STATES.idle.c.slice(), energy = STATES.idle.e;
    var level = 0, levelAt = -1e9;                      // loudness handed in by the app, when it has one
    var W = 0, H = 0, dpr = 1, S = 1, CX = 0, CY = 0, clock = 0, last = 0, running = false;
    var blinkAt = 2.5, mouth = 0, yaw = 0, pitch = 0, lids = 1;
    var still = root.matchMedia && root.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function rgb(c, a) { return 'rgba(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ',' + Math.max(0, Math.min(1, a)) + ')'; }
    function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]; }

    function resize() {
      var box = canvas.getBoundingClientRect();
      var nd = Math.min(2, root.devicePixelRatio || 1), nw = Math.max(1, Math.round(box.width * nd)), nh = Math.max(1, Math.round(box.height * nd));
      if (nw === canvas.width && nh === canvas.height && nd === dpr) return;
      dpr = nd; canvas.width = nw; canvas.height = nh; W = box.width; H = box.height;
      S = Math.min(W * 0.66 / 1.2, H * 0.44 / 1.5); CX = W / 2; CY = H / 2 + S * 0.04;      // small, in the middle
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

    /** Turn the head slightly and drop the jaw; fills in each point's place on screen. */
    function place(p) {
      var y = p.y + (p.jaw ? p.jaw * mouth * 0.05 : 0);
      if (p.tag === 'eye') y = EYE_Y + (y - EYE_Y) * lids;
      var x1 = p.x * Math.cos(yaw) + p.z * Math.sin(yaw), z1 = -p.x * Math.sin(yaw) + p.z * Math.cos(yaw);
      p.sx = CX + x1 * S; p.sy = CY + (y * Math.cos(pitch) - z1 * Math.sin(pitch)) * S;
    }
    function trace(list) { list.forEach(function (i, k) { if (k) ctx.lineTo(pts[i].sx, pts[i].sy); else ctx.moveTo(pts[i].sx, pts[i].sy); }); }
    function strokeEdges(list, alpha, width) {
      ctx.beginPath();
      for (var i = 0; i < list.length; i += 2) { ctx.moveTo(pts[list[i]].sx, pts[list[i]].sy); ctx.lineTo(pts[list[i + 1]].sx, pts[list[i + 1]].sy); }
      ctx.strokeStyle = rgb(MESH, alpha); ctx.lineWidth = width; ctx.stroke();
    }

    function draw(dt) {
      var loud = loudness(), k = Math.min(1, dt * 5), goal = STATES[state];
      for (var i = 0; i < 3; i++) col[i] += (goal.c[i] - col[i]) * k;
      energy += (goal.e - energy) * k;
      mouth += ((state === 'speaking' ? loud : 0) - mouth) * Math.min(1, dt * 18);
      if (clock > blinkAt + 0.16) blinkAt = clock + 2.4 + Math.random() * 3.5;
      lids = clock >= blinkAt ? 1 - 0.9 * Math.sin((clock - blinkAt) / 0.16 * Math.PI) : 1;
      yaw = 0.13 * Math.sin(clock * 0.42); pitch = 0.04 * Math.sin(clock * 0.31 + 1);
      pts.forEach(place);
      model.folds.forEach(function (f) { f.path.forEach(place); });

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';

      // light behind the head, and a faint skin of colour over the face
      var back = ctx.createRadialGradient(CX, CY - S * 0.1, 0, CX, CY - S * 0.1, S * 1.7);
      back.addColorStop(0, rgb(mix([40, 70, 200], col, 0.35), 0.20 + loud * 0.10)); back.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = back; ctx.fillRect(0, 0, W, H);
      ctx.beginPath(); trace(model.lines.outline); ctx.closePath();
      var skin = ctx.createLinearGradient(0, CY - S * 0.85, 0, CY + S * 0.65);
      skin.addColorStop(0, 'rgba(60,90,220,0.04)'); skin.addColorStop(0.55, 'rgba(50,110,255,0.20)'); skin.addColorStop(1, 'rgba(40,80,200,0.06)');
      ctx.fillStyle = skin; ctx.fill();

      // the brain: each fold is a soft tube with a bright core; signals run along the busy ones
      var brain = mix(PINK, col, 0.12 + energy * 0.38), busy = 0.45 + energy * 0.3 + loud * 0.25;
      [-1, 1].forEach(function (side) {
        var c = { x: side * 0.22, y: -0.55, z: 0.3, jaw: 0, tag: '' }; place(c);
        var g = ctx.createRadialGradient(c.sx, c.sy, 0, c.sx, c.sy, S * 0.34);
        g.addColorStop(0, rgb(brain, 0.30 * busy)); g.addColorStop(1, rgb(brain, 0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.ellipse(c.sx, c.sy, S * 0.26, S * 0.32, 0, 0, TAU); ctx.fill();
      });
      ctx.beginPath();
      model.folds.forEach(function (f) { f.path.forEach(function (q, n) { if (n) ctx.lineTo(q.sx, q.sy); else ctx.moveTo(q.sx, q.sy); }); });
      ctx.strokeStyle = rgb(brain, 0.24 * busy); ctx.lineWidth = Math.max(2, S * 0.036); ctx.stroke();
      ctx.strokeStyle = rgb(mix(brain, [255, 255, 255], 0.35), 0.75 * busy); ctx.lineWidth = Math.max(0.9, S * 0.01); ctx.stroke();
      model.folds.forEach(function (f) {
        if (!f.live) { if (Math.random() < dt * (0.03 + energy * 0.5 + loud * energy * 1.4)) { f.live = true; f.at = 0; } return; }
        f.at += dt * f.speed * (0.8 + energy * 1.2);
        if (f.at >= 1) { f.live = false; return; }
        var q = f.path[Math.min(f.path.length - 1, Math.floor(f.at * f.path.length))];
        var g = ctx.createRadialGradient(q.sx, q.sy, 0, q.sx, q.sy, S * 0.05);
        g.addColorStop(0, rgb(mix(col, [255, 255, 255], 0.6), 0.95)); g.addColorStop(1, rgb(col, 0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(q.sx, q.sy, S * 0.05, 0, TAU); ctx.fill();
      });

      // the mesh, brighter where the face comes towards you and faint over the brain
      var thin = Math.max(0.5, S * 0.0032);
      strokeEdges(model.groups.rim, 0.20, thin); strokeEdges(model.groups.mid, 0.32, thin); strokeEdges(model.groups.front, 0.46, thin);
      strokeEdges(model.groups.lobe, 0.13, thin); strokeEdges(model.groups.neck, 0.10, thin);

      // points: steady dots, plus ripples out from the mouth when speaking or in from the ears when listening
      var ring = (clock * (0.5 + energy * 0.5)) % 1.15, dotR = Math.max(0.7, S * 0.006);
      ctx.fillStyle = rgb(MESH, 0.75); ctx.beginPath();
      pts.forEach(function (p) { if (p.fade > 0.15) { ctx.moveTo(p.sx + dotR, p.sy); ctx.arc(p.sx, p.sy, dotR * (0.6 + p.fade * 0.4), 0, TAU); } });
      ctx.fill();
      if (state !== 'idle') {
        ctx.fillStyle = rgb(mix(col, [255, 255, 255], 0.3), 0.95); ctx.beginPath();
        pts.forEach(function (p) {
          var d = state === 'speaking' ? p.dMouth : 0.62 - p.dEar;
          var band = Math.exp(-Math.pow((d - ring) / 0.045, 2)) * (state === 'speaking' ? 0.3 + loud : 0.75) * p.fade;
          if (band > 0.2) { ctx.moveTo(p.sx + dotR * 3, p.sy); ctx.arc(p.sx, p.sy, dotR * (1 + band * 1.8), 0, TAU); }
        });
        ctx.fill();
      }

      // the lines that make it a face
      ctx.strokeStyle = rgb(mix(MESH, [255, 255, 255], 0.4), 0.85); ctx.lineWidth = Math.max(0.9, S * 0.0065);
      ctx.beginPath(); trace(model.lines.outline); model.lines.ears.forEach(trace); model.lines.brows.forEach(trace); trace(model.lines.nose); model.lines.lips.forEach(trace); ctx.stroke();

      // eyes: lit from inside, in the colour of what the call is doing
      model.lines.eyes.forEach(function (ringPts, n) {
        var ex = 0, ey = 0;
        ringPts.forEach(function (i) { ex += pts[i].sx; ey += pts[i].sy; }); ex /= ringPts.length; ey /= ringPts.length;
        var power = (0.55 + energy * 0.3 + loud * 0.15) * (0.25 + 0.75 * lids);
        var halo = ctx.createRadialGradient(ex, ey, 0, ex, ey, S * 0.26);
        halo.addColorStop(0, rgb(col, 0.55 * power)); halo.addColorStop(1, rgb(col, 0));
        ctx.fillStyle = halo; ctx.beginPath(); ctx.arc(ex, ey, S * 0.26, 0, TAU); ctx.fill();
        ctx.beginPath(); trace(ringPts); ctx.closePath();
        var core = ctx.createRadialGradient(ex, ey, 0, ex, ey, S * 0.1);
        core.addColorStop(0, rgb(mix(col, [255, 255, 255], 0.75), 0.95 * power)); core.addColorStop(1, rgb(col, 0.55 * power));
        ctx.fillStyle = core; ctx.fill();
        ctx.strokeStyle = rgb(mix(col, [255, 255, 255], 0.5), 0.9); ctx.lineWidth = Math.max(0.9, S * 0.006); ctx.stroke();
      });
    }

    function frame(now) {
      if (!running) return;
      var dt = Math.min(0.05, (now - last) / 1000 || 0.016); last = now; clock += dt;
      resize(); draw(dt);
      root.requestAnimationFrame(frame);
    }
    /** With reduced motion there is no animation: settle into one picture of the state. */
    function settle() { resize(); blinkAt = clock + 99; for (var i = 0; i < 40; i++) { clock += 0.05; draw(0.05); } }

    this.setState = function (s) { if (STATES[s]) state = s; if (still) settle(); };
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
