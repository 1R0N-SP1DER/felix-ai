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
  show_tag: true, wake: { enabled: true, state: 'listening', phrase: 'hey faze', detail: '', engine: 'auto' }, tts: { engine: 'system' }, user_name: 'Felix', forced_turns: 0, turns: 0, agent: { mode: 'ask', high_tools: false, tools: 1, tasks: 1, rev: 7 }, think: 'auto',
  stt: { server: false, pref: 'auto', reason: 'no whisper' } };

function sse(events) {
  const enc = new TextEncoder();
  let i = 0;
  const stream = new ReadableStream({
    async pull(controller) {
      if (i >= events.length) { controller.close(); return; }
      await sleep(15);
      const [event, data] = events[i++];
      if (event === 'reply' && WIN) { WIN.__atReply = { spoken: WIN.__spoken.length, clips: WIN.__clipCalls }; }
      controller.enqueue(enc.encode('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'));
    },
  });
  return { ok: true, status: 200, body: stream, json: async () => ({}) };
}

let evCtl = null;
const pushEvent = (event, data) => evCtl && evCtl.enqueue(new TextEncoder().encode('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'));
const AGENT = { mode: 'ask', control: 'off', high_tools: false, modes: { off: 'no tools', ask: 'asks first', yolo: 'auto' }, pending: [],
  rev: 7,
  tools: [{ name: 'miles_to_km', description: 'Convert miles to km.', how_it_works: 'Multiplies by 1.609.', level: 'normal', uses: 2, created: 1,
    params: { miles: { type: 'number', description: 'distance in miles' } } }],
  tasks: [{ id: 'ab12', prompt: 'convert 5 miles to km', tools: ['miles_to_km'], uses: 1, runs: 2, ts: 1 }] };
const INFO = { id: 'ap1', kind: 'tool', name: 'miles_to_km', description: 'Convert miles to km.', how_it_works: 'Multiplies by 1.609.', level: 'high',
  reasons: ['imports subprocess'], params: { miles: 'number' }, code: 'def run(args):\n    return "1"', replaces: false };

