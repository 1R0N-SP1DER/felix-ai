// Drives the real index.html + core.js + app.js in jsdom against a fake FAZE server.
// Needs `npm install jsdom` somewhere above this folder; the Python test skips itself otherwise.
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const WEB = path.join(__dirname, '..', 'web');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const errors = [];
const calls = [];
let ops = 0;

const status = { name: 'Faze', qwen: { state: 'ready', detail: '', note: '' }, lfm: 'idle', gemini: true, mode: 'auto', busy: false, notices: [],
  show_tag: true, wake: { enabled: false }, tts: { engine: 'system' }, user_name: 'Felix', forced_turns: 0, turns: 0, think: 'auto',
  stt: { server: false, pref: 'auto', reason: 'no whisper' } };

function sse(events) {
  const enc = new TextEncoder();
  let i = 0;
  const stream = new ReadableStream({
    async pull(controller) {
      if (i >= events.length) { controller.close(); return; }
      await sleep(15);
      const [event, data] = events[i++];
      controller.enqueue(enc.encode('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'));
    },
  });
  return { ok: true, status: 200, body: stream, json: async () => ({}) };
}

const REPLY = 'Here is **code**:\n```js\nconst a = 1;\n```\n1. first\n2. second';

async function fakeFetch(url, opts) {
  opts = opts || {};
  const p = String(url).split('?')[0];
  const body = opts.body ? JSON.parse(opts.body) : null;
  calls.push({ path: p, body });
  const json = (obj) => ({ ok: true, status: 200, json: async () => obj });
  if (p === '/api/events') return new Promise(() => {});
  if (p === '/api/status') return json(status);
  if (p === '/api/commands') return json({ commands: [
    { name: 'model', description: 'Choose model', usage: '/model [auto]', args: [{ value: 'auto', description: 'x' }] },
    { name: 'log', description: 'Show recent activity', usage: '/log [count]', args: [] }] });
  if (p === '/api/settings') return json({ settings: [] });
  if (p === '/api/log') return json({ lines: [{ ts: 1700000000, text: '[qwen -> get_battery_status] {} => 80% (12ms)' }] });
  if (p === '/api/command') return json({ text: 'ok' });
  if (p === '/api/restore') return json({ ok: true, kept: (body.messages || []).length });
  if (p === '/api/chat') {
    return sse([
      ['route', { tag: 'qwen', note: 'ready' }],
      ['trace', { line: '[qwen -> search_web] {"query": "x"} => ok (40ms)' }],
      ['route', { tag: 'gemini', note: 'fallback' }],
      ['token', { kind: 'text', text: 'Here is ' }],
      ['reply', { text: REPLY, route: 'gemini' }],
    ]);
  }
  return { ok: false, status: 404, json: async () => ({ error: 'nope' }) };
}

