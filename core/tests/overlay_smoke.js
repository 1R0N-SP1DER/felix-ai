// Drives the real web/overlay.html + overlay.js in jsdom against a fake FAZE server.
// Needs `npm install jsdom` somewhere above this folder; the Python test skips itself otherwise.
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const WEB = path.join(__dirname, '..', 'web');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const errors = [];
const calls = [];
let evCtl = null;
let snap = { state: 'idle', transcript: '', reply: '', error: '', note: '', seq: 0, active: false };
const push = (patch) => {
  snap = Object.assign({}, snap, patch, { seq: snap.seq + 1 });
  snap.active = ['listening', 'transcribing', 'thinking', 'speaking'].includes(snap.state);
  evCtl.enqueue(new TextEncoder().encode('event: voice\ndata: ' + JSON.stringify(snap) + '\n\n'));
};

async function fakeFetch(url, opts) {
  opts = opts || {};
  const p = String(url).split('?')[0];
  const body = opts.body ? JSON.parse(opts.body) : null;
  calls.push({ path: p, method: opts.method || 'GET', auth: (opts.headers || {}).Authorization, body });
  const json = (obj) => ({ ok: true, status: 200, json: async () => obj });
  if ((opts.headers || {}).Authorization !== 'Bearer tok') return { ok: false, status: 401, json: async () => ({ error: 'no' }) };
  if (p === '/api/events') return { ok: true, status: 200, body: new ReadableStream({ start(c) { evCtl = c; } }) };
  if (p === '/api/voice/state') return json(snap);
  if (p === '/api/voice/start') { snap = Object.assign({}, snap, { state: 'listening', active: true, transcript: '', reply: '', note: '' }); return json(Object.assign({ started: true }, snap)); }
  if (p === '/api/voice/say') {
    if (body.text === 'FAIL') return { ok: false, status: 400, json: async () => ({ error: 'nothing to say' }) };
    snap = Object.assign({}, snap, { state: 'thinking', transcript: body.text, reply: '', active: true });
    return json(Object.assign({ started: true }, snap));
  }
  if (p === '/api/voice/stop') { snap = Object.assign({}, snap, { state: 'idle', active: false, note: 'Stopped' }); return json(snap); }
  if (p === '/api/open') return json({ ok: true });
  return { ok: false, status: 404, json: async () => ({}) };
}

function boot(hash) {
  const read = (f) => fs.readFileSync(path.join(WEB, f), 'utf8');
  const html = read('overlay.html').split('<script src="/overlay.js"></script>').join('<script>' + read('overlay.js') + '</script>');
  const vc = new VirtualConsole();
  vc.on('jsdomError', (e) => errors.push('jsdom: ' + (e.detail || e.message)));
  vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));
  return new JSDOM(html, { url: 'http://localhost:8787/overlay' + hash, runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(w) { w.fetch = fakeFetch; w.ReadableStream = ReadableStream; w.TextDecoder = TextDecoder; w.TextEncoder = TextEncoder; } });
}

