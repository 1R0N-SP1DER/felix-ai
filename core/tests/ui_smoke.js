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
  show_tag: true, wake: { enabled: true, state: 'listening', phrase: 'hey faze', detail: '', engine: 'auto' }, tts: { engine: 'system' }, user_name: 'Felix', forced_turns: 0, turns: 0, think: 'auto',
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

let evCtl = null;
const pushEvent = (event, data) => evCtl && evCtl.enqueue(new TextEncoder().encode('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'));
const AGENT = { mode: 'ask', high_tools: false, modes: { off: 'no tools', ask: 'asks first', yolo: 'auto' }, pending: [],
  tools: [{ name: 'miles_to_km', description: 'Convert miles to km.', how_it_works: 'Multiplies by 1.609.', level: 'normal', uses: 2, created: 1 }],
  tasks: [{ id: 'ab12', prompt: 'convert 5 miles to km', tools: ['miles_to_km'], uses: 1, runs: 2, ts: 1 }] };
const INFO = { id: 'ap1', kind: 'tool', name: 'miles_to_km', description: 'Convert miles to km.', how_it_works: 'Multiplies by 1.609.', level: 'high',
  reasons: ['imports subprocess'], params: { miles: 'number' }, code: 'def run(args):\n    return "1"', replaces: false };

const REPLY = 'Here is **code**:\n```js\nconst a = 1;\n```\n1. first\n2. second';