(async () => {
  const read = (f) => fs.readFileSync(path.join(WEB, f), 'utf8');
  const html = read('index.html')
    .split('<script src="/core.js"></script>').join('<script>' + read('core.js') + '</script>')
    .split('<script src="/app.js"></script>').join('<script>' + read('app.js') + '</script>');
  const vc = new VirtualConsole();
  vc.on('jsdomError', (e) => errors.push('jsdom: ' + (e.detail || e.message)));
  vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));
  const dom = new JSDOM(html, { url: 'http://localhost:8787/', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(w) {
      w.localStorage.setItem('faze_token', JSON.stringify('tok'));
      w.fetch = fakeFetch;
      w.ReadableStream = ReadableStream; w.TextDecoder = TextDecoder; w.TextEncoder = TextEncoder;
      w.matchMedia = (q) => ({ matches: /min-width: 861px/.test(q), media: q, addEventListener() {}, removeEventListener() {} });
      w.Element.prototype.scrollIntoView = function () {};
      w.confirm = () => true;
      w.prompt = () => 'Work';
      const ctx = new Proxy({}, { get: (t, k) => (k in t ? t[k] : (ops++, /^create/.test(String(k)) ? () => ({ addColorStop() {} }) : () => {})), set: (t, k, v) => { t[k] = v; return true; } });
      w.HTMLCanvasElement.prototype.getContext = () => ctx;
      w.__copied = [];
      Object.defineProperty(w.navigator, 'clipboard', { value: { writeText: (t) => { w.__copied.push(t); return Promise.resolve(); } } });
      w.addEventListener('error', (e) => errors.push('window error: ' + e.message));
    } });
  const w = dom.window;
  const d = w.document;
  await new Promise((r) => w.addEventListener('load', r));
  await sleep(150);
  const $ = (id) => d.getElementById(id);
  const out = {};

  const liveTexts = [];
  new w.MutationObserver(() => { const t = $('live-text').textContent; if (liveTexts[liveTexts.length - 1] !== t) liveTexts.push(t); }).observe($('live-text'), { childList: true, characterData: true, subtree: true });

  out.home = { empty: $('log').classList.contains('empty'), sub: $('sub').textContent.length > 0, h1: d.querySelector('#home h1').textContent,
    live: $('live-text').textContent, model: $('model-label').textContent, chats: d.querySelectorAll('.chat-item').length, profile: $('profile-name').textContent,
    cores: w.document.querySelectorAll('canvas').length };

  $('input').focus();
  out.openOnFocus = $('composer').classList.contains('open');

  $('input').value = 'search test';
  $('input').dispatchEvent(new w.Event('input', { bubbles: true }));
  $('composer').requestSubmit();
  await sleep(500);
  const faze = d.querySelector('.turn.faze');
  out.turn = { user: d.querySelectorAll('.turn.user').length, faze: d.querySelectorAll('.turn.faze').length, text: faze.querySelector('.body').textContent,
    code: !!faze.querySelector('.code .copy'), ol: !!faze.querySelector('ol li'), steps: !!faze.querySelector('details.steps'), via: [...faze.querySelectorAll('.via')].map((e) => e.textContent),
    pending: faze.classList.contains('pending'), emptyAfter: $('log').classList.contains('empty'), liveNow: $('livebar').dataset.state, texts: liveTexts.slice(), model: $('model-label').textContent };
  out.storedChats = JSON.parse(w.localStorage.getItem('faze_chats')).map((c) => ({ title: c.title, n: c.messages.length }));
  out.sideTitle = d.querySelector('.chat-item.on .chat-open').textContent;

  d.querySelector('.code .copy').click();
  await sleep(20);
  out.copied = w.__copied.slice();

  $('new-chat').click();
  await sleep(150);
  out.afterNew = { empty: $('log').classList.contains('empty'), turns: d.querySelectorAll('#thread .turn').length, chats: d.querySelectorAll('.chat-item').length,
    reset: calls.some((c) => c.path === '/api/command' && c.body.line === '/reset') };

  const older = [...d.querySelectorAll('.chat-item')].find((el) => !el.classList.contains('on'));
  older.querySelector('.chat-open').click();
  await sleep(150);
  const restore = calls.filter((c) => c.path === '/api/restore').pop();
  out.reopen = { turns: d.querySelectorAll('#thread .turn').length, restore: restore && restore.body.messages.map((m) => m.role), empty: $('log').classList.contains('empty'), chats: d.querySelectorAll('.chat-item').length };

  $('chat-search').value = 'zzz-none';
  $('chat-search').dispatchEvent(new w.Event('input', { bubbles: true }));
  out.searchNone = d.querySelector('#chat-groups .empty-note') && d.querySelector('#chat-groups .empty-note').textContent;
  $('chat-search').value = 'search';
  $('chat-search').dispatchEvent(new w.Event('input', { bubbles: true }));
  out.searchHit = d.querySelectorAll('.chat-item').length;
  $('chat-search').value = '';
  $('chat-search').dispatchEvent(new w.Event('input', { bubbles: true }));

  d.querySelector('.chat-item.on button[title="Move to project"]').click();
  await sleep(20);
  out.project = [...d.querySelectorAll('.grp h4')].map((h) => h.textContent);

  $('model-chip').click();
  out.modelSheet = { open: !$('model-sheet').hidden, rows: d.querySelectorAll('.model-row').length };
  $('model-sheet').hidden = true;

  $('input').value = '/lo';
  $('input').dispatchEvent(new w.Event('input', { bubbles: true }));
  out.slash = [...d.querySelectorAll('#cmdmenu .cmd-name')].map((e) => e.textContent);
  $('input').value = '/log';
  $('input').dispatchEvent(new w.Event('input', { bubbles: true }));
  $('composer').requestSubmit();
  await sleep(150);
  out.logPanel = { open: !$('log-sheet').hidden, rows: d.querySelectorAll('.log-row').length, head: d.querySelector('.log-row b') && d.querySelector('.log-row b').textContent,
    noChatCall: !calls.some((c) => c.path === '/api/command' && c.body && c.body.line === '/log') };
  d.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  out.escClosesLog = $('log-sheet').hidden;

  $('tools-btn').click();
  out.toolsOpen = !$('tools-menu').hidden;
  d.querySelector('#tools-menu [data-insert="ask gemini "]').click();
  out.toolInsert = { value: $('input').value, closed: $('tools-menu').hidden };

  $('collapse').click();
  out.collapsed = { cls: d.body.classList.contains('side-closed'), stored: w.localStorage.getItem('opt_sidebar') };
  $('menu-btn').click();
  out.reopened = !d.body.classList.contains('side-closed');

  // the core itself
  const K = w.FazeCoreKit;
  const core = new K.FazeCore($('core-hero'));
  const before = ops;
  core.setModel('qwen');
  for (let i = 0; i < 160; i++) core.step(0.016);
  let diff = 0; const target = K.radii('qwen');
  for (let i = 0; i < K.N; i++) diff = Math.max(diff, Math.abs(core.cur[i] - target[i]));
  core.setState('thinking'); core.draw(); core.setState('tool', { fragments: 6 }); core.draw();
  core.burst(['qwen', 'gemini', 'lfm']); core.setLevel(0.8); core.draw();
  const minis = core.minis.length;
  for (let i = 0; i < 160; i++) core.step(0.016);
  core.setModel(null);
  out.core = { settled: diff < 1e-6, minisSpawned: minis, minisGone: core.minis.length === 0, drew: ops > before, state: core.state };
  K.setReduced(true); K.setReduced(false);

  out.errors = errors;
  console.log(JSON.stringify(out));
  dom.window.close();
  process.exit(0);
})().catch((e) => { console.log(JSON.stringify({ crash: String(e && e.stack || e), errors })); process.exit(1); });
