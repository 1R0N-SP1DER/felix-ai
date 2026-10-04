/* Faze web app. Pure helpers first (unit-tested under node), then the browser UI. */
(function () {
  'use strict';

  // ---------------------------------------------------------------- helpers

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
    let t = String(text)
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

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { escapeHtml, renderMarkdown, parseSSE, stripForSpeech };
    return;
  }

  // ---------------------------------------------------------------- app state

  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* storage full or blocked */ } },
  };

  let token = store.get('faze_token', '');
  let chat = store.get('faze_chat', []);
  let busy = false;
  let showTag = true;
  let installEvent = null;
  const seenNotices = new Set();

  const opts = {
    speak: store.get('opt_speak', false),
    mic: store.get('opt_mic', false),
    onDevice: store.get('opt_ondevice', true),
  };

  function saveChat() { store.set('faze_chat', chat.slice(-200)); }

  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { el.hidden = true; }, 3500);
  }

  // ---------------------------------------------------------------- api

  async function api(path, options) {
    options = options || {};
    const headers = Object.assign({ Authorization: 'Bearer ' + token }, options.headers || {});
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetch(path, {
      method: options.method || (options.body !== undefined ? 'POST' : 'GET'),
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
    if (res.status === 401) { showPair(); throw new Error('not paired'); }
    return res;
  }

  async function apiJson(path, body) {
    const res = await api(path, body === undefined ? {} : { body });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    return data;
  }

  // ---------------------------------------------------------------- rendering

  function bubbleHtml(m) {
    if (m.role === 'user') return null;
    let html = '';
    if (m.role === 'faze' && showTag && m.tag) {
      html += '<div class="tag">[' + escapeHtml(m.tag + (m.note ? ' | ' + m.note : '')) + ']</div>';
    }
    html += '<div class="body">' + (m.role === 'system' ? escapeHtml(m.text) : renderMarkdown(m.text || '')) + '</div>';
    if (m.thinking) html += '<details><summary>thinking</summary><pre>' + escapeHtml(m.thinking) + '</pre></details>';
    if (m.trace && m.trace.length) html += '<details><summary>trace</summary><pre>' + escapeHtml(m.trace.join('\n')) + '</pre></details>';
    return html;
  }

  function renderMessage(m, el) {
    el = el || document.createElement('div');
    el.className = 'msg ' + m.role + (m.failed ? ' failed' : '') + (m.pending && !m.text ? ' typing' : '');
    if (m.role === 'user') el.textContent = m.text;
    else el.innerHTML = bubbleHtml(m);
    if (m.pending && m.text) el.classList.add('typing');
    return el;
  }

  function scrollDown() { const log = $('log'); log.scrollTop = log.scrollHeight; }

  function renderAll() {
    const log = $('log');
    log.textContent = '';
    for (const m of chat) log.appendChild(renderMessage(m));
    scrollDown();
  }

  function addMessage(m) {
    chat.push(m);
    const el = renderMessage(m);
    $('log').appendChild(el);
    scrollDown();
    return el;
  }

  // ---------------------------------------------------------------- status

  function chip(text, cls) {
    const s = document.createElement('span');
    s.className = 'chip ' + cls;
    s.textContent = text;
    return s;
  }

  async function pollStatus() {
    if (document.hidden) return;
    try {
      const res = await api('/api/status');
      const s = await res.json();
      $('banner').hidden = true;
      showTag = s.show_tag;
      const chips = $('chips');
      chips.textContent = '';
      const q = s.qwen.state;
      const qOk = q === 'ready' || q === 'unmanaged';
      chips.appendChild(chip('qwen ' + (s.qwen.note ? s.qwen.note.replace(/^qwen /, '') : (qOk ? 'ready' : q)),
        qOk ? 'ok' : (q === 'failed' ? 'bad' : 'warn')));
      chips.appendChild(chip('lfm ' + s.lfm, s.lfm === 'running' ? 'ok' : ''));
      chips.appendChild(chip('gemini ' + (s.gemini ? 'on' : 'off'), s.gemini ? 'ok' : ''));
      if (s.mode !== 'auto') chips.appendChild(chip('mode ' + s.mode, 'warn'));
      for (const n of s.notices || []) {
        if (!seenNotices.has(n)) { seenNotices.add(n); addMessage({ role: 'system', text: n }); saveChat(); }
      }
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
    $('send').disabled = on;
  }

  async function runCommand(line) {
    addMessage({ role: 'user', text: line });
    try {
      const data = await apiJson('/api/command', { line });
      addMessage({ role: 'system', text: data.text });
    } catch (e) {
      addMessage({ role: 'system', text: 'Command failed: ' + e.message });
    }
    saveChat();
    pollStatus();
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
    paint();
    saveChat();
    setBusy(false);
    speak(m.text);
    pollStatus();
  }

  // ---------------------------------------------------------------- voice

  function speak(text) {
    if (!opts.speak || typeof speechSynthesis === 'undefined' || !text) return;
    const clean = stripForSpeech(text);
    if (!clean) return;
    const u = new SpeechSynthesisUtterance(clean);
    u.onend = u.onerror = syncSpeechButton;
    speechSynthesis.speak(u);
    syncSpeechButton();
  }

  function syncSpeechButton() {
    $('stop-speech').hidden = !(typeof speechSynthesis !== 'undefined' && speechSynthesis.speaking);
  }

  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recognizer = null;

  function micErrorText(code) {
    if (code === 'language-not-supported' || code === 'service-not-allowed') {
      return opts.onDevice
        ? 'No on-device speech pack for this language. Turn off "on-device only" to use the cloud recognizer.'
        : 'Speech recognition is not available.';
    }
    if (code === 'not-allowed') return 'Microphone permission was denied.';
    if (code === 'no-speech') return 'Did not catch anything.';
    return 'Mic error: ' + code;
  }

  function toggleMic() {
    if (!Recognition) return;
    if (recognizer) { recognizer.stop(); return; }
    const r = new Recognition();
    r.lang = navigator.language || 'en-US';
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
    r.onerror = (ev) => toast(micErrorText(ev.error));
    r.onend = () => {
      recognizer = null;
      $('mic').classList.remove('on');
      const text = $('input').value.trim();
      if (finalText && text && !busy) { $('input').value = ''; autoGrow(); sendMessage(text); }
    };
    recognizer = r;
    $('mic').classList.add('on');
    try { r.start(); } catch (e) { recognizer = null; $('mic').classList.remove('on'); toast('Could not start the mic'); }
  }

  function syncMicButton() { $('mic').hidden = !(Recognition && opts.mic); }

  // ---------------------------------------------------------------- settings

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
        loadSettings();
        pollStatus();
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
        try { await apiJson('/api/settings', { key: s.key, unset: true }); loadSettings(); }
        catch (e) { toast(e.message); }
      };
      row.appendChild(reset);
    }
    return row;
  }

  function openSettings() {
    $('opt-speak').checked = opts.speak;
    $('opt-mic').checked = opts.mic;
    $('opt-ondevice').checked = opts.onDevice;
    $('row-mic').hidden = !Recognition;
    $('row-ondevice').hidden = !Recognition;
    $('sheet').hidden = false;
    loadSettings();
  }

  // ---------------------------------------------------------------- pairing / boot

  function showPair() { $('pair').hidden = false; }

  function pairWith(value) {
    token = value.trim();
    if (!token) return;
    store.set('faze_token', token);
    $('pair').hidden = true;
    pollStatus();
  }

  function autoGrow() {
    const el = $('input');
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 140) + 'px';
  }

  function init() {
    const hash = new URLSearchParams(location.hash.slice(1));
    if (hash.get('t')) {
      token = hash.get('t');
      store.set('faze_token', token);
      history.replaceState(null, '', location.pathname);
    }
    if (!token) showPair();

    renderAll();
    syncMicButton();

    $('composer').addEventListener('submit', (ev) => {
      ev.preventDefault();
      const text = $('input').value.trim();
      if (!text || busy) return;
      $('input').value = '';
      autoGrow();
      sendMessage(text);
    });
    $('input').addEventListener('input', autoGrow);
    $('input').addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) { ev.preventDefault(); $('composer').requestSubmit(); }
    });
    $('mic').addEventListener('click', toggleMic);
    $('stop-speech').addEventListener('click', () => { speechSynthesis.cancel(); syncSpeechButton(); });
    $('open-settings').addEventListener('click', openSettings);
    $('close-settings').addEventListener('click', () => { $('sheet').hidden = true; });
    $('sheet').addEventListener('click', (ev) => { if (ev.target === $('sheet')) $('sheet').hidden = true; });
    $('pair-go').addEventListener('click', () => pairWith($('pair-input').value));

    $('opt-speak').addEventListener('change', (ev) => { opts.speak = ev.target.checked; store.set('opt_speak', opts.speak); });
    $('opt-mic').addEventListener('change', (ev) => { opts.mic = ev.target.checked; store.set('opt_mic', opts.mic); syncMicButton(); });
    $('opt-ondevice').addEventListener('change', (ev) => { opts.onDevice = ev.target.checked; store.set('opt_ondevice', opts.onDevice); });

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

    pollStatus();
    setInterval(pollStatus, 3000);
    document.addEventListener('visibilitychange', pollStatus);
  }

  init();
})();