async function fakeFetch(url, opts) {
  opts = opts || {};
  const p = String(url).split('?')[0];
  const body = opts.body ? JSON.parse(opts.body) : null;
  calls.push({ path: p, body });
  const json = (obj) => ({ ok: true, status: 200, json: async () => obj });
  if (p === '/api/events') { return { ok: true, status: 200, body: new ReadableStream({ start(c) { evCtl = c; } }) }; }
  if (p === '/api/agent') return json(AGENT);
  if (p === '/api/approve') return json({ ok: true });
  if (p === '/api/status') return json(status);
  if (p === '/api/commands') return json({ commands: [
    { name: 'model', description: 'Choose model', usage: '/model [auto]', args: [{ value: 'auto', description: 'x' }] },
    { name: 'log', description: 'Show recent activity', usage: '/log [count]', args: [] }] });
  if (p === '/api/settings') return json({ value: body && body.value, restart: false, settings: [] });
  if (p === '/api/log') return json({ lines: [{ ts: 1700000000, text: '[qwen -> get_battery_status] {} => 80% (12ms)' }] });
  if (p === '/api/command') {
    const m = /^\/model (\w+)(?: (\d+))?$/.exec(body.line || '');
    if (m) { status.mode = m[1]; status.forced_turns = Number(m[2] || 0); }
    return json({ text: 'ok' });
  }
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
  out.modelSheet = { open: !$('model-sheet').hidden, rows: d.querySelectorAll('.model-row').length, items: d.querySelectorAll('.model-item').length,
    controls: d.querySelectorAll('.stay-ctl').length, slider: !!d.getElementById('stay-range') };
  const rowFor = (name) => [...d.querySelectorAll('.model-item')].find((i) => i.querySelector('.name').firstChild.textContent === name);
  const ctlOf = (name) => rowFor(name).querySelector('.stay-ctl');
  out.picker = { autoHasControl: !!rowFor('Auto').querySelector('.stay-ctl'), arrow: ctlOf('Qwen').querySelector('.stay-trigger').textContent, activeBefore: ctlOf('Qwen').classList.contains('active') };
  rowFor('Qwen').querySelector('.model-row').click();
  await sleep(120);
  out.picker.chose = calls.filter((c) => c.path === '/api/command' && /^\/model qwen/.test(c.body.line)).map((c) => c.body.line);
  out.picker.tick = ctlOf('Qwen').querySelector('.stay-trigger').textContent;
  out.picker.activeAfter = ctlOf('Qwen').classList.contains('active');
  out.picker.rowOn = rowFor('Qwen').querySelector('.model-row').classList.contains('on');
  ctlOf('Qwen').querySelector('.stay-trigger').click();
  out.picker.opened = ctlOf('Qwen').classList.contains('open');
  out.picker.options = [...ctlOf('Qwen').querySelectorAll('option')].map((o) => o.textContent);
  const pick = ctlOf('Qwen').querySelector('select');
  pick.value = '5';
  pick.dispatchEvent(new w.Event('change'));
  await sleep(120);
  out.picker.counted = calls.filter((c) => c.path === '/api/command' && /^\/model qwen 5/.test(c.body.line)).length;
  out.picker.remaining = ctlOf('Qwen').querySelector('select').value;
  out.picker.closed = !ctlOf('Qwen').classList.contains('open');
  rowFor('Auto').querySelector('.model-row').click();
  await sleep(120);
  out.picker.backToAuto = status.mode;
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

  // settings page
  $('open-settings').click();
  await sleep(30);
  const view = $('settings-view');
  const panelOn = () => [...view.querySelectorAll('.panel.on')].map((p) => p.dataset.panel);
  out.settings = { open: !view.hidden, bodyClass: d.body.classList.contains('view-settings'), composerHidden: w.getComputedStyle($('composer')).display === 'none',
    hash: w.location.hash, panel: panelOn(), tabs: [...$('settings-tabs').children].map((b) => b.textContent) };
  d.querySelector('#settings-tabs [data-tab="voice"]').click();
  out.settings.voicePanel = panelOn();
  out.settings.engine = $('wake-engine').value;
  $('wake-engine').value = 'gemini';
  $('wake-engine').dispatchEvent(new w.Event('change'));
  await sleep(40);
  out.settings.engineSaved = calls.filter((c) => c.path === '/api/settings' && c.body && c.body.key === 'wake.engine').map((c) => c.body.value);
  out.settings.wake = { checked: $('opt-wake').checked, phrase: $('wake-phrase').value, status: $('wake-status').textContent };
  $('opt-wake').checked = false;
  $('opt-wake').dispatchEvent(new w.Event('change'));
  await sleep(30);
  const wakeCall = calls.filter((c) => c.path === '/api/settings' && c.body && c.body.key === 'wake.enabled').pop();
  out.settings.wakeSaved = wakeCall && wakeCall.body.value;
  d.querySelector('#settings-tabs [data-tab="agent"]').click();
  await sleep(60);
  out.agent = { panel: panelOn(), tools: d.querySelectorAll('#agent-tools .agent-item').length, tasks: d.querySelectorAll('#agent-tasks .agent-item').length,
    mode: [...$('agent-mode-seg').children].filter((b) => b.classList.contains('on')).map((b) => b.dataset.v), help: $('agent-mode-help').textContent,
    toolTitle: d.querySelector('#agent-tools b').textContent, elevated: $('opt-elevated').checked };
  d.querySelector('#agent-mode-seg [data-v="yolo"]').click();
  await sleep(60);
  out.agent.yoloSaved = calls.some((c) => c.path === '/api/settings' && c.body && c.body.key === 'agent.mode' && c.body.value === 'yolo');
  $('opt-elevated').checked = true;
  $('opt-elevated').dispatchEvent(new w.Event('change'));
  await sleep(60);
  out.agent.elevatedSaved = calls.some((c) => c.path === '/api/settings' && c.body && c.body.key === 'agent.high_tools' && c.body.value === 'true');
  $('clear-tasks').click();
  await sleep(60);
  $('clear-tools').click();
  await sleep(60);
  d.querySelector('#agent-tools .agent-item button').click();
  await sleep(60);
  d.querySelector('#agent-tasks .agent-item button').click();
  await sleep(60);
  const lines = calls.filter((c) => c.path === '/api/command').map((c) => c.body.line);
  out.agent.commands = ['/tasks clear', '/tools clear', '/tools remove miles_to_km', '/tasks remove ab12'].map((l) => lines.includes(l));
  pushEvent('approval', INFO);
  await sleep(80);
  out.approval = { open: !$('approval-sheet').hidden, name: $('ap-name').textContent, level: $('ap-level').textContent, warn: $('ap-level').classList.contains('warn-pill'),
    desc: $('ap-desc').textContent, how: $('ap-how').textContent, reasons: $('ap-reasons').textContent, code: $('ap-code').textContent.includes('def run') };
  $('ap-allow').click();
  await sleep(60);
  const approves = () => calls.filter((c) => c.path === '/api/approve').map((c) => c.body);
  out.approval.allowed = approves().slice(-1)[0];
  out.approval.closed = $('approval-sheet').hidden;
  pushEvent('approval', Object.assign({}, INFO, { id: 'ap2' }));
  await sleep(60);
  $('ap-deny').click();
  await sleep(60);
  out.approval.denied = approves().slice(-1)[0];
  pushEvent('approval', Object.assign({}, INFO, { id: 'ap3' }));
  await sleep(60);
  $('ap-yolo').click();
  await sleep(60);
  out.approval.yolo = { approve: approves().slice(-1)[0], mode: calls.filter((c) => c.path === '/api/settings' && c.body && c.body.value === 'yolo').length >= 2 };
  pushEvent('approval', Object.assign({}, INFO, { id: 'ap4' }));
  await sleep(60);
  pushEvent('approval_done', { id: 'ap4' });
  await sleep(60);
  out.approval.autoClosed = $('approval-sheet').hidden;
  d.querySelector('#settings-tabs [data-tab="advanced"]').click();
  out.settings.advancedPanel = panelOn();
  $('close-settings').click();
  await sleep(80);
  out.settings.closed = view.hidden && !d.body.classList.contains('view-settings');
  out.settings.hashAfter = w.location.hash;

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
