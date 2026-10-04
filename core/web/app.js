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
    const out = [];
    let inList = false;
    let inCode = false;
    let code = [];
    const closeList = () => { if (inList) { out.push('</ul>'); inList = false; } };
    for (const line of escapeHtml(src).split('\n')) {
      if (line.trim().startsWith('```')) {
        if (inCode) { out.push('<pre><code>' + code.join('\n') + '</code></pre>'); code = []; inCode = false; }
        else { closeList(); inCode = true; }
        continue;
      }
      if (inCode) { code.push(line); continue; }
      const li = line.match(/^\s*[*\-\u2022]\s+(.*)$/);
      if (li) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push('<li>' + inline(li[1]) + '</li>');
        continue;
      }
      closeList();
      const h = line.match(/^#{1,4}\s+(.*)$/);
      if (h) { out.push('<div class="h">' + inline(h[1]) + '</div>'); continue; }
      out.push(line.trim() === '' ? '<div class="gap"></div>' : '<div>' + inline(line) + '</div>');
    }
    if (inCode) out.push('<pre><code>' + code.join('\n') + '</code></pre>');
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

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { escapeHtml, renderMarkdown, parseSSE, stripForSpeech, greetingFor, GREETINGS,
      modelLabel, stayLabel, resampleMean, encodeWav, commandSuggestions };
    return;
  }

  // ---------------------------------------------------------------- state

  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* storage full or blocked */ } },
  };

  const ACCENTS = { orange: '#d97757', blue: '#58a6ff', green: '#5fb878', purple: '#a78bfa', pink: '#f472b6' };

  let token = store.get('faze_token', '');
  let chat = store.get('faze_chat', []);
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
  const seenNotices = new Set();

  const opts = {
    speak: store.get('opt_speak', false),
    mic: store.get('opt_mic', true),
    onDevice: store.get('opt_ondevice', true),
    accent: store.get('opt_accent', 'orange'),
    size: store.get('opt_size', 'm'),
    motion: store.get('opt_motion', true),
    greeting: store.get('opt_greeting', true),
    voice: store.get('opt_voice', ''),
    offlineVoices: store.get('opt_offline_voices', true),
    rate: store.get('opt_rate', 1),
    pitch: store.get('opt_pitch', 1),
  };

  function saveChat() { store.set('faze_chat', chat.slice(-200)); }
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
    root.style.setProperty('--accent', ACCENTS[opts.accent] || ACCENTS.orange);
    root.dataset.size = opts.size;
    root.dataset.motion = opts.motion ? 'on' : 'off';
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

  function bubbleHtml(m) {
    let html = '';
    if (m.role === 'faze' && showTag && m.tag) {
      html += '<div class="tag">[' + escapeHtml(m.tag + (m.note ? ' | ' + m.note : '')) + ']</div>';
    }
    if (m.role === 'system') return '<div class="body">' + escapeHtml(m.text) + '</div>';
    if (m.pending && !m.text) {
      html += '<div class="body"><span class="dots"><i></i><i></i><i></i></span></div>';
    } else {
      html += '<div class="body">' + renderMarkdown(m.text || '') + '</div>';
    }
    if (m.thinking) {
      const open = m.pending && !m.text ? ' open' : '';
      html += '<details' + open + '><summary>thinking</summary><pre>' + escapeHtml(m.thinking) + '</pre></details>';
    }
    if (m.trace && m.trace.length) html += '<details><summary>trace</summary><pre>' + escapeHtml(m.trace.join('\n')) + '</pre></details>';
    return html;
  }

  function renderMessage(m, el) {
    el = el || document.createElement('div');
    el.className = 'msg ' + m.role + (m.failed ? ' failed' : '') + (m.pending && m.text ? ' typing' : '');
    if (m.role === 'user') el.textContent = m.text;
    else el.innerHTML = bubbleHtml(m);
    return el;
  }

  function scrollDown() { const log = $('log'); log.scrollTop = log.scrollHeight; }

  function syncHero() {
    $('log').classList.toggle('empty', chat.length === 0);
    $('hero').classList.toggle('hidden', !opts.greeting);
  }

  function newGreeting() {
    $('greeting').textContent = greetingFor(new Date().getHours(), names.user, names.ai);
  }

  function renderAll() {
    const log = $('log');
    for (const el of Array.from(log.querySelectorAll('.msg'))) el.remove();
    for (const m of chat) log.appendChild(renderMessage(m));
    syncHero();
    scrollDown();
  }

  function addMessage(m) {
    chat.push(m);
    const el = renderMessage(m);
    $('log').appendChild(el);
    syncHero();
    scrollDown();
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
    $('input').placeholder = 'Message ' + s.name;
    $('banner').hidden = true;

    const chips = $('chips');
    chips.textContent = '';
    const st = modelStates(s);
    chips.appendChild(chip('qwen ' + st.qwen.sub, st.qwen.cls));
    if (s.lfm === 'running') chips.appendChild(chip('lfm on', 'ok'));
    if (!s.gemini) chips.appendChild(chip('gemini off', ''));

    $('model-label').textContent = modelLabel(s.mode, s.forced_turns);
    $('menu-think-val').textContent = s.think;
    for (const b of $('think-seg').children) b.classList.toggle('on', b.dataset.v === s.think);
    $('stt-note').textContent = s.stt.server ? 'offline engine ready' : (s.stt.reason || '');

    if (!$('model-sheet').hidden) renderModelSheet();
    for (const n of s.notices || []) {
      if (!seenNotices.has(n)) { seenNotices.add(n); addMessage({ role: 'system', text: n }); saveChat(); }
    }
  }

  function renderModelSheet() {
    if (!status) return;
    const list = $('model-list');
    list.textContent = '';
    const st = modelStates(status);
    for (const id of ['auto', 'qwen', 'lfm', 'gemini']) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'model-row' + (status.mode === id ? ' on' : '');
      row.innerHTML = '<span class="dot ' + st[id].cls + '"></span><span class="name">' + MODEL_NAMES[id] +
        '<small>' + escapeHtml(st[id].sub) + '</small></span><span class="check">&#10003;</span>';
      row.onclick = () => chooseModel(id);
      list.appendChild(row);
    }
    const forced = status.mode !== 'auto';
    $('stay').hidden = !forced;
    if (forced) {
      const n = status.forced_turns || 0;
      $('stay-range').value = n;
      $('stay-val').textContent = stayLabel(n);
    }
  }

  async function chooseModel(id) {
    try {
      const n = Number($('stay-range').value) || 0;
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

  function setBusy(on) {
    busy = on;
    document.body.classList.toggle('busy', on);
    $('send-icon').textContent = on ? '\u25A0' : '\u2191';
    $('send').setAttribute('aria-label', on ? 'Stop' : 'Send');
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

  async function sendMessage(text) {
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
    syncSpeechButton();
    if (text.startsWith('/')) return runCommand(text);

    addMessage({ role: 'user', text });
    const m = { role: 'faze', text: '', tag: '', note: '', thinking: '', trace: [], pending: true };
    chat.push(m);
    const el = renderMessage(m);
    $('log').appendChild(el);
    scrollDown();
    setBusy(true);

    const paint = () => { renderMessage(m, el); scrollDown(); };
    try {
      const res = await api('/api/chat', { body: { message: text } });
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
            if (m.text) { m.trace.push('(discarded partial reply from ' + m.tag + ')'); m.text = ''; m.thinking = ''; }
            m.tag = data.tag; m.note = data.note;
          } else if (event === 'token') {
            if (data.kind === 'thinking') m.thinking += data.text; else m.text += data.text;
          } else if (event === 'trace') {
            m.trace.push(data.line);
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
    if (m.text === '[stopped]') { m.text = 'Stopped.'; m.tag = ''; }
    paint();
    saveChat();
    setBusy(false);
    speak(m.failed || m.text === 'Stopped.' ? '' : m.text);
    pollStatus(true);
  }

  async function newChat() {
    $('menu').hidden = true;
    if (busy) await stopTurn();
    try { await command('/reset'); } catch (e) { toast(e.message); return; }
    chat = [];
    seenNotices.clear();
    saveChat();
    renderAll();
    newGreeting();
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

  function speak(text) {
    const wanted = (opts.speak || voiceMode) && typeof speechSynthesis !== 'undefined' && text;
    const clean = wanted ? stripForSpeech(text) : '';
    if (!clean) { resumeListening(); return; }
    const u = makeUtterance(clean);
    u.onend = u.onerror = () => { syncSpeechButton(); resumeListening(); };
    speechSynthesis.speak(u);
    syncSpeechButton();
  }

  // Voice mode: listen, answer out loud, then listen again until you stop it.
  function setVoiceMode(on) {
    voiceMode = on;
    misses = 0;
    document.body.classList.toggle('voice', on);
    $('menu-voice-val').textContent = on ? 'on' : 'off';
    if (on) {
      if (!busy) toggleMic();
    } else {
      if (recorder) recorder.stop();
      if (recognizer) recognizer.stop();
      if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
      syncSpeechButton();
    }
  }

  function resumeListening() {
    if (!voiceMode || busy || recorder || recognizer) return;
    setTimeout(() => { if (voiceMode && !busy && !recorder && !recognizer && !(typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking)) toggleMic(); }, 400);
  }

  function heardNothing() {
    toast('Did not catch anything.');
    if (!voiceMode) return;
    misses += 1;
    if (misses >= 2) { setVoiceMode(false); toast('Voice mode off: nothing heard.'); }
    else resumeListening();
  }

  function syncSpeechButton() {
    $('stop-speech').hidden = !(typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking);
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
        if (Math.sqrt(sum / data.length) > 0.02) { heard = true; lastLoud = Date.now(); }
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
    for (const b of $('swatches').children) b.classList.toggle('on', b.dataset.v === opts.accent);
    loadVoices();
  }

  function openSettings() {
    $('menu').hidden = true;
    syncSettingsControls();
    $('sheet').hidden = false;
    loadSettings();
  }

  function openModelSheet() {
    $('menu').hidden = true;
    renderModelSheet();
    $('model-sheet').hidden = false;
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
    for (const [name, color] of Object.entries(ACCENTS)) {
      const b = document.createElement('button');
      b.type = 'button'; b.dataset.v = name; b.style.background = color; b.setAttribute('aria-label', name);
      b.addEventListener('click', () => { saveOpt('accent', name); applyLook(); syncSettingsControls(); });
      $('swatches').appendChild(b);
    }
  }

  function init() {
    const hash = new URLSearchParams(location.hash.slice(1));
    if (hash.get('t')) {
      token = hash.get('t');
      store.set('faze_token', token);
      history.replaceState(null, '', location.pathname);
    }
    if (!token) showPair();

    applyLook();
    bindSettings();
    renderAll();
    newGreeting();
    syncMicButton();
    if (typeof speechSynthesis !== 'undefined') {
      speechSynthesis.addEventListener('voiceschanged', loadVoices);
      loadVoices();
    }

    $('composer').addEventListener('submit', (ev) => {
      ev.preventDefault();
      if (busy) { stopTurn(); return; }
      const text = $('input').value.trim();
      if (!text) return;
      $('input').value = '';
      autoGrow();
      closeMenu();
      sendMessage(text);
    });
    $('input').addEventListener('input', () => { autoGrow(); menuSel = 0; renderCommandMenu(); });
    $('input').addEventListener('keydown', (ev) => {
      if (menuKey(ev)) { ev.preventDefault(); return; }
      if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) { ev.preventDefault(); if (!busy) $('composer').requestSubmit(); }
    });
    $('mic').addEventListener('click', toggleMic);
    $('stop-speech').addEventListener('click', () => { speechSynthesis.cancel(); syncSpeechButton(); resumeListening(); });
    $('menu-voice').addEventListener('click', () => { $('menu').hidden = true; setVoiceMode(!voiceMode); });
    $('new-chat').addEventListener('click', newChat);
    $('model-chip').addEventListener('click', openModelSheet);
    $('plus').addEventListener('click', (ev) => { ev.stopPropagation(); $('menu').hidden = !$('menu').hidden; });
    document.addEventListener('click', (ev) => { if (!$('menu').contains(ev.target)) $('menu').hidden = true; });
    $('menu-think').addEventListener('click', async () => {
      const next = { auto: 'on', on: 'off', off: 'auto' }[(status && status.think) || 'auto'];
      try { toast(await command('/think ' + next)); } catch (e) { toast(e.message); }
      pollStatus(true);
    });
    $('menu-new').addEventListener('click', newChat);
    $('menu-settings').addEventListener('click', openSettings);
    $('open-settings').addEventListener('click', openSettings);
    $('close-settings').addEventListener('click', () => { $('sheet').hidden = true; });
    $('sheet').addEventListener('click', (ev) => { if (ev.target === $('sheet')) $('sheet').hidden = true; });
    $('model-sheet').addEventListener('click', (ev) => { if (ev.target === $('model-sheet')) $('model-sheet').hidden = true; });
    $('stay-range').addEventListener('input', (ev) => { $('stay-val').textContent = stayLabel(ev.target.value); });
    $('stay-range').addEventListener('change', () => { if (status && status.mode !== 'auto') chooseModel(status.mode); });
    for (const b of $('think-seg').children) {
      b.addEventListener('click', async () => {
        try { toast(await command('/think ' + b.dataset.v)); } catch (e) { toast(e.message); }
        pollStatus(true);
      });
    }
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
    pollStatus(true);
    setInterval(pollStatus, 3000);
    document.addEventListener('visibilitychange', () => pollStatus(true));
  }

  init();
})();