(async () => {
  const out = {};
  let dom = boot('#t=tok&auto');
  let d = dom.window.document;
  const $ = (id) => d.getElementById(id);
  const view = () => ({ state: $('card').dataset.state, label: $('state').textContent, you: $('you').hidden ? '' : $('you').textContent,
    reply: $('reply').hidden ? '' : $('reply').textContent, note: $('note').hidden ? '' : $('note').textContent,
    again: !$('again').hidden, stop: !$('stop').hidden });
  await sleep(150);
  out.loaded = view();
  out.autoStarted = calls.filter((c) => c.path === '/api/voice/start').length;
  out.auth = calls.every((c) => c.auth === 'Bearer tok');
  out.hashAfter = dom.window.location.hash;
  out.token = dom.window.localStorage.getItem('faze_overlay_token');

  push({ state: 'transcribing' }); await sleep(30); out.transcribing = view();
  push({ state: 'thinking', transcript: 'what is the time' }); await sleep(30); out.thinking = view();
  push({ reply: 'It is' }); await sleep(30); out.streaming = view();
  push({ state: 'speaking', reply: 'It is half past four.' }); await sleep(30); out.speaking = view();
  push({ state: 'done' }); await sleep(30); out.done = view();
  push({ timings: { listen_ms: 1200, stt_ms: 800, first_token_ms: 1500, first_speech_ms: 1700, total_ms: 4000 } }); await sleep(30); out.doneTimings = view();
  push({ state: 'error', error: 'no microphone' }); await sleep(30); out.error = view();

  $('again').click(); await sleep(60);
  out.again = { starts: calls.filter((c) => c.path === '/api/voice/start').length, view: view() };
  $('stop').click(); await sleep(60);
  out.stop = { call: calls.filter((c) => c.path === '/api/voice/stop').map((c) => c.body), view: view() };
  $('open').click(); await sleep(40);
  out.open = calls.filter((c) => c.path === '/api/open').map((c) => c.body);
  $('close').click(); await sleep(40);
  out.close = calls.filter((c) => c.path === '/api/voice/stop').map((c) => c.body);
  const says = () => calls.filter((c) => c.path === '/api/voice/say').map((c) => c.body);
  const typeIn = async (text) => { $('say').value = text; $('typed').dispatchEvent(new dom.window.Event('submit', { cancelable: true, bubbles: true })); await sleep(60); };
  await typeIn('   ');
  out.typedBlank = says().length;
  await typeIn('what is the time');
  out.typedClassic = { bodies: says(), inputAfter: $('say').value, state: $('card').dataset.state, you: $('you').textContent, sendDisabled: $('send').disabled };
  await typeIn('FAIL');
  out.typedFail = { state: $('card').dataset.state, note: $('note').textContent, keptText: $('say').value, sendDisabled: $('send').disabled };
  out.classicBadgeHidden = $('engine').hidden;
  push({ state: 'idle', engine: 'live' }); await sleep(30);
  out.liveBadgeHidden = $('engine').hidden;
  await typeIn('hello live');
  out.typedLive = says().slice(-1)[0];
  $('again').click(); await sleep(60);
  out.liveAgainBody = calls.filter((c) => c.path === '/api/voice/start').slice(-1)[0].body;
  dom.window.close();

  // an exchange already running is shown, not restarted; no token is reported, not crashed on
  snap = { state: 'thinking', transcript: 'hi', reply: '', error: '', note: '', seq: 9, active: true };
  const before = calls.filter((c) => c.path === '/api/voice/start').length;
  dom = boot('#t=tok&auto'); d = dom.window.document;
  await sleep(150);
  out.running = { view: { state: d.getElementById('card').dataset.state, you: d.getElementById('you').textContent },
    newStarts: calls.filter((c) => c.path === '/api/voice/start').length - before };
  dom.window.close();
  snap = { state: 'idle', transcript: '', reply: '', error: '', note: '', seq: 20, active: false };
  const beforeLive = calls.filter((c) => c.path === '/api/voice/start').length;
  dom = boot('#t=tok&auto&live'); d = dom.window.document;
  await sleep(150);
  out.liveAuto = { bodies: calls.filter((c) => c.path === '/api/voice/start').slice(beforeLive).map((c) => c.body), badgeHidden: d.getElementById('engine').hidden, hash: dom.window.location.hash };
  dom.window.close();
  dom = boot(''); d = dom.window.document;
  await sleep(80);
  out.noToken = { state: d.getElementById('card').dataset.state, note: d.getElementById('note').textContent };
  dom.window.close();

  out.errors = errors;
  console.log(JSON.stringify(out));
  process.exit(0);
})().catch((e) => { console.log(JSON.stringify({ crash: String(e && e.stack || e), errors })); process.exit(1); });
