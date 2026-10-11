/* Faze web app. Pure helpers first (unit-tested under node), then the browser UI. */
(function () {
  'use strict';

  // ---------------------------------------------------------------- pure helpers

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  // Tiny, safe markdown: everything is escaped first, then only our own tags are added.
  function renderMarkdown(src) {
    const inline = (t) => t
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[\s(])\*([^*\s][^*]*)\*(?=[\s).,!?]|$)/g, '$1<em>$2</em>');
    const block = (lang, lines) => '<div class="code"><div class="code-head"><span>' + (lang || 'code') +
      '</span><button type="button" class="copy">Copy</button></div><pre><code>' + lines.join('\n') + '</code></pre></div>';
    const out = [];
    let list = '';
    let inCode = false;
    let lang = '';
    let code = [];
    const closeList = () => { if (list) { out.push('</' + list + '>'); list = ''; } };
    for (const line of escapeHtml(src).split('\n')) {
      if (line.trim().startsWith('```')) {
        if (inCode) { out.push(block(lang, code)); code = []; inCode = false; }
        else { closeList(); inCode = true; lang = line.trim().slice(3).trim().split(/\s+/)[0]; }
        continue;
      }
      if (inCode) { code.push(line); continue; }
      const li = line.match(/^\s*[*\-\u2022]\s+(.*)$/);
      const ol = li ? null : line.match(/^\s*\d+[.)]\s+(.*)$/);
      if (li || ol) {
        const kind = li ? 'ul' : 'ol';
        if (list !== kind) { closeList(); out.push('<' + kind + '>'); list = kind; }
        out.push('<li>' + inline((li || ol)[1]) + '</li>');
        continue;
      }
      closeList();
      const h = line.match(/^#{1,4}\s+(.*)$/);
      if (h) { out.push('<div class="h">' + inline(h[1]) + '</div>'); continue; }
      out.push(line.trim() === '' ? '<div class="gap"></div>' : '<div>' + inline(line) + '</div>');
    }
    if (inCode) out.push(block(lang, code));
    closeList();
    return out.join('');
  }

  // Splits a Server-Sent Events buffer into complete events plus the unfinished tail.
  function parseSSE(buffer) {
    const parts = buffer.split('\n\n');
    const rest = parts.pop();
    const events = [];
    for (const block of parts) {
      let event = 'message';
      let data = '';
      for (const line of block.split('\n')) {
        if (line.startsWith('event: ')) event = line.slice(7);
        else if (line.startsWith('data: ')) data += line.slice(6);
      }
      if (!data) continue;
      try { events.push({ event, data: JSON.parse(data) }); } catch (e) { /* skip malformed */ }
    }
    return { events, rest };
  }

  // Text for the speech engine: no markdown, code, links or emoji; capped at a sentence.
  function stripForSpeech(text, limit) {
    limit = limit || 600;
    const t = String(text)
      .replace(/```[\s\S]*?```/g, ' code omitted. ')
      .replace(/https?:\/\/\S+/g, ' link ')
      .replace(/[`*#_>]/g, '')
      .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, '')
      .replace(/\s+/g, ' ')
      .trim();
    if (t.length <= limit) return t;
    const cut = t.slice(0, limit);
    const end = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('! '), cut.lastIndexOf('? '));
    return end > limit * 0.4 ? cut.slice(0, end + 1) : cut;
  }

  const GREETINGS = {
    any: [
      'Coffee and {ai} time?', "What's on your mind, {user}?", 'Back at it, {user}?', '{user}! What are we building?',
      'Ready when you are.', "Hey {user}. What's the plan?", "Let's make something, {user}.", 'Talk to me, {user}.',
      'Got a minute, {user}?', 'Fire away.',
    ],
    morning: ['Good morning, {user}.', 'Morning, {user}. Coffee first?', 'Fresh day, {user}. What comes first?'],
    afternoon: ['Good afternoon, {user}.', 'Afternoon, {user}. What are we tackling?', 'Mid-day check-in, {user}?'],
    evening: ['Good evening, {user}.', 'Winding down or ramping up, {user}?', 'Evening, {user}. What are we up to?'],
    night: ['Still up, {user}?', 'Burning the midnight oil, {user}?', 'Late-night ideas, {user}?'],
  };

  function greetingFor(hour, user, ai, rand) {
    rand = rand || Math.random;
    const slot = hour >= 5 && hour < 12 ? 'morning' : hour >= 12 && hour < 17 ? 'afternoon' : hour >= 17 && hour < 22 ? 'evening' : 'night';
    const pool = GREETINGS.any.concat(GREETINGS[slot]);
    const pick = pool[Math.min(pool.length - 1, Math.floor(rand() * pool.length))];
    return pick.replace(/\{user\}/g, user || 'there').replace(/\{ai\}/g, ai || 'Faze');
  }

  const MODEL_NAMES = { auto: 'Auto', qwen: 'Qwen', lfm: 'LFM', gemini: 'Gemini' };

  function modelLabel(mode, turns) {
    const name = MODEL_NAMES[mode] || mode;
    return mode === 'auto' || !turns ? name : name + ' \u00b7 ' + turns + ' left';
  }

  const STAY_OPTIONS = [[0, 'Until I turn it off'], [1, 'Next message'], [3, '3 messages'], [5, '5 messages'], [10, '10 messages'], [20, '20 messages']];

  function stayLabel(n) {
    n = Number(n) || 0;
    return n <= 0 ? 'until I turn it off' : n === 1 ? '1 message' : n + ' messages';
  }

  // Box-filter downsample: averages the input samples that fall into each output sample.
  function resampleMean(input, fromRate, toRate) {
    if (fromRate === toRate) return input;
    const ratio = fromRate / toRate;
    const outLen = Math.floor(input.length / ratio);
    const out = new Float32Array(outLen);
    for (let i = 0; i < outLen; i++) {
      const start = Math.floor(i * ratio);
      const end = Math.max(start + 1, Math.min(input.length, Math.floor((i + 1) * ratio)));
      let sum = 0;
      for (let j = start; j < end; j++) sum += input[j];
      out[i] = sum / (end - start);
    }
    return out;
  }

  // 16-bit mono PCM WAV, the format the server's whisper.cpp wrapper accepts.
  function encodeWav(samples, sampleRate) {
    const bytes = new Uint8Array(44 + samples.length * 2);
    const view = new DataView(bytes.buffer);
    const text = (off, s) => { for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i)); };
    text(0, 'RIFF'); view.setUint32(4, 36 + samples.length * 2, true); text(8, 'WAVE');
    text(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    text(36, 'data'); view.setUint32(40, samples.length * 2, true);
    for (let i = 0; i < samples.length; i++) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    return bytes;
  }

  // Menu rows for what the user has typed so far. Each row: label, description, insert (the
  // new input text) and complete (true when pressing Enter should run it, false when it needs more).
  function commandSuggestions(text, commands, settings) {
    if (!text.startsWith('/') || /\n/.test(text)) return [];
    const rows = [];
    const space = text.indexOf(' ');
    if (space < 0) {
      const q = text.slice(1).toLowerCase();
      const starts = commands.filter((c) => c.name.startsWith(q));
      const rest = commands.filter((c) => !c.name.startsWith(q) && (c.description || '').toLowerCase().includes(q) && q.length > 1);
      for (const c of starts.concat(rest)) {
        const needs = !!c.needs_args;
        rows.push({ label: '/' + c.name, description: c.description, insert: '/' + c.name + (needs ? ' ' : ''), complete: !needs });
      }
      return rows;
    }
    const parts = text.slice(1).split(' ');
    const cmd = commands.find((c) => c.name === parts[0].toLowerCase());
    if (!cmd) return [];
    if (parts.length === 2) {
      const q = parts[1].toLowerCase();
      for (const a of cmd.args || []) {
        if (a.value.startsWith(q)) {
          const takesKey = cmd.name === 'settings';
          rows.push({ label: a.value, description: a.description, insert: '/' + cmd.name + ' ' + a.value + (takesKey ? ' ' : ''), complete: !takesKey });
        }
      }
      return rows;
    }
    if (cmd.name === 'settings' && (parts[1] === 'set' || parts[1] === 'unset') && parts.length === 3) {
      const q = parts[2].toLowerCase();
      for (const st of settings || []) {
        if (st.key.toLowerCase().startsWith(q)) {
          rows.push({ label: st.key, description: '= ' + st.value + (st.choices ? '   (' + st.choices.join(' | ') + ')' : ''),
            insert: '/settings ' + parts[1] + ' ' + st.key + (parts[1] === 'set' ? ' ' : ''), complete: parts[1] === 'unset' });
        }
      }
    }
    return rows;
  }

  // ---- FAZE additions: trace parsing, live-state wording, chat grouping, attachments

  const TOOL_VERBS = {
    search_web: 'Searching', delegate_to_gemini: 'Asking Gemini', get_current_datetime: 'Checking the time',
    get_battery_status: 'Checking battery', get_wifi_status: 'Checking Wi-Fi', read_clipboard: 'Reading clipboard',
    vibrate: 'Vibrating', enable_thinking_mode: 'Thinking deeply',
  };
  // The glyph beside the model name. New models: add a line here, in MODEL_NAMES and in core.js.
  const MODEL_GLYPH = { auto: '\u25CE', qwen: '\u25C7', lfm: '\u25C8', gemini: '\u25C9', regex: '\u2301', intent: '\u2301', needle: '\u2301' };

  const MODEL_FORM_TAGS = new Set(['qwen', 'lfm', 'gemini']); // routes that count as a model for the "N models" indicator

  // "[layer -> tool] {args} => result (12ms)" into its parts; null when it is not a trace line.
  function parseTrace(line) {
    const m = String(line).match(/^\[([^\]]+?)\s*->\s*([^\]]+?)\]\s*(.*)$/);
    if (!m) return null;
    const ms = m[3].match(/\((\d+)ms\)/);
    return { layer: m[1].trim(), tool: m[2].trim(), detail: m[3], ms: ms ? Number(ms[1]) : null,
      error: /\bERROR\b|failed/i.test(m[3]), verb: TOOL_VERBS[m[2].trim()] || '' };
  }

  // The quiet line beside the core: "FAZE is ready", "Searching > writing", "Done".
  function liveLabel(state, verbs, name) {
    const who = String(name || 'FAZE').toUpperCase();
    const chain = (extra) => {
      const seen = [];
      for (const v of (verbs || []).concat(extra || [])) if (v && seen[seen.length - 1] !== v) seen.push(v);
      return seen.slice(-3).map((v, i) => (i ? v.charAt(0).toLowerCase() + v.slice(1) : v)).join(' \u2192 ');
    };
    switch (state) {
      case 'thinking': return '\u25CC ' + who + ' is thinking';
      case 'tool': return '\u25CC ' + (chain() || who + ' is working');
      case 'writing': return '\u25CC ' + (chain(['writing']) || who + ' is writing');
      case 'done': return '\u2726 Done';
      case 'stopped': return '\u25CC Stopped';
      case 'error': return '\u2726 Something went wrong';
      default: return '\u2726 ' + who + ' is ready';
    }
  }

  // Cuts finished sentences out of text that is still arriving, so speech can start before the reply ends.
  // Code blocks are skipped (said once as "Code omitted"), tiny fragments wait for the next sentence, and
  // a trailing "3." style number is not mistaken for a sentence end because a space must follow.
  function splitSpeakable(buffer, final) {
    let text = String(buffer).replace(/```[\s\S]*?```/g, ' Code omitted. ');
    let hold = '';
    const open = text.indexOf('```');
    if (open >= 0) { hold = text.slice(open); text = text.slice(0, open); }
    const sentences = [];
    let start = 0;
    const end = /[.!?\u2026]["')\]]*\s+|\n+/g;
    let m;
    while ((m = end.exec(text))) {
      const stop = m.index + m[0].length;
      const piece = text.slice(start, stop).replace(/[ \t]+/g, ' ').trim();
      if (piece.length >= 20 || (/\n/.test(m[0]) && piece)) { sentences.push(piece); start = stop; }
    }
    if (final) {
      const tail = text.slice(start).replace(/[ \t]+/g, ' ').trim();
      if (tail) sentences.push(tail);
      return { sentences, rest: '' };
    }
    return { sentences, rest: text.slice(start) + hold };
  }

  function titleFor(text) {
    const t = String(text || '').replace(/\s+/g, ' ').trim();
    if (!t) return 'New chat';
    return t.length > 42 ? t.slice(0, 41).trimEnd() + '\u2026' : t;
  }

  // Sidebar structure: projects first, then loose chats by age. Empty groups are dropped.
  function groupChats(chats, now, query) {
    const q = String(query || '').trim().toLowerCase();
    const hit = (c) => !q || (c.title || '').toLowerCase().includes(q) || (c.project || '').toLowerCase().includes(q) ||
      (c.messages || []).some((m) => m.role !== 'system' && String(m.text || '').toLowerCase().includes(q));
    const sorted = chats.filter(hit).sort((a, b) => (b.updated || 0) - (a.updated || 0));
    const groups = [];
    const projects = {};
    for (const c of sorted) if (c.project) (projects[c.project] = projects[c.project] || []).push(c);
    for (const name of Object.keys(projects).sort((a, b) => a.localeCompare(b))) groups.push({ title: name, project: true, items: projects[name] });
    const day = 86400000;
    const start = new Date(now); start.setHours(0, 0, 0, 0);
    const buckets = [['Today', []], ['Yesterday', []], ['Previous 7 days', []], ['Older', []]];
    for (const c of sorted) {
      if (c.project) continue;
      const age = start.getTime() - (c.updated || 0);
      buckets[age <= 0 ? 0 : age <= day ? 1 : age <= 7 * day ? 2 : 3][1].push(c);
    }
    for (const [title, items] of buckets) if (items.length) groups.push({ title, project: false, items });
    return groups;
  }

  function clipFile(name, content, max) {
    max = max || 3000;
    const text = String(content || '');
    return { name: String(name || 'file'), content: text.slice(0, max), truncated: text.length > max };
  }

  function composeMessage(text, files) {
    let out = String(text || '').trim();
    for (const f of files || []) {
      out += '\n\n[Attached: ' + f.name + (f.truncated ? ' (truncated)' : '') + ']\n```\n' + f.content.replace(/```/g, "'''") + '\n```';
    }
    return out;
  }

  // What the server needs to rebuild its context when an old chat is reopened.
  function restorePayload(messages) {
    const out = [];
    for (const m of messages || []) {
      if (m.role === 'user' && (m.sent || m.text)) out.push({ role: 'user', content: m.sent || m.text });
      else if (m.role === 'faze' && m.text && !m.failed && !m.pending) out.push({ role: 'assistant', content: m.text });
    }
    // keep the newest turns that fit the server's request limit
    let kept = out.slice(-20).map((m) => ({ role: m.role, content: m.content.slice(0, 4000) }));
    let total = kept.reduce((n, m) => n + m.content.length, 0);
    while (kept.length > 1 && total > 48000) total -= kept.shift().content.length;
    return kept;
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { escapeHtml, renderMarkdown, parseSSE, stripForSpeech, greetingFor, GREETINGS,
      modelLabel, stayLabel, STAY_OPTIONS, splitSpeakable, resampleMean, encodeWav, commandSuggestions,
      parseTrace, liveLabel, titleFor, groupChats, clipFile, composeMessage, restorePayload, TOOL_VERBS, MODEL_GLYPH, MODEL_NAMES };
    return;
  }

  // ---------------------------------------------------------------- state

  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* storage full or blocked */ } },
  };

  // name: [light, deeper edge]. Colour is only ever set through --accent / --accent-2.
  const ACCENTS = { cyan: ['#5cc8ff', '#3d7bff'], ice: ['#b8d8ff', '#7a9bd6'], violet: ['#a99bff', '#6a5cff'], mint: ['#5ce6c0', '#2fa98c'], amber: ['#ffc46b', '#e0873a'] };

  let token = store.get('faze_token', '');
  let chats = store.get('faze_chats', null);
  if (!Array.isArray(chats) || !chats.length) chats = [makeChat(store.get('faze_chat', []))];
  let activeId = store.get('faze_active', '');
  if (!chats.some((c) => c.id === activeId)) activeId = chats[0].id;
  let chat = chats.find((c) => c.id === activeId).messages;
  let busy = false;
  let showTag = true;
  let installEvent = null;
  let status = null;
  let names = { ai: 'Faze', user: 'there' };
  let commandList = [];
  let settingRows = [];
  let menuRows = [];
  let menuSel = 0;
  let voiceMode = false;
  let misses = 0;
  let lastWakeState = '';
  let audio = null;
  let speechStream = null;
  let audioPlaying = false;
  let geminiVoices = null;
  let wakeSeq = 0;
  const seenNotices = new Set();

  const opts = {
    speak: store.get('opt_speak', false),
    mic: store.get('opt_mic', true),
    onDevice: store.get('opt_ondevice', false),
    accent: store.get('opt_accent', 'cyan'),
    sidebar: store.get('opt_sidebar', 'open'),
    size: store.get('opt_size', 'm'),
    motion: store.get('opt_motion', true),
    greeting: store.get('opt_greeting', true),
    voice: store.get('opt_voice', ''),
    offlineVoices: store.get('opt_offline_voices', true),
    rate: store.get('opt_rate', 1),
    pitch: store.get('opt_pitch', 1),
    font: store.get('opt_font', 'system'),
  };

  function saveOpt(key, value) { opts[key] = value; store.set('opt_' + key.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase()), value); }

  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { el.hidden = true; }, 3500);
  }

  function applyLook() {
    const root = document.documentElement;
    const pair = ACCENTS[opts.accent] || ACCENTS.cyan;
    root.style.setProperty('--accent', pair[0]);
    root.style.setProperty('--accent-2', pair[1]);
    root.dataset.size = opts.size;
    root.dataset.font = opts.font;
    root.dataset.platform = /Android/i.test(navigator.userAgent) ? 'android' : 'other';
    if (opts.font === 'custom') loadCustomFont();
    root.dataset.motion = opts.motion ? 'on' : 'off';
    document.body.classList.toggle('side-closed', opts.sidebar === 'closed');
    if (window.FazeCoreKit) { window.FazeCoreKit.refreshTheme(); window.FazeCoreKit.setReduced(reducedMotion()); }
  }

  // A font file you pick (a .ttf/.otf, for example the one your phone theme uses) is kept in the browser and
  // used for the whole app. Up to 3 MB.
  let customFontLoaded = false;

  function b64FromBuffer(buf) {
    const bytes = new Uint8Array(buf);
    let out = '';
    for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(out);
  }

  async function registerFont(buf) {
    if (typeof FontFace === 'undefined' || !document.fonts) throw new Error('This browser cannot load font files');
    const face = new FontFace('FazeCustom', buf);
    await face.load();
    document.fonts.add(face);
    customFontLoaded = true;
  }

  async function loadCustomFont() {
    if (customFontLoaded) return;
    const data = store.get('opt_fontdata', '');
    if (!data) return;
    try {
      const raw = atob(data);
      const bytes = new Uint8Array(raw.length);
      for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i);
      await registerFont(bytes.buffer);
    } catch (e) { toast('Your font could not be loaded: ' + e.message); }
  }

  async function pickFontFile(file) {
    if (!file) return;
    if (file.size > 3 * 1024 * 1024) { toast('Pick a font under 3 MB'); return; }
    try {
      const buf = file.arrayBuffer ? await file.arrayBuffer() : await new Promise((resolve, reject) => { const r = new FileReader(); r.onload = () => resolve(r.result); r.onerror = reject; r.readAsArrayBuffer(file); });
      customFontLoaded = false;
      await registerFont(buf);
      store.set('opt_fontdata', b64FromBuffer(buf));
      store.set('opt_fontname', file.name);
      saveOpt('font', 'custom');
      applyLook();
      syncSettingsControls();
      toast('Using ' + file.name);
    } catch (e) { toast('Could not use that font: ' + e.message); }
  }

  // ---------------------------------------------------------------- api

  async function api(path, options) {
    options = options || {};
    const headers = Object.assign({ Authorization: 'Bearer ' + token }, options.headers || {});
    let body = options.raw;
    if (options.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(options.body); }
    const res = await fetch(path, { method: options.method || (body !== undefined ? 'POST' : 'GET'), headers, body });
    if (res.status === 401) { showPair(); throw new Error('not paired'); }
    return res;
  }

  async function apiJson(path, body) {
    const res = await api(path, body === undefined ? {} : { body });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    return data;
  }

  async function command(line) {
    const data = await apiJson('/api/command', { line });
    return data.text;
  }

  // ---------------------------------------------------------------- rendering

  function stepsHtml(trace) {
    const rows = [];
    for (const line of trace || []) {
      const t = parseTrace(line);
      if (!t) continue;
      rows.push('<div class="step' + (t.error ? ' bad' : '') + '" title="' + escapeHtml(line) + '"><i></i><b>' +
        escapeHtml(t.verb || t.tool.replace(/_/g, ' ')) + '</b><span>' + escapeHtml(t.layer) + '</span>' +
        (t.ms !== null ? '<em>' + t.ms + 'ms</em>' : '') + '</div>');
    }
    if (!rows.length) return '';
    return '<details class="steps"><summary>' + rows.length + (rows.length === 1 ? ' step' : ' steps') + '</summary>' + rows.join('') + '</details>';
  }

  function viaHtml(m) {
    const bits = [];
    if (showTag && m.tag) {
      bits.push('<span class="via" title="' + escapeHtml(m.note || '') + '">' + (MODEL_GLYPH[m.tag] || '\u25CB') + ' ' + escapeHtml(MODEL_NAMES[m.tag] || m.tag) + '</span>');
    }
    if (m.models > 1) bits.push('<span class="via">' + m.models + ' models</span>');
    return bits.join('');
  }

  function turnHtml(m) {
    if (m.role === 'system') return '<div class="body">' + escapeHtml(m.text) + '</div>';
    if (m.role === 'user') {
      let h = '<div class="who">YOU</div><div class="text">' + escapeHtml(m.text) + '</div>';
      if (m.files && m.files.length) h += '<div class="files">' + m.files.map((f) => '<span>\uD83D\uDCCE ' + escapeHtml(f) + '</span>').join('') + '</div>';
      return h + '<div class="acts"><button type="button" class="act-copy">Copy</button><button type="button" class="act-speak">Listen</button></div>';
    }
    let h = '<div class="who"><i class="gl"></i><span>' + escapeHtml(String(names.ai || 'Faze').toUpperCase()) + '</span>' + viaHtml(m) + '</div>';
    if (m.thinking) {
      h += '<details class="think"' + (m.pending && !m.text ? ' open' : '') + '><summary>thinking</summary><pre>' + escapeHtml(m.thinking) + '</pre></details>';
    }
    h += stepsHtml(m.trace);
    if (m.pending && !m.text) h += '<div class="skeleton"><i></i><i></i><i></i></div>';
    else h += '<div class="body">' + renderMarkdown(m.text || '') + '</div>';
    if (!m.pending && m.text && !m.failed) {
      h += '<div class="acts"><button type="button" class="act-copy">Copy</button><button type="button" class="act-speak">Listen</button></div>';
    }
    return h;
  }

  // Repainting while tokens stream must not close sections the reader opened.
  function renderMessage(m, el) {
    el = el || document.createElement('div');
    const open = Array.from(el.querySelectorAll('details')).map((d) => d.open);
    el._m = m;
    el.className = 'turn ' + m.role + (m.failed ? ' failed' : '') + (m.pending ? ' pending' : '') + (m.pending && m.text ? ' typing' : '');
    el.innerHTML = turnHtml(m);
    Array.from(el.querySelectorAll('details')).forEach((d, i) => { if (open[i] !== undefined && !(m.pending && !m.text)) d.open = open[i]; });
    return el;
  }

  function scrollDown(force) {
    const log = $('log');
    if (force || log.scrollHeight - log.scrollTop - log.clientHeight < 160) log.scrollTop = log.scrollHeight;
  }

  function syncHero() {
    $('log').classList.toggle('empty', chat.length === 0);
    cores.forEach((c) => c.resize());
  }

  function newGreeting() {
    const fancy = opts.greeting && Math.random() < 0.5;
    $('sub').textContent = fancy ? greetingFor(new Date().getHours(), names.user, names.ai) : 'Ask anything. Build anything.';
  }

  function renderAll() {
    const thread = $('thread');
    thread.textContent = '';
    for (const m of chat) thread.appendChild(renderMessage(m));
    syncHero();
    scrollDown(true);
  }

  function addMessage(m) {
    chat.push(m);
    const el = renderMessage(m);
    $('thread').appendChild(el);
    syncHero();
    scrollDown(true);
    return el;
  }

  // ---------------------------------------------------------------- status + model sheet

  function chip(text, cls) {
    const s = document.createElement('span');
    s.className = 'chip ' + cls;
    s.textContent = text;
    return s;
  }

  function modelStates(s) {
    const q = s.qwen.state;
    const qOk = q === 'ready' || q === 'unmanaged';
    return {
      auto: { cls: 'ok', sub: 'Qwen when ready, LFM while it boots, Gemini as backup' },
      qwen: { cls: qOk ? 'ok' : (q === 'failed' ? 'bad' : 'warn'), sub: qOk ? 'ready' : (s.qwen.note || q).replace(/^qwen /, '') },
      lfm: { cls: s.lfm === 'running' ? 'ok' : '', sub: s.lfm },
      gemini: { cls: s.gemini ? 'ok' : 'bad', sub: s.gemini ? 'online helper' : 'turned off' },
    };
  }

  function renderStatus(s) {
    status = s;
    names = { ai: s.name, user: s.user_name };
    showTag = s.show_tag;
    document.title = s.name;
    $('input').placeholder = 'Ask ' + s.name + ' anything...';
    $('banner').hidden = true;

    const chips = $('chips');
    chips.textContent = '';
    const st = modelStates(s);
    chips.appendChild(chip('qwen ' + st.qwen.sub, st.qwen.cls));
    if (s.lfm === 'running') chips.appendChild(chip('lfm on', 'ok'));
    if (!s.gemini) chips.appendChild(chip('gemini off', ''));
    if (s.wake && s.wake.enabled) {
      const c = chip('wake ' + s.wake.state, s.wake.state === 'listening' ? 'wake' : (s.wake.state === 'listening' ? '' : 'warn'));
      if (s.wake.detail) c.title = s.wake.detail;
      chips.appendChild(c);
      if (s.wake.detail && s.wake.state !== lastWakeState) addMessage({ role: 'system', text: 'Wake word: ' + s.wake.detail });
      lastWakeState = s.wake.state;
    }

    syncModelIndicator();
    if (!busy) syncCoreModel();
    renderSidebar();
    maybeRestore(s);
    renderWakeRow();
    maybeRefreshAgent(s);
    renderThinking(s);
    $('stt-note').textContent = s.stt.server ? 'offline engine ready' : (s.stt.reason || '');

    if (!$('model-sheet').hidden) renderModelSheet();
    for (const n of s.notices || []) {
      if (!seenNotices.has(n)) { seenNotices.add(n); addMessage({ role: 'system', text: n }); saveChat(); }
    }
  }

  // Tap a model: stay on it until turned off. The control at the right (a tick on the active model, an
  // arrow elsewhere) morphs into a dropdown for "stay N messages".
  function stayControl(id) {
    const active = status.mode === id;
    const turns = active ? (status.forced_turns || 0) : 0;
    const ctl = document.createElement('div');
    ctl.className = 'stay-ctl' + (active ? ' active' : '');
    const trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.className = 'stay-trigger';
    trigger.textContent = active ? '\u2713' : '\u25BE';
    trigger.setAttribute('aria-label', (active ? 'Staying on ' + MODEL_NAMES[id] + ' ' + stayLabel(turns) + '. ' : '') + 'Choose how long to stay on ' + MODEL_NAMES[id]);
    const pick = document.createElement('select');
    pick.className = 'stay-pick';
    pick.tabIndex = -1;
    pick.setAttribute('aria-label', 'How long to stay on ' + MODEL_NAMES[id]);
    const options = STAY_OPTIONS.slice();
    if (turns && !options.some((o) => o[0] === turns)) options.push([turns, stayLabel(turns)]);
    for (const [n, label] of options) {
      const o = document.createElement('option');
      o.value = String(n);
      o.textContent = label;
      pick.appendChild(o);
    }
    pick.value = String(turns);
    const close = () => { ctl.classList.remove('open'); pick.tabIndex = -1; };
    trigger.onclick = () => {
      ctl.classList.add('open');
      pick.tabIndex = 0;
      pick.focus();
      try { if (pick.showPicker) pick.showPicker(); } catch (e) { /* the tap itself opens it */ }
    };
    pick.onchange = () => { close(); chooseModel(id, Number(pick.value)); };
    pick.onblur = () => setTimeout(close, 150);
    ctl.appendChild(trigger);
    ctl.appendChild(pick);
    return ctl;
  }

  function renderModelSheet() {
    if (!status) return;
    const list = $('model-list');
    if (list.querySelector('.stay-ctl.open')) return;  // do not yank a dropdown away mid-choice
    list.textContent = '';
    const st = modelStates(status);
    for (const id of ['auto', 'qwen', 'lfm', 'gemini']) {
      const item = document.createElement('div');
      item.className = 'model-item';
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'model-row' + (status.mode === id ? ' on' : '');
      row.innerHTML = '<span class="dot ' + st[id].cls + '"></span><span class="name">' + MODEL_NAMES[id] +
        '<small>' + escapeHtml(st[id].sub) + '</small></span>';
      row.onclick = () => chooseModel(id, 0);
      item.appendChild(row);
      if (id !== 'auto') item.appendChild(stayControl(id));
      list.appendChild(item);
    }
  }

  // Thinking is one level for whichever model answers; what it means depends on the model picked in the sheet.
  function thinkHint(mode, level, gem) {
    if (mode === 'lfm') return 'LFM cannot think, so this does nothing while LFM is forced. It still applies when Qwen or Gemini answers.';
    if (mode === 'qwen') return 'Qwen has one thinking mode: any level except Off turns it on, Auto lets Qwen ask for it. Slow on a phone, about 6 words a second, and you can press stop.';
    if (mode === 'gemini') return 'Gemini: Off is minimal, Low, Medium and High are as named, Max is High, Auto uses your gemini.thinking setting (' + (gem || 'low') + '). Lower is faster.';
    return 'Applies to whichever model answers. Qwen thinks on any level except Off; Gemini uses the level (Off is minimal, Max is High, Auto follows gemini.thinking, ' + (gem || 'low') + ').';
  }

  function renderThinking(s) {
    const level = s.think_level || s.think || 'auto';
    $('menu-think-val').textContent = level;
    const sel = $('think-level');
    if (document.activeElement !== sel) sel.value = level;
    sel.disabled = s.mode === 'lfm';
    $('think-hint').textContent = thinkHint(s.mode || 'auto', level, s.gemini_thinking);
  }

  async function chooseModel(id, n) {
    try {
      toast(await command('/model ' + id + (id !== 'auto' && n > 0 ? ' ' + n : '')));
    } catch (e) { toast(e.message); }
    pollStatus(true);
  }

  async function pollStatus(force) {
    if (document.hidden && !force) return;
    try {
      renderStatus(await (await api('/api/status')).json());
    } catch (e) {
      if (e.message !== 'not paired') {
        $('banner').textContent = "Can't reach Faze. Start it in Termux:  ./faze.sh --serve";
        $('banner').hidden = false;
      }
    }
  }

  // ---------------------------------------------------------------- chat

  // ---------------------------------------------------------------- chat

  let liveTimer = 0;
  let liveVerbs = [];
  let turnModels = [];
  let tokenLevel = 0;
  let restoredOnce = false;

  function setLive(kind) {
    clearTimeout(liveTimer);
    $('livebar').dataset.state = kind;
    $('live-text').textContent = liveLabel(kind, liveVerbs, names.ai);
    const coreState = { thinking: 'thinking', tool: 'tool', writing: 'responding' }[kind] || 'idle';
    coreAll((c) => c.setState(coreState, { fragments: 3 + liveVerbs.length * 2 }));
    if (kind === 'done' || kind === 'stopped' || kind === 'error') liveTimer = setTimeout(() => setLive('ready'), 2400);
  }

  function syncModelIndicator() {
    const mode = (status && status.mode) || 'auto';
    const active = busy && turnModels.length ? turnModels[turnModels.length - 1] : mode;
    let label = busy && turnModels.length ? (MODEL_NAMES[active] || active) : modelLabel(mode, status && status.forced_turns);
    if (busy && turnModels.length > 1) label = turnModels.length + ' models';
    const glyph = MODEL_GLYPH[active] || '\u25CB';
    const g = $('model-glyph');
    if (g.textContent !== glyph) { g.classList.remove('swap'); void g.offsetWidth; g.classList.add('swap'); }
    g.textContent = glyph;
    $('model-label').textContent = label;
    const quick = $('model-quick');
    quick.querySelector('.g').textContent = glyph;
    quick.querySelector('b').textContent = label;
  }

  // The core wears the form of whatever model is answering; auto cycles through them all.
  function syncCoreModel() {
    const target = busy && turnModels.length ? turnModels[turnModels.length - 1] : (status && status.mode !== 'auto' ? status.mode : null);
    coreAll((c) => c.setModel(target));
  }

  function setBusy(on) {
    busy = on;
    document.body.classList.toggle('busy', on);
    $('send-icon').textContent = on ? '\u25A0' : '\u2191';
    $('send').setAttribute('aria-label', on ? 'Stop' : 'Send');
    syncModelIndicator();
  }

  async function stopTurn() {
    try { await apiJson('/api/cancel', {}); } catch (e) { toast(e.message); }
  }

  async function runCommand(line) {
    addMessage({ role: 'user', text: line });
    try { addMessage({ role: 'system', text: await command(line) }); }
    catch (e) { addMessage({ role: 'system', text: 'Command failed: ' + e.message }); }
    saveChat();
    pollStatus(true);
  }

  async function sendMessage(text, files) {
    stopAudio();
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
    syncSpeechButton();
    if (/^\/logs?(\s|$)/.test(text)) return openLog();
    if (text.startsWith('/')) return runCommand(text);

    const sent = composeMessage(text, files);
    const um = { role: 'user', text };
    if (files && files.length) { um.files = files.map((f) => f.name); um.sent = sent; }
    addMessage(um);
    const m = { role: 'faze', text: '', tag: '', note: '', thinking: '', trace: [], pending: true };
    chat.push(m);
    const el = renderMessage(m);
    $('thread').appendChild(el);
    scrollDown(true);
    liveVerbs = [];
    turnModels = [];
    setBusy(true);
    setLive('thinking');
    syncCoreModel();
    const voice = (opts.speak || voiceMode) ? makeSpeechStream() : null;

    const paint = () => { renderMessage(m, el); scrollDown(); };
    try {
      const res = await api('/api/chat', { body: { message: sent } });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || ('HTTP ' + res.status));
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSSE(buffer);
        buffer = parsed.rest;
        for (const { event, data } of parsed.events) {
          if (event === 'route') {
            if (m.text) { m.trace.push('(discarded partial reply from ' + m.tag + ')'); m.text = ''; m.thinking = ''; if (voice) voice.reset(); }
            m.tag = data.tag; m.note = data.note;
            if (MODEL_FORM_TAGS.has(data.tag) && !turnModels.includes(data.tag)) {
              turnModels.push(data.tag);
              if (turnModels.length > 1) { m.models = turnModels.length; coreAll((c) => c.burst(turnModels)); }
            }
            syncModelIndicator();
            syncCoreModel();
          } else if (event === 'token') {
            if (data.kind === 'thinking') m.thinking += data.text;
            else if (data.kind === 'reset') { m.text = ''; if (voice) voice.reset(); }
            else {
              if (!m.text) setLive('writing');
              m.text += data.text;
              if (voice) voice.feed(data.text);
              tokenLevel = 0.7;
              coreAll((c) => c.pulse(0.02));
            }
          } else if (event === 'trace') {
            m.trace.push(data.line);
            const t = parseTrace(data.line);
            if (t && t.verb) { liveVerbs.push(t.verb); setLive('tool'); }
          } else if (event === 'notice') {
            addMessage({ role: 'system', text: data.text });
          } else if (event === 'reply') {
            m.text = data.text;
            if (data.route) m.tag = data.route;
          } else if (event === 'error') {
            m.text = 'Something went wrong: ' + data.message;
            m.failed = true;
          }
          paint();
        }
      }
    } catch (e) {
      if (e.message !== 'not paired') {
        m.text = (m.text ? m.text + '\n\n' : '') + 'Connection problem: ' + e.message;
        m.failed = true;
      }
    }
    m.pending = false;
    if (m.text.startsWith('[') && /failed|couldn't answer|no backend/.test(m.text)) m.failed = true;
    const stopped = m.text === '[stopped]';
    if (stopped) { m.text = 'Stopped.'; m.tag = ''; }
    paint();
    saveChat();
    setBusy(false);
    setLive(m.failed ? 'error' : stopped ? 'stopped' : 'done');
    syncCoreModel();
    if (voice && voice.used() && !m.failed && !stopped) voice.finish();
    else { if (voice) voice.cancel(); speak(m.failed || stopped ? '' : m.text); }
    pollStatus(true);
  }

  function makeChat(messages) {
    const first = (messages || []).find((m) => m.role === 'user');
    return { id: 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), title: first ? titleFor(first.text) : 'New chat',
      project: '', updated: Date.now(), messages: messages || [] };
  }

  function persistChats() { store.set('faze_chats', chats); store.set('faze_active', activeId); }

  function saveChat() {
    const c = chats.find((x) => x.id === activeId);
    if (c) {
      c.updated = Date.now();
      const u = chat.find((m) => m.role === 'user');
      if (u && (!c.title || c.title === 'New chat')) c.title = titleFor(u.text);
      if (chat.length > 200) chat.splice(0, chat.length - 200);
    }
    if (chats.length > 60) chats = chats.slice().sort((a, b) => b.updated - a.updated).slice(0, 60);
    persistChats();
    renderSidebar();
  }

  function startChat() {
    const c = makeChat([]);
    chats.unshift(c);
    activeId = c.id;
    chat = c.messages;
    persistChats();
  }

  async function newChat() {
    closeDrawer();
    $('menu').hidden = true;
    if (busy) await stopTurn();
    try { await command('/reset'); } catch (e) { toast(e.message); return; }
    if (chat.length) startChat();
    seenNotices.clear();
    renderAll();
    newGreeting();
    renderSidebar();
    setLive('ready');
    $('input').focus();
  }

  async function openChat(id) {
    closeDrawer();
    if (id === activeId) return;
    const target = chats.find((x) => x.id === id);
    if (!target) return;
    if (busy) await stopTurn();
    if (!chat.length) chats = chats.filter((x) => x.id !== activeId);
    activeId = id;
    chat = target.messages;
    persistChats();
    try { await apiJson('/api/restore', { messages: restorePayload(chat) }); } catch (e) { toast(e.message); }
    seenNotices.clear();
    renderAll();
    newGreeting();
    renderSidebar();
  }

  async function deleteChat(c) {
    if (!confirm('Delete "' + c.title + '"?')) return;
    chats = chats.filter((x) => x.id !== c.id);
    if (c.id === activeId) {
      if (busy) await stopTurn();
      try { await command('/reset'); } catch (e) { toast(e.message); }
      if (!chats.length) chats = [makeChat([])];
      activeId = chats[0].id;
      chat = chats[0].messages;
      if (chat.length) { try { await apiJson('/api/restore', { messages: restorePayload(chat) }); } catch (e) { /* offline */ } }
      renderAll();
      newGreeting();
    }
    persistChats();
    renderSidebar();
  }

  // After a server restart its history is empty; give it the open chat back once.
  function maybeRestore(s) {
    if (restoredOnce) return;
    restoredOnce = true;
    if (s.turns === 0 && chat.length) apiJson('/api/restore', { messages: restorePayload(chat) }).catch(() => {});
  }

  // ---------------------------------------------------------------- voice out

  let voices = [];

  function loadVoices() {
    if (typeof speechSynthesis === 'undefined') return;
    voices = speechSynthesis.getVoices();
    const pick = $('voice-pick');
    pick.textContent = '';
    const usable = voices.filter((v) => !opts.offlineVoices || v.localService);
    const def = document.createElement('option');
    def.value = '';
    def.textContent = 'System default';
    pick.appendChild(def);
    for (const v of usable) {
      const o = document.createElement('option');
      o.value = v.voiceURI;
      o.textContent = v.name + ' (' + v.lang + ')';
      o.selected = v.voiceURI === opts.voice;
      pick.appendChild(o);
    }
  }

  function makeUtterance(text) {
    const u = new SpeechSynthesisUtterance(text);
    const v = voices.find((x) => x.voiceURI === opts.voice);
    if (v) { u.voice = v; u.lang = v.lang; }
    u.rate = Number(opts.rate) || 1;
    u.pitch = Number(opts.pitch) || 1;
    return u;
  }

  function stopAudio() {
    if (speechStream) { const s = speechStream; speechStream = null; s.cancel(); }
    if (audio) { audio.pause(); audio = null; }
    audioPlaying = false;
  }

  function systemSpeak(clean) {
    if (typeof speechSynthesis === 'undefined') { resumeListening(); return; }
    const u = makeUtterance(clean);
    u.onend = u.onerror = () => { syncSpeechButton(); resumeListening(); };
    speechSynthesis.speak(u);
    syncSpeechButton();
  }

  // Natural online voice from the server; resolves false if it can't be played.
  // Speaks a reply while it is still being written. Gemini voice: sentences are synthesized one request at a
  // time (the server voices one clip at once) while the previous clip plays, and played back to back. Device
  // voice: sentences go straight to speechSynthesis, which queues them. Reads ahead of the text, never behind it.
  function makeSpeechStream() {
    stopAudio();
    const gemini = !!(status && status.tts && status.tts.engine === 'gemini' && navigator.onLine);
    const s = { buffer: '', items: [], gemini, cancelled: false, started: false, finished: false, synthRunning: false, playRunning: false, pending: 0, warned: false, abort: null };
    speechStream = s;
    const nap = (ms) => new Promise((r) => setTimeout(r, ms));

    const fetchClip = async (text) => {
      try {
        const res = await api('/api/speak', { body: { text } });
        if (!res.ok) { const d = await res.json().catch(() => ({})); throw new Error(d.error || ('HTTP ' + res.status)); }
        return URL.createObjectURL(await res.blob());
      } catch (e) {
        if (!s.warned) { s.warned = true; toast('Gemini voice unavailable: ' + e.message); }
        return null;
      }
    };

    const synth = async () => {
      if (s.synthRunning) return;
      s.synthRunning = true;
      for (const item of s.items) {
        if (s.cancelled) break;
        if (item.url === undefined) item.url = await fetchClip(item.text);
      }
      s.synthRunning = false;
      if (!s.cancelled && s.items.some((i) => i.url === undefined)) synth();
    };

    const playUrl = (url) => new Promise((resolve) => {
      const a = new Audio(url);
      audio = a;
      const done = () => { URL.revokeObjectURL(url); if (audio === a) audio = null; s.abort = null; resolve(); };
      s.abort = () => { try { a.pause(); } catch (e) { /* already gone */ } done(); };
      a.onended = a.onerror = done;
      a.play().catch(() => { toast('The voice clip could not be played'); done(); });
    });

    const speakDevice = async (text) => {
      if (typeof speechSynthesis === 'undefined') return;
      const u = makeUtterance(text);
      speechSynthesis.speak(u);
      while (!s.cancelled && speechSynthesis.speaking) await nap(60);
    };

    const play = async () => {
      if (s.playRunning) return;
      s.playRunning = true;
      audioPlaying = true;
      syncSpeechButton();
      let i = 0;
      while (!s.cancelled) {
        const item = s.items[i];
        if (!item) { if (s.finished) break; await nap(40); continue; }
        if (item.url === undefined) { await nap(40); continue; }
        i += 1;
        if (item.url) await playUrl(item.url); else await speakDevice(item.text);
      }
      s.playRunning = false;
      if (speechStream === s) { audioPlaying = false; syncSpeechButton(); if (!s.cancelled) resumeListening(); }
    };

    s.enqueue = (sentence) => {
      const clean = stripForSpeech(sentence, 300);
      if (!clean || s.cancelled) return;
      s.started = true;
      if (gemini) { s.items.push({ text: clean, url: undefined }); synth(); play(); return; }
      if (typeof speechSynthesis === 'undefined') return;
      const u = makeUtterance(clean);
      u.onend = u.onerror = () => { s.pending -= 1; syncSpeechButton(); if (s.finished && !s.pending && !s.cancelled) resumeListening(); };
      s.pending += 1;
      speechSynthesis.speak(u);
      syncSpeechButton();
    };
    s.feed = (chunk) => {
      if (s.cancelled) return;
      s.buffer += chunk;
      const r = splitSpeakable(s.buffer, false);
      s.buffer = r.rest;
      r.sentences.forEach(s.enqueue);
    };
    s.finish = () => {
      if (s.cancelled) return;
      const r = splitSpeakable(s.buffer, true);
      s.buffer = '';
      r.sentences.forEach(s.enqueue);
      s.finished = true;
      if (!gemini && !s.pending) resumeListening();
    };
    s.reset = () => { s.buffer = ''; };
    s.used = () => s.started || s.buffer.length > 0;
    s.cancel = () => { s.cancelled = true; if (s.abort) s.abort(); if (speechStream === s) speechStream = null; };
    return s;
  }

  async function playGemini(text, voice, quiet) {
    try {
      const res = await api('/api/speak', { body: voice ? { text, voice } : { text } });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || ('HTTP ' + res.status));
      }
      const url = URL.createObjectURL(await res.blob());
      stopAudio();
      const a = new Audio(url);
      audio = a;
      audioPlaying = true;
      const done = () => { if (audio === a) { audioPlaying = false; audio = null; } URL.revokeObjectURL(url); syncSpeechButton(); if (!quiet) resumeListening(); };
      a.onended = a.onerror = done;
      await a.play();
      syncSpeechButton();
      return true;
    } catch (e) {
      audioPlaying = false;
      toast('Gemini voice unavailable: ' + e.message);
      return false;
    }
  }

  function speak(text, force) {
    const wanted = (opts.speak || voiceMode || force) && text;
    const clean = wanted ? stripForSpeech(text) : '';
    if (!clean) { resumeListening(); return; }
    const gemini = status && status.tts && status.tts.engine === 'gemini' && navigator.onLine;
    if (gemini) { playGemini(clean).then((ok) => { if (!ok) systemSpeak(clean); }); return; }
    systemSpeak(clean);
  }

  // Voice mode: listen, answer out loud, then listen again until you stop it.
  // The aura follows what Faze is doing: listening, thinking, speaking.
  function syncAura() {
    let state = '';
    if (voiceMode || wakeFlash) {
      if (recorder || recognizer) state = 'listening';
      else if (busy) state = 'thinking';
      else if (audioPlaying || (typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking)) state = 'speaking';
      else state = voiceMode ? 'listening' : 'listening';
    }
    if (document.body.dataset.aura !== state) {
      if (state) document.body.dataset.aura = state; else delete document.body.dataset.aura;
      $('voice-label').textContent = { listening: 'Listening', thinking: 'Thinking', speaking: 'Speaking' }[state] || '';
    }
    if (!recorder) document.documentElement.style.setProperty('--level', '0');
  }

  let wakeFlash = false;

  // Wake word (or a Tasker call) fired: light up and start listening.
  function onWake() {
    wakeFlash = true;
    setTimeout(() => { wakeFlash = false; }, 4000);
    if (!busy && !voiceMode) setVoiceMode(true);
    syncAura();
  }

  function connectEvents() {
    if (!token) { setTimeout(connectEvents, 3000); return; }
    fetch('/api/events', { headers: { Authorization: 'Bearer ' + token } }).then(async (res) => {
      if (!res.ok) throw new Error('events ' + res.status);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSSE(buffer);
        buffer = parsed.rest;
        for (const { event, data } of parsed.events) {
          if (event === 'wake' && data.seq !== wakeSeq) { wakeSeq = data.seq; onWake(); }
          else if (event === 'approval') showApproval(data);
          else if (event === 'approval_done' && data.id === approvalId) { $('approval-sheet').hidden = true; approvalId = ''; }
        }
      }
    }).catch(() => {}).then(() => setTimeout(connectEvents, 3000));
  }

  function handleHash() {
    const hash = location.hash.slice(1);
    if (hash === 'voice' || new URLSearchParams(hash).has('voice')) {
      history.replaceState(null, '', location.pathname);
      onWake();
    }
  }

  function setVoiceMode(on) {
    voiceMode = on;
    misses = 0;
    document.body.classList.toggle('voice', on);
    $('menu-voice-val').textContent = on ? 'on' : 'off';
    syncAura();
    if (on) {
      if (!busy) toggleMic();
    } else {
      if (recorder) recorder.stop();
      if (recognizer) recognizer.stop();
      stopAudio();
      if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
      syncSpeechButton();
    }
  }

  function resumeListening() {
    if (!voiceMode || busy || recorder || recognizer) return;
    setTimeout(() => { if (voiceMode && !busy && !recorder && !recognizer && !audioPlaying && !(typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking)) toggleMic(); }, 400);
  }

  function heardNothing() {
    toast('Did not catch anything.');
    if (!voiceMode) return;
    misses += 1;
    if (misses >= 2) { setVoiceMode(false); toast('Voice mode off: nothing heard.'); }
    else resumeListening();
  }

  function syncSpeechButton() {
    $('stop-speech').hidden = !(audioPlaying || (typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking));
  }

  // ---------------------------------------------------------------- voice in

  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  const canRecord = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && (window.AudioContext || window.webkitAudioContext));
  let recorder = null;   // active offline recording: { stop() }
  let recognizer = null; // active Chrome recognizer

  function micEngine() {
    const pref = status ? status.stt.pref : 'auto';
    const serverOk = status && status.stt.server && canRecord;
    if (pref === 'server') return serverOk ? 'server' : 'none-server';
    if (pref === 'browser') return Recognition ? 'browser' : 'none';
    // Auto: Chrome's recognizer while online (it was the reliable one), whisper when offline.
    if (Recognition && navigator.onLine && !opts.onDevice) return 'browser';
    if (serverOk) return 'server';
    return Recognition ? 'browser' : 'none';
  }

  function syncMicButton() {
    $('mic').hidden = !(opts.mic && (canRecord || Recognition));
    $('row-ondevice').hidden = !Recognition;
  }

  // Record from the mic until silence (or maxMs), return 16 kHz mono samples (null if nothing was said).
  function startRecording(maxMs, silenceMs) {
    let stopNow = () => {};
    const promise = (async () => {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
      const Ctx = window.AudioContext || window.webkitAudioContext;
      const ctx = new Ctx();
      const src = ctx.createMediaStreamSource(stream);
      const node = ctx.createScriptProcessor(4096, 1, 1);
      const chunks = [];
      let heard = false;
      const began = Date.now();
      let lastLoud = began;
      node.onaudioprocess = (ev) => {
        const data = ev.inputBuffer.getChannelData(0);
        chunks.push(new Float32Array(data));
        let sum = 0;
        for (let i = 0; i < data.length; i++) sum += data[i] * data[i];
        const level = Math.sqrt(sum / data.length);
        document.documentElement.style.setProperty('--level', Math.min(1, level * 7).toFixed(2));
        if (level > 0.02) { heard = true; lastLoud = Date.now(); }
      };
      src.connect(node);
      node.connect(ctx.destination);
      await new Promise((resolve) => {
        const timer = setInterval(() => {
          const now = Date.now();
          if ((heard && now - lastLoud > silenceMs) || now - began > maxMs) { clearInterval(timer); resolve(); }
        }, 100);
        stopNow = () => { clearInterval(timer); resolve(); };
      });
      node.disconnect(); src.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      const rate = ctx.sampleRate;
      ctx.close();
      if (!heard) return null;
      let total = 0;
      for (const c of chunks) total += c.length;
      const all = new Float32Array(total);
      let off = 0;
      for (const c of chunks) { all.set(c, off); off += c.length; }
      return resampleMean(all, rate, 16000);
    })();
    return { stop: () => stopNow(), promise };
  }

  async function micServer() {
    $('mic').classList.add('on');
    toast('Listening...');
    recorder = startRecording(20000, 1400);
    let samples;
    try { samples = await recorder.promise; }
    catch (e) { toast(e && e.name === 'NotAllowedError' ? 'Microphone permission was denied.' : 'Could not use the microphone.'); samples = undefined; if (voiceMode) setVoiceMode(false); }
    recorder = null;
    $('mic').classList.remove('on');
    if (samples === undefined) return;
    if (!samples) { heardNothing(); return; }
    toast('Transcribing...');
    try {
      const res = await api('/api/transcribe', { raw: encodeWav(samples, 16000), headers: { 'Content-Type': 'audio/wav' } });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
      if (!data.text) { heardNothing(); return; }
      misses = 0;
      if (busy) { $('input').value = data.text; autoGrow(); return; }
      sendMessage(data.text);
    } catch (e) { toast(e.message); if (voiceMode) setVoiceMode(false); }
  }

  async function ensureOnDevicePack(lang) {
    if (!Recognition.available) return 'unknown';
    try {
      const state = await Recognition.available({ langs: [lang], processLocally: true });
      if (state === 'available') return 'ok';
      if ((state === 'downloadable' || state === 'downloading') && Recognition.install) {
        toast('Downloading the speech pack (one time)...');
        return (await Recognition.install({ langs: [lang], processLocally: true })) ? 'ok' : 'failed';
      }
      return 'unavailable';
    } catch (e) { return 'unknown'; }
  }

  async function micBrowser() {
    const lang = navigator.language || 'en-US';
    if (opts.onDevice) {
      const pack = await ensureOnDevicePack(lang);
      if (pack === 'unavailable' || pack === 'failed') {
        toast('No offline speech pack for ' + lang + '. Set Mic engine to Offline (whisper), or turn off on-device only.');
        return;
      }
    }
    const r = new Recognition();
    r.lang = lang;
    r.interimResults = true;
    r.continuous = false;
    if (opts.onDevice && 'processLocally' in r) r.processLocally = true;
    let finalText = '';
    r.onresult = (ev) => {
      let interim = '';
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const t = ev.results[i][0].transcript;
        if (ev.results[i].isFinal) finalText += t; else interim += t;
      }
      $('input').value = (finalText + interim).trim();
      autoGrow();
    };
    r.onerror = (ev) => {
      if (ev.error === 'not-allowed') { toast('Microphone permission was denied.'); if (voiceMode) setVoiceMode(false); }
      else if (ev.error === 'no-speech') heardNothing();
      else if (ev.error === 'language-not-supported' || ev.error === 'service-not-allowed') {
        toast('Chrome has no offline speech pack. Set Mic engine to Offline (whisper) in settings.');
      } else toast('Mic error: ' + ev.error);
    };
    r.onend = () => {
      recognizer = null;
      $('mic').classList.remove('on');
      const text = $('input').value.trim();
      if (finalText && text && !busy) { misses = 0; $('input').value = ''; autoGrow(); sendMessage(text); }
    };
    recognizer = r;
    $('mic').classList.add('on');
    try { r.start(); } catch (e) { recognizer = null; $('mic').classList.remove('on'); toast('Could not start the mic'); }
  }

  async function toggleMic() {
    if (recorder) { recorder.stop(); return; }
    if (recognizer) { recognizer.stop(); return; }
    const engine = micEngine();
    if (engine === 'server') return micServer();
    if (engine === 'browser') return micBrowser();
    if (engine === 'none-server') { toast((status && status.stt.reason) || 'Offline speech is not set up'); return; }
    toast('This browser has no speech input.');
  }

  // ---------------------------------------------------------------- slash-command menu

  async function loadCommands() {
    try { commandList = (await apiJson('/api/commands')).commands; } catch (e) { /* menu just stays empty */ }
  }

  async function loadSettingRows() {
    try { settingRows = (await apiJson('/api/settings')).settings; } catch (e) { settingRows = []; }
  }

  function closeMenu() { menuRows = []; $('cmdmenu').hidden = true; }

  function renderCommandMenu() {
    const text = $('input').value;
    menuRows = commandSuggestions(text, commandList, settingRows);
    const box = $('cmdmenu');
    if (!menuRows.length) { closeMenu(); return; }
    if (menuSel >= menuRows.length) menuSel = 0;
    box.textContent = '';
    menuRows.forEach((row, i) => {
      const el = document.createElement('div');
      el.className = 'cmd' + (i === menuSel ? ' sel' : '');
      el.setAttribute('role', 'option');
      const name = document.createElement('span');
      name.className = 'cmd-name';
      name.textContent = row.label;
      const desc = document.createElement('span');
      desc.className = 'cmd-desc';
      desc.textContent = row.description || '';
      el.appendChild(name);
      el.appendChild(desc);
      el.addEventListener('mousedown', (ev) => ev.preventDefault());
      el.addEventListener('click', () => pickRow(i, true));
      box.appendChild(el);
    });
    box.hidden = false;
    const sel = box.querySelector('.sel');
    if (sel && sel.scrollIntoView) sel.scrollIntoView({ block: 'nearest' });
  }

  // Fill the input with a row; when the row is a finished command and `run` is set, send it.
  function pickRow(i, run) {
    const row = menuRows[i];
    if (!row) return;
    const input = $('input');
    input.value = row.insert;
    autoGrow();
    input.focus();
    if (run && row.complete) { input.value = ''; autoGrow(); closeMenu(); sendMessage(row.insert); return; }
    menuSel = 0;
    renderCommandMenu();
  }

  function menuKey(ev) {
    if (!menuRows.length) return false;
    if (ev.key === 'ArrowDown') { menuSel = (menuSel + 1) % menuRows.length; renderCommandMenu(); return true; }
    if (ev.key === 'ArrowUp') { menuSel = (menuSel - 1 + menuRows.length) % menuRows.length; renderCommandMenu(); return true; }
    if (ev.key === 'Tab') { pickRow(menuSel, false); return true; }
    if (ev.key === 'Escape') { closeMenu(); return true; }
    if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) {
      const row = menuRows[menuSel];
      // Already typed in full? Let Enter send it normally.
      if (row && $('input').value.trim() === row.insert.trim() && row.complete) return false;
      pickRow(menuSel, true);
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- voice browser

  async function setTts(key, value) {
    try { await apiJson('/api/settings', { key, value }); await pollStatus(true); }
    catch (e) { toast(e.message); }
  }

  function voiceRow(name, sub, on, tag, onPick, onPlay) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'voice-row' + (on ? ' on' : '');
    const play = document.createElement('span');
    play.className = 'play';
    play.textContent = '\u25B6';
    play.addEventListener('click', (ev) => { ev.stopPropagation(); onPlay(); });
    const label = document.createElement('span');
    label.className = 'name';
    label.textContent = name;
    if (tag) { const t = document.createElement('span'); t.className = 'tag'; t.textContent = tag; label.appendChild(t); }
    const small = document.createElement('small');
    small.textContent = sub;
    label.appendChild(small);
    const check = document.createElement('span');
    check.className = 'check';
    check.textContent = '\u2713';
    row.append(play, label, check);
    row.addEventListener('click', onPick);
    return row;
  }

  async function renderVoices() {
    const q = $('voice-search').value.trim().toLowerCase();
    if (!geminiVoices) {
      try { geminiVoices = (await apiJson('/api/voices')).gemini; } catch (e) { geminiVoices = []; }
    }
    const t = (status && status.tts) || { engine: 'system', voice: '' };
    const g = $('gemini-voices');
    g.textContent = '';
    for (const v of geminiVoices.filter((x) => !q || (x.label + ' ' + x.style).toLowerCase().includes(q))) {
      g.appendChild(voiceRow(v.label, v.style, t.engine === 'gemini' && t.voice === v.name, '',
        async () => { await setTts('tts.voice', v.name); await setTts('tts.engine', 'gemini'); renderVoices(); },
        () => playGemini('Hi, I am ' + v.label + '. This is how I sound.', v.name, true)));
    }
    if (!geminiVoices.length) g.textContent = 'Could not load the voice list.';

    const d = $('device-voices');
    d.textContent = '';
    const lang = (navigator.language || 'en').slice(0, 2).toLowerCase();
    const list = (typeof speechSynthesis !== 'undefined' ? speechSynthesis.getVoices() : [])
      .filter((v) => !q || (v.name + ' ' + v.lang).toLowerCase().includes(q))
      .sort((a, b) => (b.lang.toLowerCase().startsWith(lang) - a.lang.toLowerCase().startsWith(lang)) || a.name.localeCompare(b.name));
    for (const v of list) {
      d.appendChild(voiceRow(v.name, v.lang, t.engine === 'system' && opts.voice === v.voiceURI, v.localService ? '' : 'online',
        async () => { saveOpt('voice', v.voiceURI); await setTts('tts.engine', 'system'); loadVoices(); renderVoices(); },
        () => { speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance('Hi, this is ' + v.name + '.'); u.voice = v; u.lang = v.lang; speechSynthesis.speak(u); }));
    }
    if (!list.length) d.textContent = 'No device voices found.';
  }

  // ---------------------------------------------------------------- settings sheet

  async function loadSettings() {
    const list = $('settings-list');
    list.textContent = 'Loading...';
    let data;
    try { data = await apiJson('/api/settings'); } catch (e) { list.textContent = 'Could not load settings.'; return; }
    list.textContent = '';
    for (const s of data.settings) list.appendChild(settingRow(s));
  }

  function settingRow(s) {
    const row = document.createElement('div');
    row.className = 'row';
    const label = document.createElement('span');
    label.className = 'key';
    label.textContent = s.key + (s.restart ? ' *' : '');
    row.appendChild(label);

    const save = async (value, revert) => {
      try {
        const data = await apiJson('/api/settings', { key: s.key, value: String(value) });
        toast(s.key + ' = ' + data.value + (data.restart ? ' (after restart)' : ''));
        settingRows = data.settings || settingRows;
        loadSettings();
        pollStatus(true);
      } catch (e) { toast(e.message); revert(); }
    };

    let control;
    if (s.type === 'bool') {
      control = document.createElement('input');
      control.type = 'checkbox';
      control.checked = !!s.value;
      control.onchange = () => save(control.checked, () => { control.checked = !control.checked; });
    } else if (s.choices) {
      control = document.createElement('select');
      for (const c of s.choices) {
        const o = document.createElement('option');
        o.value = o.textContent = c;
        o.selected = c === s.value;
        control.appendChild(o);
      }
      control.onchange = () => save(control.value, () => { control.value = s.value; });
    } else {
      control = document.createElement('input');
      control.type = 'text';
      control.value = s.value;
      control.onchange = () => save(control.value, () => { control.value = s.value; });
    }
    row.appendChild(control);

    if (s.user) {
      const reset = document.createElement('span');
      reset.className = 'reset';
      reset.textContent = 'reset';
      reset.onclick = async () => {
        try { await apiJson('/api/settings', { key: s.key, unset: true }); loadSettings(); pollStatus(true); }
        catch (e) { toast(e.message); }
      };
      row.appendChild(reset);
    }
    return row;
  }

  function syncSettingsControls() {
    $('opt-speak').checked = opts.speak;
    $('opt-mic').checked = opts.mic;
    $('opt-ondevice').checked = opts.onDevice;
    $('opt-motion').checked = opts.motion;
    $('opt-greeting').checked = opts.greeting;
    $('opt-offline-voices').checked = opts.offlineVoices;
    $('voice-rate').value = opts.rate;
    $('voice-pitch').value = opts.pitch;
    $('rate-val').textContent = Number(opts.rate).toFixed(2) + 'x';
    $('pitch-val').textContent = Number(opts.pitch).toFixed(2);
    $('stt-pick').value = status ? status.stt.pref : 'auto';
    for (const b of $('size-seg').children) b.classList.toggle('on', b.dataset.v === opts.size);
    for (const b of $('font-seg').children) b.classList.toggle('on', b.dataset.v === opts.font);
    $('row-fontfile').hidden = opts.font !== 'custom';
    $('font-name').textContent = store.get('opt_fontname', '') || 'No font chosen yet';
    for (const b of $('engine-seg').children) b.classList.toggle('on', b.dataset.v === ((status && status.tts && status.tts.engine) || 'system'));
    for (const b of $('swatches').children) b.classList.toggle('on', b.dataset.v === opts.accent);
    loadVoices();
    renderWakeRow();
  }

  // ---- tools and actions in the sidebar and the Tools menu

  let agentData = { rev: -1, tools: [], tasks: [] };
  let agentRevSeen = -2;
  let runningTool = '';
  const pretty = (name) => name.replace(/_/g, ' ');

  function maybeRefreshAgent(s) {
    if (s.agent && s.agent.rev !== agentRevSeen) { agentRevSeen = s.agent.rev; refreshAgentUi(); }
  }

  async function refreshAgentUi() {
    try {
      const d = await (await api('/api/agent')).json();
      agentData = { rev: d.rev, tools: d.tools, tasks: d.tasks };
    } catch (e) { return; }
    renderSideSections();
    renderCustomTools();
  }

  function sideRow(label, title, onMain, extras, pill) {
    const row = document.createElement('div');
    row.className = 'side-row';
    const main = document.createElement('button');
    main.type = 'button';
    main.className = 'side-main';
    main.textContent = label;
    main.title = title;
    main.addEventListener('click', onMain);
    row.appendChild(main);
    if (pill) {
      const p = document.createElement('i');
      p.className = 'side-pill';
      p.textContent = pill;
      p.title = 'Higher-level tool';
      row.appendChild(p);
    }
    const acts = document.createElement('span');
    acts.className = 'side-acts';
    for (const [glyph, name, fn] of extras) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = glyph;
      b.title = name;
      b.setAttribute('aria-label', name + ': ' + label);
      b.addEventListener('click', (ev) => { ev.stopPropagation(); fn(); });
      acts.appendChild(b);
    }
    row.appendChild(acts);
    return row;
  }

  function emptyNote(text) {
    const p = document.createElement('p');
    p.className = 'empty-note';
    p.textContent = text;
    return p;
  }

  function renderSideSections() {
    const tools = $('tools-list');
    tools.textContent = '';
    $('tools-count').textContent = String(agentData.tools.length);
    if (!agentData.tools.length) tools.appendChild(emptyNote('Tools Gemini makes show up here.'));
    for (const t of agentData.tools) {
      tools.appendChild(sideRow(pretty(t.name), t.description, () => openRunTool(t.name),
        [['\u25B6', 'Use', () => useTool(t)], ['\u2715', 'Delete', () => deleteTool(t.name)]], t.level === 'high' ? '!' : ''));
    }
    const actions = $('actions-list');
    actions.textContent = '';
    $('actions-count').textContent = String(agentData.tasks.length);
    if (!agentData.tasks.length) actions.appendChild(emptyNote('Tasks Gemini finishes are saved here to run again.'));
    for (const t of agentData.tasks) {
      actions.appendChild(sideRow(t.prompt, t.prompt, () => { closeDrawer(); insertText(t.prompt); },
        [['\u25B6', 'Run again', () => { closeDrawer(); sendMessage(t.prompt); }], ['\u2715', 'Delete', () => deleteTask(t.id)]]));
    }
  }

  function renderCustomTools() {
    const box = $('tools-custom');
    box.textContent = '';
    box.hidden = !agentData.tools.length;
    if (!agentData.tools.length) return;
    const head = document.createElement('small');
    head.textContent = 'Made by Gemini';
    box.appendChild(head);
    for (const t of agentData.tools) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = pretty(t.name) + (t.level === 'high' ? ' !' : '');
      b.title = t.description;
      b.addEventListener('click', () => { $('tools-menu').hidden = true; useTool(t); });
      box.appendChild(b);
    }
  }

  async function runTool(name, params) {
    const res = await api('/api/tools/run', { body: { name, params } });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    return data;
  }

  // No inputs: run it right away. Otherwise ask for them first.
  async function useTool(t) {
    if (Object.keys(t.params || {}).length) { openRunTool(t.name); return; }
    try { const d = await runTool(t.name, {}); toast((d.ok ? '' : 'Failed: ') + d.text.slice(0, 160)); } catch (e) { toast(e.message); }
  }

  function openRunTool(name) {
    const t = agentData.tools.find((x) => x.name === name);
    if (!t) return;
    closeDrawer();
    runningTool = name;
    $('run-title').textContent = pretty(name);
    $('run-name').textContent = name;
    $('run-level').textContent = t.level === 'high' ? 'higher-level' : 'normal';
    $('run-level').classList.toggle('warn-pill', t.level === 'high');
    $('run-uses').textContent = 'used ' + t.uses + 'x';
    $('run-desc').textContent = t.description;
    $('run-how').textContent = t.how_it_works;
    const box = $('run-fields');
    box.textContent = '';
    const params = Object.entries(t.params || {});
    if (!params.length) box.appendChild(emptyNote('No inputs needed.'));
    for (const [p, spec] of params) {
      const label = document.createElement('label');
      label.className = 'run-field';
      const span = document.createElement('span');
      span.textContent = pretty(p) + (spec.description && spec.description !== p ? ' (' + spec.description + ')' : '');
      const input = document.createElement('input');
      input.name = p;
      input.autocomplete = 'off';
      if (spec.type === 'boolean') input.type = 'checkbox';
      else { input.type = spec.type === 'string' ? 'text' : 'number'; if (spec.type === 'number') input.step = 'any'; }
      label.appendChild(span);
      label.appendChild(input);
      box.appendChild(label);
    }
    $('run-result').hidden = true;
    $('run-result').classList.remove('bad');
    $('run-code').textContent = '';
    $('run-code-box').open = false;
    $('run-sheet').hidden = false;
    (box.querySelector('input') || $('run-go')).focus();
  }

  async function runFromSheet() {
    const t = agentData.tools.find((x) => x.name === runningTool);
    if (!t) return;
    const values = {};
    for (const input of $('run-fields').querySelectorAll('input')) {
      const kind = t.params[input.name].type;
      if (kind === 'boolean') values[input.name] = input.checked;
      else if (input.value.trim() === '') { toast('Fill in every field first'); return; }
      else values[input.name] = kind === 'string' ? input.value : Number(input.value);
    }
    $('run-go').disabled = true;
    try {
      const d = await runTool(runningTool, values);
      $('run-result').textContent = d.text;
      $('run-result').classList.toggle('bad', !d.ok);
    } catch (e) {
      $('run-result').textContent = e.message;
      $('run-result').classList.add('bad');
    }
    $('run-result').hidden = false;
    $('run-go').disabled = false;
  }

  async function deleteTool(name) {
    if (!confirm('Delete the tool "' + pretty(name) + '"?')) return;
    try { toast(await command('/tools remove ' + name)); } catch (e) { toast(e.message); }
    $('run-sheet').hidden = true;
    agentRevSeen = -2;
    refreshAgentUi();
  }

  async function deleteTask(id) {
    try { toast(await command('/tasks remove ' + id)); } catch (e) { toast(e.message); }
    agentRevSeen = -2;
    refreshAgentUi();
  }

  async function quickAction(btn) {
    const value = prompt(btn.dataset.ask);
    if (!value || !value.trim()) return;
    closeDrawer();
    try { const d = await runTool(btn.dataset.tool, { [btn.dataset.param]: value.trim() }); toast((d.ok ? '' : 'Failed: ') + d.text.slice(0, 160)); } catch (e) { toast(e.message); }
  }

  function initToolPanels() {
    for (const sec of document.querySelectorAll('.side-sec')) {
      const head = sec.querySelector('.sec-head');
      const body = sec.querySelector('.sec-body');
      const key = 'opt_' + sec.id;
      const set = (open) => { body.hidden = !open; head.setAttribute('aria-expanded', String(open)); sec.classList.toggle('open', open); };
      set(store.get(key, false) === true);
      head.addEventListener('click', () => { set(body.hidden); store.set(key, !body.hidden); });
    }
    for (const b of document.querySelectorAll('.quick-actions button')) b.addEventListener('click', () => quickAction(b));
    $('run-go').addEventListener('click', runFromSheet);
    $('run-delete').addEventListener('click', () => deleteTool(runningTool));
    $('run-close').addEventListener('click', () => { $('run-sheet').hidden = true; });
    $('run-sheet').addEventListener('click', (ev) => { if (ev.target === $('run-sheet')) $('run-sheet').hidden = true; });
    $('run-code-box').addEventListener('toggle', async () => {
      if (!$('run-code-box').open || $('run-code').textContent) return;
      try { $('run-code').textContent = (await (await api('/api/tools/code?name=' + encodeURIComponent(runningTool))).json()).code; } catch (e) { $('run-code').textContent = 'Could not load the code.'; }
    });
  }

  // ---- agent: approving tools Gemini wrote, modes, saved tools and tasks

  let approvalId = '';

  function showApproval(info) {
    approvalId = info.id;
    $('ap-title').textContent = info.kind === 'control' ? 'Let Faze control your phone?' : (info.replaces ? 'Update a tool?' : 'Add a new tool?');
    $('ap-name').textContent = info.name;
    $('ap-level').textContent = info.level === 'high' ? 'higher-level' : 'normal';
    $('ap-level').classList.toggle('warn-pill', info.level === 'high');
    $('ap-desc').textContent = info.description;
    $('ap-how').textContent = info.how_it_works;
    const why = info.reasons || [];
    $('ap-reasons').hidden = !why.length;
    $('ap-reasons').textContent = 'Higher-level because it ' + why.join('; ');
    $('ap-code').textContent = info.code || '';
    $('ap-code').closest('details').hidden = !info.code;
    $('approval-sheet').hidden = false;
    $('ap-allow').focus();
  }

  async function answerApproval(approve, yolo) {
    const id = approvalId;
    $('approval-sheet').hidden = true;
    approvalId = '';
    if (!id) return;
    try {
      if (yolo) await apiJson('/api/settings', { key: 'agent.mode', value: 'yolo' });
      await apiJson('/api/approve', { id, approve });
    } catch (e) { toast(e.message); }
    pollStatus(true);
  }

  function agentList(box, items, build) {
    box.textContent = '';
    if (!items.length) {
      const p = document.createElement('p');
      p.className = 'empty-note';
      p.textContent = 'Nothing saved yet';
      box.appendChild(p);
      return;
    }
    for (const it of items) box.appendChild(build(it));
  }

  function agentItem(title, pill, sub, meta, onRemove) {
    const row = document.createElement('div');
    row.className = 'agent-item';
    const body = document.createElement('div');
    const head = document.createElement('b');
    head.textContent = title;
    body.appendChild(head);
    if (pill) { const p = document.createElement('span'); p.className = 'via' + (pill === 'higher-level' ? ' warn-pill' : ''); p.textContent = pill; body.appendChild(p); }
    const d = document.createElement('small');
    d.textContent = sub;
    body.appendChild(d);
    const m = document.createElement('small');
    m.textContent = meta;
    body.appendChild(m);
    const x = document.createElement('button');
    x.type = 'button';
    x.className = 'icon';
    x.setAttribute('aria-label', 'Remove ' + title);
    x.textContent = '\u2715';
    x.addEventListener('click', onRemove);
    row.appendChild(body);
    row.appendChild(x);
    return row;
  }

  async function loadAgent() {
    let d;
    try { d = await (await api('/api/agent')).json(); } catch (e) { return; }
    for (const b of $('agent-mode-seg').children) b.classList.toggle('on', b.dataset.v === d.mode);
    for (const b of $('control-seg').children) b.classList.toggle('on', b.dataset.v === d.control);
    $('agent-mode-help').textContent = d.modes[d.mode];
    $('opt-elevated').checked = d.high_tools;
    agentList($('agent-tools'), d.tools, (t) => agentItem(t.name, t.level === 'high' ? 'higher-level' : '', t.description + ' ' + t.how_it_works,
      'used ' + t.uses + 'x', async () => { toast(await command('/tools remove ' + t.name)); loadAgent(); }));
    agentList($('agent-tasks'), d.tasks, (t) => agentItem(t.prompt, '', t.tools.length ? 'Uses ' + t.tools.join(', ') : 'Answered directly',
      'reused ' + t.uses + 'x, done ' + t.runs + 'x', async () => { toast(await command('/tasks remove ' + t.id)); loadAgent(); }));
    if (d.pending && d.pending.length && !approvalId) showApproval(d.pending[0]);
  }

  async function clearAgent(what) {
    if (!confirm('Delete all saved ' + what + '?')) return;
    try { toast(await command('/' + what + ' clear')); } catch (e) { toast(e.message); }
    loadAgent();
    pollStatus(true);
  }

  // Settings is a page of its own (General / Voice / Advanced), not a popup. Android's back button leaves it.
  let settingsTab = 'general';

  function showSettings(on) {
    $('settings-view').hidden = !on;
    document.body.classList.toggle('view-settings', on);
    if (on) { syncSettingsControls(); selectTab(settingsTab); loadSettings(); $('settings-view').querySelector('.sv-body').scrollTop = 0; }
  }

  function selectTab(name) {
    settingsTab = name;
    for (const b of $('settings-tabs').children) { const on = b.dataset.tab === name; b.classList.toggle('on', on); b.setAttribute('aria-selected', String(on)); b.tabIndex = on ? 0 : -1; }
    for (const p of $('settings-view').querySelectorAll('.panel')) p.classList.toggle('on', p.dataset.panel === name);
    if (name === 'agent') loadAgent();
  }

  function openSettings() {
    $('menu').hidden = true;
    closeDrawer();
    showSettings(true);
    if (location.hash !== '#settings') history.pushState({ settings: true }, '', '#settings');
  }

  function closeSettings() {
    if (location.hash === '#settings') history.back();
    else showSettings(false);
  }

  async function saveSetting(key, value, revert) {
    try {
      const data = await apiJson('/api/settings', { key, value: String(value) });
      toast(key + ' = ' + data.value + (data.restart ? ' (after restart)' : ''));
      settingRows = data.settings || settingRows;
      loadSettings();
      pollStatus(true);
    } catch (e) { toast(e.message); if (revert) revert(); }
  }

  function renderWakeRow() {
    const w = status && status.wake;
    if (!w) return;
    $('opt-wake').checked = !!w.enabled;
    if (document.activeElement !== $('wake-phrase')) $('wake-phrase').value = w.phrase || '';
    if (document.activeElement !== $('wake-engine')) $('wake-engine').value = w.engine || 'auto';
    const say = '\u201C' + (w.phrase || '') + '\u201D';
    $('wake-status').textContent = !w.enabled ? 'Off'
      : w.state === 'listening' ? 'Listening for ' + say
      : w.state === 'error' ? (w.detail || 'Not working')
      : w.state === 'starting' ? 'Starting...'
      : (w.state || 'On').charAt(0).toUpperCase() + (w.state || 'On').slice(1);
    $('wake-status').classList.toggle('bad-text', w.state === 'error');
  }

  function openModelSheet() {
    $('menu').hidden = true;
    renderModelSheet();
    $('model-sheet').hidden = false;
  }

  // ---------------------------------------------------------------- core, sidebar, composer, log

  const cores = [];
  function coreAll(fn) {
    for (const c of cores) { try { fn(c); } catch (e) { /* drawing must never break chat */ } }
  }

  function initCores() {
    const kit = window.FazeCoreKit;
    if (!kit) return;
    for (const id of ['core-hero', 'core-side', 'core-live']) {
      const el = $(id);
      if (el) cores.push(new kit.FazeCore(el, { scale: 0.3 }));
    }
    kit.refreshTheme();
    kit.setReduced(reducedMotion());
    window.addEventListener('resize', () => cores.forEach((c) => c.resize()));
  }

  function reducedMotion() {
    return !opts.motion || (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  // Core size follows speech, the microphone and streaming text.
  function levelTick() {
    let v = tokenLevel;
    tokenLevel *= 0.78;
    const speaking = audioPlaying || (typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking);
    if (speaking) v = Math.max(v, 0.25 + Math.random() * 0.45);
    if (recorder) v = Math.max(v, Number(document.documentElement.style.getPropertyValue('--level')) || 0);
    coreAll((c) => c.setLevel(v));
  }

  const isWide = () => !window.matchMedia || window.matchMedia('(min-width: 861px)').matches;
  function closeDrawer() { document.body.classList.remove('side-open'); }

  function toggleSidebar() {
    if (isWide()) { saveOpt('sidebar', opts.sidebar === 'open' ? 'closed' : 'open'); applyLook(); }
    else document.body.classList.toggle('side-open');
    setTimeout(() => cores.forEach((c) => c.resize()), 320);
  }

  function renderSidebar() {
    const nav = $('chat-groups');
    nav.textContent = '';
    const groups = groupChats(chats, Date.now(), $('chat-search').value);
    if (!groups.length) {
      const p = document.createElement('p');
      p.className = 'empty-note';
      p.textContent = 'No chats found';
      nav.appendChild(p);
    }
    for (const g of groups) {
      const sec = document.createElement('section');
      sec.className = 'grp' + (g.project ? ' project' : '');
      const h = document.createElement('h4');
      h.textContent = (g.project ? '\u25A3 ' : '') + g.title;
      sec.appendChild(h);
      for (const c of g.items) sec.appendChild(chatItem(c));
      nav.appendChild(sec);
    }
    const name = names.user || 'You';
    $('avatar').textContent = name.charAt(0).toUpperCase();
    $('profile-name').textContent = name;
  }

  function chatItem(c) {
    const row = document.createElement('div');
    row.className = 'chat-item' + (c.id === activeId ? ' on' : '');
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'chat-open';
    open.textContent = c.title || 'New chat';
    open.title = c.title || 'New chat';
    if (c.id === activeId) open.setAttribute('aria-current', 'true');
    open.addEventListener('click', () => openChat(c.id));
    const acts = document.createElement('span');
    acts.className = 'chat-acts';
    const actions = [
      ['Rename', '\u270E', () => { const v = prompt('Rename chat', c.title); if (v && v.trim()) { c.title = v.trim().slice(0, 60); persistChats(); renderSidebar(); } }],
      ['Move to project', '\u25A3', () => { const v = prompt('Project name (leave empty to remove)', c.project || ''); if (v !== null) { c.project = v.trim().slice(0, 30); persistChats(); renderSidebar(); } }],
      ['Delete', '\u2715', () => deleteChat(c)],
    ];
    for (const [label, glyph, fn] of actions) {
      const b = document.createElement('button');
      b.type = 'button';
      b.title = label;
      b.setAttribute('aria-label', label + ': ' + (c.title || 'chat'));
      b.textContent = glyph;
      b.addEventListener('click', (ev) => { ev.stopPropagation(); fn(); });
      acts.appendChild(b);
    }
    row.appendChild(open);
    row.appendChild(acts);
    return row;
  }

  const pendingFiles = [];

  function renderAttachments() {
    const box = $('attachments');
    box.textContent = '';
    pendingFiles.forEach((f, i) => {
      const chipEl = document.createElement('span');
      chipEl.className = 'file-chip';
      chipEl.textContent = '\uD83D\uDCCE ' + f.name + (f.truncated ? ' (truncated)' : '');
      const x = document.createElement('button');
      x.type = 'button';
      x.setAttribute('aria-label', 'Remove ' + f.name);
      x.textContent = '\u2715';
      x.addEventListener('click', () => { pendingFiles.splice(i, 1); renderAttachments(); syncComposerOpen(); });
      chipEl.appendChild(x);
      box.appendChild(chipEl);
    });
    box.hidden = !pendingFiles.length;
  }

  async function addFiles(list) {
    for (const f of Array.from(list || [])) {
      if (pendingFiles.length >= 3) { toast('Up to 3 files per message'); break; }
      if (f.size > 500000) { toast(f.name + ' is too large'); continue; }
      try { pendingFiles.push(clipFile(f.name, await f.text())); } catch (e) { toast("Can't read " + f.name); }
    }
    renderAttachments();
    syncComposerOpen();
  }

  function syncComposerOpen() {
    const form = $('composer');
    const open = form.contains(document.activeElement) || $('input').value.length > 0 || pendingFiles.length > 0;
    form.classList.toggle('open', open);
  }

  function insertText(text) {
    const input = $('input');
    input.value = text;
    autoGrow();
    input.focus();
    $('tools-menu').hidden = true;
    syncComposerOpen();
    renderCommandMenu();
  }

  function initComposerUi() {
    const form = $('composer');
    form.addEventListener('focusin', syncComposerOpen);
    form.addEventListener('focusout', () => setTimeout(syncComposerOpen, 0));
    $('attach').addEventListener('click', () => $('file-input').click());
    $('file-input').addEventListener('change', (ev) => { addFiles(ev.target.files); ev.target.value = ''; });
    $('tools-btn').addEventListener('click', (ev) => { ev.stopPropagation(); $('menu').hidden = true; $('tools-menu').hidden = !$('tools-menu').hidden; });
    $('model-quick').addEventListener('click', openModelSheet);
    for (const b of $('tools-menu').querySelectorAll('[data-insert]')) b.addEventListener('click', () => insertText(b.dataset.insert));
    for (const b of $('starters').children) b.addEventListener('click', () => insertText(b.dataset.insert));
    document.addEventListener('click', (ev) => { if (!$('tools-menu').contains(ev.target) && ev.target !== $('tools-btn') && !$('tools-btn').contains(ev.target)) $('tools-menu').hidden = true; });
    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(() => document.documentElement.style.setProperty('--composer-h', form.offsetHeight + 'px')).observe(form);
    }
    $('thread').addEventListener('click', (ev) => {
      const btn = ev.target.closest('button');
      if (!btn) return;
      const turn = btn.closest('.turn');
      if (btn.classList.contains('copy')) {
        const code = btn.closest('.code').querySelector('code').textContent;
        copyText(code, btn);
      } else if (btn.classList.contains('act-copy') && turn && turn._m) copyText(turn._m.text, btn);
      else if (btn.classList.contains('act-speak') && turn && turn._m) speak(turn._m.text, true);
    });
  }

  function copyText(text, btn) {
    const done = () => { if (btn) { const old = btn.textContent; btn.textContent = 'Copied'; setTimeout(() => { btn.textContent = old; }, 1400); } };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, () => toast('Copy failed'));
    else toast('Copy is not available here');
  }

  function initSidebar() {
    $('menu-btn').addEventListener('click', toggleSidebar);
    $('collapse').addEventListener('click', toggleSidebar);
    $('scrim').addEventListener('click', closeDrawer);
    $('chat-search').addEventListener('input', renderSidebar);
    $('profile').addEventListener('click', openSettings);
    renderSidebar();
  }

  // ---- log panel (also /log)

  let logTimer = 0;
  let logSig = '';

  function logRow(l) {
    const row = document.createElement('div');
    row.className = 'log-row' + (/\bERROR\b|failed/i.test(l.text) ? ' bad' : '');
    const t = document.createElement('time');
    t.textContent = new Date(l.ts * 1000).toTimeString().slice(0, 8);
    const m = l.text.match(/^(\[[^\]]+\])\s*(.*)$/);
    const head = document.createElement('b');
    head.textContent = m ? m[1] : '';
    const rest = document.createElement('span');
    rest.textContent = m ? m[2] : l.text;
    row.appendChild(t);
    row.appendChild(head);
    row.appendChild(rest);
    return row;
  }

  async function refreshLog(force) {
    if ($('log-sheet').hidden) return;
    try {
      const data = await apiJson('/api/log?n=120');
      const last = data.lines[data.lines.length - 1];
      const sig = data.lines.length + ':' + (last ? last.ts + ':' + last.text.length : '');
      if (!force && sig === logSig) return;
      logSig = sig;
      const box = $('log-lines');
      const near = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
      box.textContent = '';
      if (!data.lines.length) {
        const p = document.createElement('p');
        p.className = 'empty-note';
        p.textContent = 'No activity yet. Tool calls and model routing show up here.';
        box.appendChild(p);
        return;
      }
      for (const l of data.lines) box.appendChild(logRow(l));
      if (near || force) box.scrollTop = box.scrollHeight;
    } catch (e) { /* offline: keep what is shown */ }
  }

  function openLog() {
    $('menu').hidden = true;
    closeDrawer();
    $('log-sheet').hidden = false;
    logSig = '';
    refreshLog(true);
    clearInterval(logTimer);
    logTimer = setInterval(refreshLog, 2000);
  }

  function closeLog() {
    $('log-sheet').hidden = true;
    clearInterval(logTimer);
  }

  function initLogPanel() {
    $('log-btn').addEventListener('click', openLog);
    $('menu-log').addEventListener('click', openLog);
    $('close-log').addEventListener('click', closeLog);
    $('log-sheet').addEventListener('click', (ev) => { if (ev.target === $('log-sheet')) closeLog(); });
    $('log-copy').addEventListener('click', () => copyText(Array.from($('log-lines').children).map((r) => r.textContent).join('\n'), $('log-copy')));
  }

  function initShortcuts() {
    document.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') {
        closeMenu();
        closeDrawer();
        closeLog();
        $('menu').hidden = true;
        $('tools-menu').hidden = true;
        $('run-sheet').hidden = true;
        if (!$('approval-sheet').hidden) answerApproval(false, false);
        for (const id of ['model-sheet', 'voice-sheet']) $(id).hidden = true;
        if (!$('settings-view').hidden) closeSettings();
      } else if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'k') {
        ev.preventDefault();
        if (isWide() && opts.sidebar === 'closed') toggleSidebar();
        else document.body.classList.add('side-open');
        $('chat-search').focus();
      }
    });
  }

  // ---------------------------------------------------------------- pairing / boot

  function showPair() { $('pair').hidden = false; }

  function pairWith(value) {
    token = value.trim();
    if (!token) return;
    store.set('faze_token', token);
    $('pair').hidden = true;
    pollStatus(true);
  }

  function autoGrow() {
    const el = $('input');
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 140) + 'px';
  }

  function bindSettings() {
    const on = (id, ev, fn) => $(id).addEventListener(ev, fn);
    on('opt-speak', 'change', (e) => saveOpt('speak', e.target.checked));
    on('opt-mic', 'change', (e) => { saveOpt('mic', e.target.checked); syncMicButton(); });
    on('opt-ondevice', 'change', (e) => saveOpt('onDevice', e.target.checked));
    on('opt-motion', 'change', (e) => { saveOpt('motion', e.target.checked); applyLook(); });
    on('opt-greeting', 'change', (e) => { saveOpt('greeting', e.target.checked); syncHero(); });
    on('opt-offline-voices', 'change', (e) => { saveOpt('offlineVoices', e.target.checked); loadVoices(); });
    on('voice-pick', 'change', (e) => saveOpt('voice', e.target.value));
    on('voice-rate', 'input', (e) => { saveOpt('rate', Number(e.target.value)); $('rate-val').textContent = Number(e.target.value).toFixed(2) + 'x'; });
    on('voice-pitch', 'input', (e) => { saveOpt('pitch', Number(e.target.value)); $('pitch-val').textContent = Number(e.target.value).toFixed(2); });
    on('voice-test', 'click', () => {
      if (typeof speechSynthesis === 'undefined') return toast('This browser has no speech output.');
      speechSynthesis.cancel();
      speechSynthesis.speak(makeUtterance('Hey ' + names.user + ", it's " + names.ai + '. How do I sound?'));
    });
    on('stt-pick', 'change', async (e) => {
      try { await apiJson('/api/settings', { key: 'voice.stt', value: e.target.value }); pollStatus(true); }
      catch (err) { toast(err.message); }
    });
    for (const b of $('size-seg').children) b.addEventListener('click', () => { saveOpt('size', b.dataset.v); applyLook(); syncSettingsControls(); });
    for (const b of $('font-seg').children) b.addEventListener('click', () => { saveOpt('font', b.dataset.v); applyLook(); syncSettingsControls(); });
    $('font-pick').addEventListener('click', () => $('font-file').click());
    $('font-file').addEventListener('change', (ev) => { pickFontFile(ev.target.files[0]); ev.target.value = ''; });
    for (const b of $('engine-seg').children) b.addEventListener('click', async () => { await setTts('tts.engine', b.dataset.v); syncSettingsControls(); });
    on('browse-voices', 'click', () => { $('voice-sheet').hidden = false; renderVoices(); });
    on('close-voices', 'click', () => { $('voice-sheet').hidden = true; });
    on('voice-search', 'input', renderVoices);
    $('voice-sheet').addEventListener('click', (ev) => { if (ev.target === $('voice-sheet')) $('voice-sheet').hidden = true; });
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.addEventListener('voiceschanged', () => { if (!$('voice-sheet').hidden) renderVoices(); });
    for (const [name, color] of Object.entries(ACCENTS)) {
      const b = document.createElement('button');
      b.type = 'button'; b.dataset.v = name; b.style.background = color[0]; b.setAttribute('aria-label', name);
      b.addEventListener('click', () => { saveOpt('accent', name); applyLook(); syncSettingsControls(); });
      $('swatches').appendChild(b);
    }
  }

  function init() {
    const hash = new URLSearchParams(location.hash.slice(1));
    const wantVoice = hash.has('voice');
    if (hash.get('t')) {
      token = hash.get('t');
      store.set('faze_token', token);
      history.replaceState(null, '', location.pathname);
    }
    if (!token) showPair();

    for (const c of chats) for (const m of c.messages) if (m.pending) { m.pending = false; m.failed = true; if (!m.text) m.text = 'Interrupted.'; }
    initCores();
    applyLook();
    bindSettings();
    initSidebar();
    initComposerUi();
    initLogPanel();
    initToolPanels();
    initShortcuts();
    renderAll();
    newGreeting();
    setLive('ready');
    setInterval(levelTick, 80);
    syncMicButton();
    if (typeof speechSynthesis !== 'undefined') {
      speechSynthesis.addEventListener('voiceschanged', loadVoices);
      loadVoices();
    }

    $('composer').addEventListener('submit', (ev) => {
      ev.preventDefault();
      if (busy) { stopTurn(); return; }
      let text = $('input').value.trim();
      if (!text && !pendingFiles.length) return;
      if (!text) text = 'Please look at the attached file.';
      const files = pendingFiles.splice(0);
      $('input').value = '';
      autoGrow();
      closeMenu();
      renderAttachments();
      syncComposerOpen();
      sendMessage(text, files);
    });
    $('input').addEventListener('input', () => { autoGrow(); menuSel = 0; renderCommandMenu(); syncComposerOpen(); });
    $('input').addEventListener('keydown', (ev) => {
      if (menuKey(ev)) { ev.preventDefault(); return; }
      if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) { ev.preventDefault(); if (!busy) $('composer').requestSubmit(); }
    });
    $('mic').addEventListener('click', toggleMic);
    $('stop-speech').addEventListener('click', () => { stopAudio(); if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel(); syncSpeechButton(); resumeListening(); });
    $('menu-voice').addEventListener('click', () => { $('menu').hidden = true; setVoiceMode(!voiceMode); });
    $('voice-stop').addEventListener('click', () => { wakeFlash = false; setVoiceMode(false); syncAura(); });
    $('new-chat').addEventListener('click', newChat);
    $('model-chip').addEventListener('click', openModelSheet);
    $('plus').addEventListener('click', (ev) => { ev.stopPropagation(); $('tools-menu').hidden = true; $('menu').hidden = !$('menu').hidden; });
    document.addEventListener('click', (ev) => { if (!$('menu').contains(ev.target) && !$('plus').contains(ev.target)) $('menu').hidden = true; });
    $('menu-think').addEventListener('click', openModelSheet);
    $('menu-new').addEventListener('click', newChat);
    $('menu-settings').addEventListener('click', openSettings);
    $('open-settings').addEventListener('click', openSettings);
    $('close-settings').addEventListener('click', closeSettings);
    for (const b of $('settings-tabs').children) b.addEventListener('click', () => selectTab(b.dataset.tab));
    $('settings-tabs').addEventListener('keydown', (ev) => {
      const tabs = Array.from($('settings-tabs').children);
      const i = tabs.findIndex((b) => b.dataset.tab === settingsTab);
      if (ev.key === 'ArrowRight' || ev.key === 'ArrowLeft') {
        const next = tabs[(i + (ev.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
        selectTab(next.dataset.tab);
        next.focus();
      }
    });
    $('opt-wake').addEventListener('change', () => saveSetting('wake.enabled', $('opt-wake').checked, () => { $('opt-wake').checked = !$('opt-wake').checked; }));
    $('wake-engine').addEventListener('change', () => saveSetting('wake.engine', $('wake-engine').value, renderWakeRow));
    $('wake-phrase').addEventListener('change', () => saveSetting('wake.phrase', $('wake-phrase').value.trim(), renderWakeRow));
    $('adv-log').addEventListener('click', openLog);
    for (const b of $('agent-mode-seg').children) b.addEventListener('click', async () => { await saveSetting('agent.mode', b.dataset.v); loadAgent(); });
    for (const b of $('control-seg').children) b.addEventListener('click', async () => { await saveSetting('device.control', b.dataset.v); loadAgent(); });
    $('opt-elevated').addEventListener('change', async () => { await saveSetting('agent.high_tools', $('opt-elevated').checked, () => { $('opt-elevated').checked = !$('opt-elevated').checked; }); loadAgent(); });
    $('clear-tools').addEventListener('click', () => clearAgent('tools'));
    $('clear-tasks').addEventListener('click', () => clearAgent('tasks'));
    $('ap-allow').addEventListener('click', () => answerApproval(true, false));
    $('ap-deny').addEventListener('click', () => answerApproval(false, false));
    $('ap-yolo').addEventListener('click', () => answerApproval(true, true));
    window.addEventListener('popstate', () => showSettings(location.hash === '#settings'));
    if (location.hash === '#settings') showSettings(true);
    $('model-sheet').addEventListener('click', (ev) => { if (ev.target === $('model-sheet')) $('model-sheet').hidden = true; });
    $('think-level').addEventListener('change', async () => {
      try { toast(await command('/think ' + $('think-level').value)); } catch (e) { toast(e.message); }
      pollStatus(true);
    });
    $('pair-go').addEventListener('click', () => pairWith($('pair-input').value));

    window.addEventListener('beforeinstallprompt', (ev) => {
      ev.preventDefault();
      installEvent = ev;
      $('install').hidden = false;
      $('install-hint').hidden = true;
    });
    $('install').addEventListener('click', async () => {
      if (!installEvent) return;
      installEvent.prompt();
      await installEvent.userChoice;
      installEvent = null;
      $('install').hidden = true;
    });
    window.addEventListener('appinstalled', () => { toast('Installed'); $('install').hidden = true; });

    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});

    loadCommands();
    loadSettingRows();
    connectEvents();
    window.addEventListener('hashchange', handleHash);
    setInterval(syncAura, 250);
    setTimeout(() => { if (wantVoice) onWake(); else handleHash(); }, 600);
    pollStatus(true);
    setInterval(pollStatus, 3000);
    document.addEventListener('visibilitychange', () => pollStatus(true));
  }

  init();
})();
