// The voice overlay: shows one server-side voice exchange (GET /api/voice/state + the "voice" SSE event).
// Open it as /overlay#t=<token>&auto  (auto = start listening on load unless an exchange is already running).
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const LABELS = { idle: 'Ready', listening: 'Listening...', transcribing: 'Got it...', thinking: 'Thinking...', speaking: 'Speaking', done: 'Done', error: 'Something went wrong' };
  const params = new URLSearchParams((location.hash || '').replace(/^#/, ''));
  let token = params.get('t') || '';
  try {
    if (token) localStorage.setItem('faze_overlay_token', token); else token = localStorage.getItem('faze_overlay_token') || '';
  } catch (e) { /* storage can be off in a WebView */ }
  if (token) history.replaceState(null, '', location.pathname + (params.has('auto') ? '#auto' : ''));
  const auto = params.has('auto') || location.hash === '#auto';

  async function api(path, body) {
    const res = await fetch(path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: Object.assign({ Authorization: 'Bearer ' + token }, body === undefined ? {} : { 'Content-Type': 'application/json' }),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) throw new Error(res.status === 401 ? 'wrong or missing token' : 'request failed (' + res.status + ')');
    return res.json();
  }

  function show(el, text) { el.hidden = !text; el.textContent = text || ''; }

  const secs = (ms) => (ms / 1000).toFixed(1) + 's';
  function timingsNote(t) {
    if (!t) return '';
    const parts = [['heard', t.listen_ms], ['transcribed', t.stt_ms], ['first word', t.first_token_ms], ['total', t.total_ms]];
    return parts.filter((p) => typeof p[1] === 'number').map((p) => p[0] + ' ' + secs(p[1])).join(' | ');
  }

  function render(s) {
    $('card').dataset.state = s.state;
    $('state').textContent = LABELS[s.state] || s.state;
    show($('you'), s.transcript);
    show($('reply'), s.reply);
    show($('note'), s.state === 'error' ? s.error : (s.note || (s.state === 'done' ? timingsNote(s.timings) : '')));
    $('again').hidden = !!s.active;
    $('stop').hidden = !s.active;
    const reply = $('reply');
    reply.scrollTop = reply.scrollHeight;
  }

  function fail(e) { render({ state: 'error', error: e.message, active: false, transcript: '', reply: '' }); }
  const start = () => api('/api/voice/start', {}).then(render).catch(fail);

  $('again').addEventListener('click', start);
  $('stop').addEventListener('click', () => api('/api/voice/stop', {}).then(render).catch(fail));
  $('open').addEventListener('click', () => api('/api/open', { close_overlay: true }).catch(fail));
  $('close').addEventListener('click', () => api('/api/voice/stop', { close: true }).then(render).catch(fail));

  // fetch-based event stream (EventSource cannot send the Authorization header)
  async function listen() {
    for (;;) {
      try {
        const res = await fetch('/api/events', { headers: { Authorization: 'Bearer ' + token } });
        if (!res.ok) throw new Error('events ' + res.status);
        const reader = res.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            const block = buf.slice(0, i); buf = buf.slice(i + 2);
            const ev = /^event: (.*)$/m.exec(block), data = /^data: (.*)$/m.exec(block);
            if (ev && ev[1] === 'voice' && data) { try { render(JSON.parse(data[1])); } catch (e) { /* ignore a bad frame */ } }
          }
        }
      } catch (e) { /* reconnect below */ }
      await new Promise((r) => setTimeout(r, 1500));
    }
  }

  (async function init() {
    if (!token) return fail(new Error('no token: open /overlay#t=<token>&auto'));
    try {
      const s = await api('/api/voice/state');
      render(s);
      listen();
      if (auto && !s.active) await start();
    } catch (e) { fail(e); }
  })();
})();