let WIN = null;
let speakInflight = 0, speakMax = 0;
const SPEAK_FULL = 'First sentence is right here. Second one follows it nicely. And a third trailing bit';
const tok = (text) => ['token', { kind: 'text', text }];

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
  if (p === '/api/tools/run') return json({ ok: true, text: '4.83' });
  if (p === '/api/tools/code') return json({ name: 'miles_to_km', code: 'def run(args):\n    return "x"' });
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
  if (p === '/api/speak') {
    WIN.__clipCalls += 1;
    WIN.__clipTexts.push(body.text);
    speakInflight += 1;
    speakMax = Math.max(speakMax, speakInflight);
    await sleep(25);
    speakInflight -= 1;
    return { ok: true, status: 200, blob: async () => ({ size: 4 }), json: async () => ({}) };
  }
  if (p === '/api/chat' && /^speak test/.test(body.message)) {
    return sse([tok('First sentence is right here. '), tok('Second one follows it nicely. '), tok('And a third trailing bit'), ['reply', { text: SPEAK_FULL }]]);
  }
  if (p === '/api/chat' && /^reset test/.test(body.message)) {
    return sse([tok('Let me think about that for you now. '), ['token', { kind: 'reset', text: '' }], tok('The real answer is short and clear.'), ['reply', { text: 'The real answer is short and clear.' }]]);
  }
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
      w.__spoken = [];
      let blobs = 0;
      w.URL.createObjectURL = () => 'blob:clip' + (++blobs);
      w.URL.revokeObjectURL = () => {};
      w.Audio = class { constructor(u) { this.u = u; w.__played.push(u); } play() { setTimeout(() => this.onended && this.onended(), 15); return Promise.resolve(); } pause() {} };
      w.FontFace = class { constructor(family, buf) { this.family = family; this.size = buf.byteLength; } load() { return Promise.resolve(this); } };
      w.document.fonts = { add: (f) => w.__fonts.push(f.family + ':' + f.size) };
      w.SpeechSynthesisUtterance = class { constructor(text) { this.text = text; } };
      w.speechSynthesis = { speak: (u) => w.__spoken.push(u.text), cancel() {}, getVoices: () => [], speaking: false, onvoiceschanged: null, addEventListener() {} };
      Object.defineProperty(w.navigator, 'clipboard', { value: { writeText: (t) => { w.__copied.push(t); return Promise.resolve(); } } });
      w.addEventListener('error', (e) => errors.push('window error: ' + e.message));
    } });
  const w = dom.window;
  WIN = w;
  w.__clipCalls = 0;
  w.__clipTexts = [];
  w.__played = [];
  w.__fonts = [];
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

  // tools and actions in the sidebar, the Tools menu and the run sheet
  await sleep(60);
  const toolCalls = () => calls.filter((c) => c.path === '/api/tools/run').map((c) => c.body);
  out.side = { toolsCount: $('tools-count').textContent, actionsCount: $('actions-count').textContent, closedByDefault: $('tools-list').hidden && $('actions-body').hidden,
    quick: [...d.querySelectorAll('.quick-actions button')].map((b) => b.textContent) };
  d.querySelector('#sec-tools .sec-head').click();
  d.querySelector('#sec-actions .sec-head').click();
  out.side.opened = !$('tools-list').hidden && !$('actions-body').hidden;
  out.side.remembered = w.localStorage.getItem('opt_sec-tools');
  out.side.toolRow = d.querySelector('#tools-list .side-main').textContent;
  out.side.taskRow = d.querySelector('#actions-list .side-main').textContent;
  d.querySelector('#tools-list .side-main').click();
  out.run = { open: !$('run-sheet').hidden, title: $('run-title').textContent, desc: $('run-desc').textContent, level: $('run-level').textContent,
    fields: [...d.querySelectorAll('#run-fields input')].map((i) => i.type + ':' + i.name) };
  $('run-go').click();
  await sleep(30);
  out.run.emptyBlocked = toolCalls().length;
  d.querySelector('#run-fields input').value = '3';
  $('run-go').click();
  await sleep(60);
  out.run.sent = toolCalls().slice(-1)[0];
  out.run.result = $('run-result').textContent;
  $('run-code-box').open = true;
  $('run-code-box').dispatchEvent(new w.Event('toggle'));
  await sleep(60);
  out.run.code = $('run-code').textContent;
  d.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  out.run.escCloses = $('run-sheet').hidden;
  $('tools-btn').click();
  out.menu = { custom: [...d.querySelectorAll('#tools-custom button')].map((b) => b.textContent), heading: d.querySelector('#tools-custom small').textContent };
  d.querySelector('#tools-custom button').click();
  out.menu.opensSheet = !$('run-sheet').hidden && $('run-name').textContent === 'miles_to_km';
  out.menu.menuClosed = $('tools-menu').hidden;
  $('run-close').click();
  d.querySelector('#actions-list .side-main').click();
  out.side.taskToInput = $('input').value;
  $('input').value = '';
  const chatsBefore = calls.filter((c) => c.path === '/api/chat').length;
  d.querySelector('#actions-list .side-acts button').click();
  await sleep(600);
  const chatCalls = calls.filter((c) => c.path === '/api/chat');
  out.side.rerun = chatCalls.length - chatsBefore === 1 && chatCalls.slice(-1)[0].body.message === 'convert 5 miles to km';
  d.querySelector('.quick-actions button').click();
  await sleep(40);
  out.side.quickSent = toolCalls().slice(-1)[0];
  d.querySelector('#actions-list .side-acts button:last-child').click();
  await sleep(40);
  d.querySelector('#tools-list .side-acts button:last-child').click();
  await sleep(40);
  const cmdLines = calls.filter((c) => c.path === '/api/command').map((c) => c.body.line);
  out.side.deleted = [cmdLines.lastIndexOf('/tasks remove ab12') >= 0, cmdLines.lastIndexOf('/tools remove miles_to_km') >= 0];
  const mine = d.querySelector('.turn.user');
  out.mine = { buttons: [...mine.querySelectorAll('.acts button')].map((b) => b.textContent), text: mine.querySelector('.text').textContent };
  mine.querySelector('.act-copy').click();
  mine.querySelector('.act-speak').click();
  await sleep(30);
  out.mine.copied = w.__copied.slice(-1)[0];
  out.mine.spoken = w.__spoken.slice(-1)[0];

  // streamed speech: device voice, then Gemini voice
  const say = async (text, wait) => { $('input').value = text; $('input').dispatchEvent(new w.Event('input', { bubbles: true })); $('composer').requestSubmit(); await sleep(wait); };
  $('opt-speak').checked = true;
  $('opt-speak').dispatchEvent(new w.Event('change'));
  status.tts.engine = 'system';
  await say('/help', 150);
  w.__spoken.length = 0;
  await say('speak test one', 700);
  out.voiceDevice = { spoken: w.__spoken.slice(), atReply: w.__atReply.spoken, clips: w.__clipCalls };
  status.tts.engine = 'gemini';
  await say('/help', 150);
  w.__spoken.length = 0;
  await say('speak test two', 1400);
  out.voiceGemini = { clipTexts: w.__clipTexts.slice(), played: w.__played.length, maxParallel: speakMax, clipsBeforeReply: w.__atReply.clips, deviceSpoke: w.__spoken.length };
  $('opt-speak').checked = false;
  $('opt-speak').dispatchEvent(new w.Event('change'));
  await say('reset test', 500);
  out.reset = { text: [...d.querySelectorAll('.turn.faze')].slice(-1)[0].querySelector('.body').textContent };

  // a Tasker/faze link: pairing token and the voice flag together
  w.location.hash = '#t=newtoken&voice';
  await sleep(300);
  out.voiceLink = { voiceMode: d.body.classList.contains('voice'), hashCleared: w.location.hash === '', pairedToken: w.localStorage.getItem('faze_token') };

  // font choices
  d.querySelector('#font-seg [data-v="serif"]').click();
  out.font = { serif: d.documentElement.dataset.font, stored: w.localStorage.getItem('opt_font'), platform: d.documentElement.dataset.platform, fileRowHidden: $('row-fontfile').hidden };
  d.querySelector('#font-seg [data-v="custom"]').click();
  out.font.fileRowShown = !$('row-fontfile').hidden;
  const fontFile = new w.File([new Uint8Array([0, 1, 0, 0, 9, 9])], 'MyFont.ttf');
  Object.defineProperty($('font-file'), 'files', { value: [fontFile], configurable: true });
  $('font-file').dispatchEvent(new w.Event('change'));
  await sleep(80);
  out.font.custom = { attr: d.documentElement.dataset.font, registered: w.__fonts.slice(), name: $('font-name').textContent, savedName: w.localStorage.getItem('opt_fontname'), hasData: !!w.localStorage.getItem('opt_fontdata') };
  d.querySelector('#font-seg [data-v="system"]').click();
  out.font.backToSystem = d.documentElement.dataset.font;

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
  d.querySelector('#control-seg [data-v="ask"]').click();
  await sleep(60);
  out.agent.controlSaved = calls.some((c) => c.path === '/api/settings' && c.body && c.body.key === 'device.control' && c.body.value === 'ask');
  out.agent.controlOn = [...$('control-seg').children].filter((b) => b.classList.contains('on')).map((b) => b.dataset.v);
  pushEvent('approval', { id: 'c1', kind: 'control', name: 'Control your phone', description: 'Faze wants to look at your screen.', how_it_works: 'uiautomator.', level: 'high', reasons: ['uses root'], params: {}, code: '', replaces: false });
  await sleep(60);
  out.control = { title: $('ap-title').textContent, codeHidden: $('ap-code').closest('details').hidden };
  $('ap-allow').click();
  await sleep(40);
  pushEvent('approval', INFO);
  await sleep(80);
  out.approval = { codeShown: !$('ap-code').closest('details').hidden, title: $('ap-title').textContent, open: !$('approval-sheet').hidden, name: $('ap-name').textContent, level: $('ap-level').textContent, warn: $('ap-level').classList.contains('warn-pill'),
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
