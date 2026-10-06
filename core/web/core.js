/* FAZE core: one abstract form that morphs between silhouettes inspired by the models behind it.
   Pure shape helpers are unit-tested under node; the canvas class runs in the browser. */
(function (root) {
  'use strict';

  const N = 120;
  const TAU = Math.PI * 2;

  // Radius as a function of angle. Original geometry, not logos: each form only echoes the
  // character of a model family (lobes, spikes, points, squircle, lopsided moon, ripples).
  const SHAPE_FNS = {
    faze: (t) => 1 + 0.025 * Math.cos(3 * t + 0.6),
    gpt: (t) => 0.84 + 0.16 * Math.cos(6 * t) + 0.035 * Math.cos(12 * t + 0.5),
    claude: (t) => 0.7 + 0.3 * Math.pow(Math.abs(Math.cos(6 * t)), 0.55),
    gemini: (t) => 0.42 + 0.58 * Math.pow(Math.abs(Math.cos(2 * t)), 2.4),
    qwen: (t) => 1 / Math.pow(Math.pow(Math.abs(Math.cos(t)), 4) + Math.pow(Math.abs(Math.sin(t)), 4), 0.25),
    kimi: (t) => 0.66 + 0.26 * Math.cos(t - 0.7) + 0.1 * Math.cos(2 * (t - 0.7)) + 0.03 * Math.cos(5 * t),
    lfm: (t) => 0.86 + 0.075 * Math.cos(8 * t) + 0.05 * Math.cos(3 * t + 1.1),
  };
  const CYCLE = ['faze', 'gpt', 'claude', 'gemini', 'qwen', 'kimi', 'lfm'];
  // Which form each backend wears. New models only need a line here (and a shape above).
  const MODEL_FORM = { auto: 'faze', faze: 'faze', qwen: 'qwen', lfm: 'lfm', gemini: 'gemini', regex: 'faze', intent: 'faze', needle: 'faze' };
  const _cache = {};

  function radii(name) {
    if (_cache[name]) return _cache[name];
    const fn = SHAPE_FNS[name] || SHAPE_FNS.faze;
    const out = new Float32Array(N);
    let max = 0;
    for (let i = 0; i < N; i++) { out[i] = fn((i / N) * TAU); if (out[i] > max) max = out[i]; }
    for (let i = 0; i < N; i++) out[i] /= max;
    return (_cache[name] = out);
  }

  function mixRadii(a, b, t, out) {
    out = out || new Float32Array(N);
    for (let i = 0; i < N; i++) out[i] = a[i] + (b[i] - a[i]) * t;
    return out;
  }

  const smooth = (t) => t * t * (3 - 2 * t);
  // Slight overshoot, like a spring settling.
  const spring = (t) => { const c = 1.35; const u = t - 1; return 1 + (c + 1) * u * u * u + c * u * u; };

  function parseColor(text, fallback) {
    const m = String(text || '').trim().match(/^#([0-9a-f]{6})$/i);
    if (!m) return fallback;
    const n = parseInt(m[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  const instances = new Set();
  const theme = { accent: [92, 200, 255], accent2: [61, 123, 255] };
  let reduced = false;
  let rafId = 0;
  let last = 0;

  function refreshTheme() {
    if (typeof getComputedStyle === 'undefined' || typeof document === 'undefined') return;
    const css = getComputedStyle(document.documentElement);
    theme.accent = parseColor(css.getPropertyValue('--accent'), theme.accent);
    theme.accent2 = parseColor(css.getPropertyValue('--accent-2'), theme.accent2);
    instances.forEach((c) => c.draw());
  }

  function loop(ts) {
    rafId = 0;
    if (reduced || !instances.size) return;
    const dt = Math.min(0.05, (ts - last) / 1000 || 0.016);
    last = ts;
    if (typeof document === 'undefined' || !document.hidden) instances.forEach((c) => { c.step(dt); if (c.canvas.clientWidth) c.draw(); });
    rafId = root.requestAnimationFrame(loop);
  }

  function kick() {
    if (!rafId && !reduced && instances.size && root.requestAnimationFrame) { last = 0; rafId = root.requestAnimationFrame(loop); }
  }

  function setReduced(value) {
    reduced = !!value;
    if (reduced) { if (rafId && root.cancelAnimationFrame) root.cancelAnimationFrame(rafId); rafId = 0; instances.forEach((c) => c.draw()); }
    else kick();
  }

  class FazeCore {
    constructor(canvas, opts) {
      this.canvas = canvas;
      this.opts = Object.assign({ scale: 0.3 }, opts || {});
      this.ctx = null;
      try { this.ctx = canvas.getContext('2d'); } catch (e) { this.ctx = null; }
      this.cur = Float32Array.from(radii('faze'));
      this.from = Float32Array.from(this.cur);
      this.to = radii('faze');
      this.t = 1; this.dur = 1; this.hold = 0;
      this.cycleIndex = 0;
      this.cycle = true;
      this.state = 'idle';
      this.level = 0; this.pulseV = 0;
      this.time = Math.random() * 10; this.rot = 0;
      this.frags = 0;
      this.minis = [];
      this.size = 0; this.dpr = 1;
      instances.add(this);
      this.resize();
      kick();
    }

    destroy() { instances.delete(this); }

    resize() {
      const c = this.canvas;
      const dpr = Math.min(2, (root.devicePixelRatio || 1));
      const w = c.clientWidth || this.opts.size || 64;
      const h = c.clientHeight || this.opts.size || 64;
      this.size = Math.min(w, h);
      this.dpr = dpr;
      if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
      this.draw();
    }

    // Morph into a model's form and hold it. null goes back to cycling through every form.
    setModel(id) {
      if (!id || id === 'auto') { if (!this.cycle) { this.cycle = true; this.hold = 0.4; } return; }
      this.cycle = false;
      this._morphTo(radii(MODEL_FORM[id] || id), 0.95);
    }

    _morphTo(target, seconds) {
      if (this.to === target && this.t >= 1) return;
      this.from = Float32Array.from(this.cur);
      this.to = target;
      this.t = 0;
      this.dur = seconds;
    }

    setState(state, extra) {
      this.state = state;
      this.frags = state === 'tool' ? Math.max(3, Math.min(9, (extra && extra.fragments) || 5)) : 0;
      this.draw();
    }

    setLevel(v) { this.level = Math.max(0, Math.min(1, v)); }
    pulse(amount) { this.pulseV = Math.min(0.5, this.pulseV + (amount || 0.08)); }

    // Several models contributed: small forms appear around the core and flow into it.
    burst(models) {
      const list = (models && models.length ? models : ['gpt', 'claude', 'gemini']).slice(0, 6);
      list.forEach((m, i) => {
        this.minis.push({ r: radii(MODEL_FORM[m] || m), a: (i / list.length) * TAU + Math.random() * 0.6, t: -i * 0.12, life: 1.5 });
      });
      this.pulse(0.05);
    }

    step(dt) {
      const fast = this.state === 'thinking' || this.state === 'tool' ? 2.4 : 1;
      this.time += dt * fast;
      this.rot += dt * 0.07 * fast;
      if (this.t < 1) {
        this.t = Math.min(1, this.t + dt / this.dur);
        mixRadii(this.from, this.to, spring(this.t) > 1.08 ? 1.08 : spring(this.t), this.cur);
      } else if (this.cycle) {
        this.hold -= dt * fast;
        if (this.hold <= 0) {
          this.cycleIndex = (this.cycleIndex + 1) % CYCLE.length;
          this.hold = 1.1;
          this._morphTo(radii(CYCLE[this.cycleIndex]), this.state === 'idle' ? 2.8 : 1.6);
        }
      }
      this.pulseV *= Math.pow(0.04, dt);
      for (const m of this.minis) {
        m.t += dt / m.life;
        if (m.t >= 1 && !m.done) { m.done = true; this.pulse(0.1); }
      }
      this.minis = this.minis.filter((m) => m.t < 1.05);
    }

    _path(ctx, cx, cy, R, radiiArr, rot) {
      const pts = [];
      for (let i = 0; i < N; i++) {
        const a = (i / N) * TAU + rot;
        pts.push([cx + Math.cos(a) * R * radiiArr[i], cy + Math.sin(a) * R * radiiArr[i]]);
      }
      ctx.beginPath();
      for (let i = 0; i < N; i++) {
        const p = pts[i], q = pts[(i + 1) % N];
        const mx = (p[0] + q[0]) / 2, my = (p[1] + q[1]) / 2;
        if (i === 0) ctx.moveTo(mx, my);
        else ctx.quadraticCurveTo(p[0], p[1], mx, my);
      }
      const p0 = pts[0], q0 = pts[1];
      ctx.quadraticCurveTo(p0[0], p0[1], (p0[0] + q0[0]) / 2, (p0[1] + q0[1]) / 2);
      ctx.closePath();
    }

    draw() {
      const ctx = this.ctx;
      if (!ctx) return;
      const s = this.canvas.width, cx = s / 2, cy = this.canvas.height / 2;
      const px = this.dpr;
      const R = Math.min(this.canvas.width, this.canvas.height) * this.opts.scale;
      const [r1, g1, b1] = theme.accent, [r2, g2, b2] = theme.accent2;
      ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

      const breathe = reduced ? 0 : Math.sin(this.time * 1.1) * 0.035;
      const scale = 1 + breathe + this.level * 0.16 + this.pulseV;
      const glow = 0.5 + this.level * 0.5 + this.pulseV * 2 + (this.state === 'thinking' ? 0.25 : 0);

      // soft light behind the form
      const halo = ctx.createRadialGradient(cx, cy, R * 0.2, cx, cy, R * 2.3);
      halo.addColorStop(0, `rgba(${r1},${g1},${b1},${0.20 * glow})`);
      halo.addColorStop(1, `rgba(${r1},${g1},${b1},0)`);
      ctx.fillStyle = halo;
      ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

      // orbiting rings while it works
      if (this.state === 'thinking' || this.state === 'tool') {
        ctx.lineWidth = 1 * px;
        for (let k = 0; k < 2; k++) {
          ctx.strokeStyle = `rgba(${r2},${g2},${b2},${0.35 - k * 0.12})`;
          ctx.beginPath();
          ctx.ellipse(cx, cy, R * (1.5 + k * 0.28), R * (0.62 + k * 0.18), this.rot * (k ? -3 : 3) + k, 0, TAU);
          ctx.stroke();
        }
        for (let k = 0; k < 7; k++) {
          const a = this.time * (0.9 + (k % 3) * 0.25) + k * 0.9;
          ctx.fillStyle = `rgba(${r1},${g1},${b1},${0.75 - (k % 3) * 0.2})`;
          ctx.beginPath();
          ctx.arc(cx + Math.cos(a) * R * 1.5, cy + Math.sin(a * 1.05) * R * 0.7, Math.max(1, R * 0.045), 0, TAU);
          ctx.fill();
        }
      }

      // tool fragments: small shards circling the form
      for (let k = 0; k < this.frags; k++) {
        const a = this.time * (1.2 + (k % 3) * 0.3) + (k / this.frags) * TAU;
        const d = R * (1.55 + (k % 2) * 0.25);
        const x = cx + Math.cos(a) * d, y = cy + Math.sin(a) * d * 0.85, sz = R * 0.12;
        ctx.save();
        ctx.translate(x, y);
        ctx.rotate(a * 2);
        ctx.fillStyle = `rgba(${r2},${g2},${b2},0.8)`;
        ctx.beginPath();
        ctx.moveTo(0, -sz); ctx.lineTo(sz * 0.8, sz * 0.7); ctx.lineTo(-sz * 0.8, sz * 0.7);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
      }

      // the form itself
      this._path(ctx, cx, cy, R * scale, this.cur, this.rot);
      const body = ctx.createRadialGradient(cx - R * 0.25, cy - R * 0.3, R * 0.1, cx, cy, R * 1.1);
      body.addColorStop(0, `rgba(${r1},${g1},${b1},${0.34 + 0.2 * glow})`);
      body.addColorStop(0.7, `rgba(${r2},${g2},${b2},0.10)`);
      body.addColorStop(1, `rgba(${r2},${g2},${b2},0.03)`);
      ctx.fillStyle = body;
      ctx.fill();
      ctx.lineJoin = 'round';
      ctx.strokeStyle = `rgba(${r1},${g1},${b1},${0.12 + 0.12 * glow})`;
      ctx.lineWidth = Math.max(3, R * 0.07);
      ctx.stroke();
      const edge = ctx.createLinearGradient(cx - R, cy - R, cx + R, cy + R);
      edge.addColorStop(0, `rgba(${r1},${g1},${b1},0.95)`);
      edge.addColorStop(1, `rgba(${r2},${g2},${b2},0.7)`);
      ctx.strokeStyle = edge;
      ctx.lineWidth = Math.max(1, R * 0.022);
      ctx.stroke();

      // other models flowing into the core
      for (const m of this.minis) {
        if (m.t < 0) continue;
        const e = smooth(Math.min(1, m.t));
        const dist = R * (2.6 * (1 - e));
        const mx = cx + Math.cos(m.a + this.time * 0.6 * (1 - e)) * dist;
        const my = cy + Math.sin(m.a + this.time * 0.6 * (1 - e)) * dist;
        this._path(ctx, mx, my, R * 0.3 * (1 - e * 0.6), m.r, m.a);
        ctx.strokeStyle = `rgba(${r1},${g1},${b1},${0.85 * (1 - e * 0.7)})`;
        ctx.lineWidth = Math.max(1, R * 0.02);
        ctx.stroke();
      }
    }
  }

  const api = { N, SHAPE_FNS, CYCLE, MODEL_FORM, radii, mixRadii, smooth, spring, parseColor, FazeCore, refreshTheme, setReduced };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.FazeCoreKit = api;
})(typeof window !== 'undefined' ? window : globalThis);
