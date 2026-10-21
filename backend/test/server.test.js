import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { loadConfig } from '../src/config.js';
import { createServer } from '../src/server.js';
import { _clearWeatherCache } from '../src/weather.js';
import { redact, makeLogger } from '../src/log.js';
import { buildSystemPrompt } from '../src/persona.js';

const TOKEN = 'test-token-0123456789abcdef';
const API_KEY = 'AIzaSyTESTKEY1234567890abcdefghijk';

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}
const close = (s) => new Promise((r) => { s.closeAllConnections?.(); s.close(() => r()); });

/** Mock Gemini + Open-Meteo upstream. `behavior` is mutated per test. */
function makeUpstream(behavior) {
  const seen = { requests: [], aborted: false };
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      seen.requests.push({ url: req.url, headers: req.headers, body: raw ? JSON.parse(raw) : null });
      const u = new URL(req.url, 'http://x');

      if (u.pathname.startsWith('/v1beta/models/')) {
        if (behavior.status && behavior.status !== 200) {
          res.writeHead(behavior.status, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ error: { message: `secret upstream detail ${API_KEY}` } }));
        }
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        const parts = behavior.parts ?? ['Systems ', 'ready.'];
        let i = 0;
        const tick = () => {
          if (res.destroyed) return;
          if (i < parts.length) {
            res.write(`data: ${JSON.stringify({ candidates: [{ content: { parts: [{ text: parts[i++] }] } }] })}\n\n`);
            setTimeout(tick, behavior.delayMs ?? 5);
          } else if (behavior.hang) {
            // keep the stream open until the client goes away
          } else {
            res.write(`data: ${JSON.stringify({ usageMetadata: { promptTokenCount: 7, candidatesTokenCount: 3, totalTokenCount: 10 } })}\n\n`);
            res.end();
          }
        };
        res.on('close', () => { seen.aborted = true; });
        return tick();
      }
      if (u.pathname === '/v1/search') {
        res.writeHead(200, { 'content-type': 'application/json' });
        const name = u.searchParams.get('name');
        return res.end(JSON.stringify(name === 'Atlantis' ? {} : { results: [{ name, country: 'India', latitude: 19.07, longitude: 72.87 }] }));
      }
      if (u.pathname === '/v1/forecast') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ current: { time: '2026-10-20T12:00', temperature_2m: 31.4, apparent_temperature: 34.1, relative_humidity_2m: 62, weather_code: 2, wind_speed_10m: 11.2 } }));
      }
      res.writeHead(404); res.end();
    });
  });
  return { server, seen };
}

async function setup(envOverrides = {}, behavior = {}) {
  const upstream = makeUpstream(behavior);
  const upPort = await listen(upstream.server);
  const base = `http://127.0.0.1:${upPort}`;
  const cfg = loadConfig({
    APP_TOKENS: TOKEN,
    GEMINI_API_KEY: API_KEY,
    GEMINI_MODELS: 'model-a,model-b',
    GEMINI_BASE_URL: base,
    OPEN_METEO_GEOCODING_URL: base,
    OPEN_METEO_FORECAST_URL: base,
    CREATOR_NAME: 'Fardeen',
    ...envOverrides,
  });
  const logs = [];
  const app = createServer(cfg, { log: makeLogger((l) => logs.push(l)) });
  const port = await listen(app);
  return {
    url: `http://127.0.0.1:${port}`,
    seen: upstream.seen,
    behavior,
    logs,
    async stop() { await close(app); await close(upstream.server); },
  };
}

const auth = { authorization: `Bearer ${TOKEN}` };
const post = (url, body, headers = {}) =>
  fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) });

async function readSse(res) {
  const text = await res.text();
  return text.split('\n\n').filter((b) => b.startsWith('data:')).map((b) => JSON.parse(b.slice(5)));
}

test('config refuses to start without auth tokens', () => {
  assert.throws(() => loadConfig({ GEMINI_API_KEY: 'x' }), /APP_TOKENS/);
  assert.throws(() => loadConfig({ APP_TOKENS: 'short' }), /16 characters/);
  assert.doesNotThrow(() => loadConfig({ ALLOW_NO_AUTH: 'true' }));
});

test('health is public and reports real configuration state', async () => {
  const s = await setup();
  try {
    const r = await fetch(`${s.url}/health`);
    assert.equal(r.status, 200);
    const j = await r.json();
    assert.equal(j.ok, true);
    assert.equal(j.configured, true);
    assert.equal(j.auth, true);
    assert.ok(!JSON.stringify(j).includes(API_KEY));
  } finally { await s.stop(); }
});

test('health reports configured=false when no provider key', async () => {
  const s = await setup({ GEMINI_API_KEY: '' });
  try {
    assert.equal((await (await fetch(`${s.url}/health`)).json()).configured, false);
    const r = await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'hi' }] }, auth);
    assert.equal(r.status, 503);
    assert.equal((await r.json()).error, 'not_configured');
  } finally { await s.stop(); }
});

test('auth: missing/wrong token rejected, valid token accepted', async () => {
  const s = await setup();
  try {
    assert.equal((await fetch(`${s.url}/v1/models`)).status, 401);
    assert.equal((await fetch(`${s.url}/v1/models`, { headers: { authorization: 'Bearer wrong-token-wrong-token' } })).status, 401);
    const ok = await fetch(`${s.url}/v1/models`, { headers: auth });
    assert.equal(ok.status, 200);
    assert.deepEqual((await ok.json()).models, ['model-a', 'model-b']);
  } finally { await s.stop(); }
});

test('chat streams deltas, usage, injects persona, and never leaks the API key', async () => {
  const s = await setup();
  try {
    const r = await post(`${s.url}/v1/chat/stream`, {
      messages: [{ role: 'user', text: 'namaste' }], style: 'short', language: 'hinglish', mode: 'study',
    }, auth);
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /text\/event-stream/);
    const events = await readSse(r);
    assert.deepEqual(events.filter((e) => e.delta).map((e) => e.delta).join(''), 'Systems ready.');
    const done = events.find((e) => e.done);
    assert.equal(done.usage.totalTokens, 10);
    assert.equal(done.model, 'model-a');

    const up = s.seen.requests.find((q) => q.url.includes(':streamGenerateContent'));
    assert.equal(up.headers['x-goog-api-key'], API_KEY); // key only goes server -> upstream
    assert.ok(!up.url.includes(API_KEY), 'key must not be in the URL');
    const sys = up.body.systemInstruction.parts[0].text;
    assert.match(sys, /Mujhe Fardeen ne banaya hai/);
    assert.match(sys, /Study mode/);
    assert.equal(up.body.contents[0].role, 'user');
    assert.ok(!JSON.stringify(events).includes(API_KEY));
    assert.ok(!s.logs.join('\n').includes(API_KEY));
    assert.ok(!s.logs.join('\n').includes('namaste'), 'prompts must not be logged');
  } finally { await s.stop(); }
});

test('chat honours selected model and rejects unknown models', async () => {
  const s = await setup();
  try {
    const ok = await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'x' }], model: 'model-b' }, auth);
    assert.equal(ok.status, 200);
    await ok.text();
    assert.match(s.seen.requests.at(-1).url, /models\/model-b:/);
    const bad = await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'x' }], model: 'evil-model' }, auth);
    assert.equal(bad.status, 400);
  } finally { await s.stop(); }
});

test('validation: bad JSON, wrong content-type, bad shape, oversize', async () => {
  const s = await setup({ MAX_BODY_BYTES: '2048', MAX_MESSAGE_CHARS: '50' });
  try {
    assert.equal((await post(`${s.url}/v1/chat/stream`, '{nope', auth)).status, 400);
    const ct = await fetch(`${s.url}/v1/chat/stream`, { method: 'POST', headers: { ...auth, 'content-type': 'text/plain' }, body: 'hi' });
    assert.equal(ct.status, 415);
    assert.equal((await post(`${s.url}/v1/chat/stream`, { messages: [] }, auth)).status, 400);
    assert.equal((await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'system', text: 'x' }] }, auth)).status, 400);
    assert.equal((await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'assistant', text: 'x' }] }, auth)).status, 400);
    assert.equal((await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'y'.repeat(51) }] }, auth)).status, 400);
    assert.equal((await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'y'.repeat(5000) }] }, auth)).status, 413);
    assert.equal(s.seen.requests.length, 0, 'invalid requests must never reach the provider');
  } finally { await s.stop(); }
});

test('rate limiting returns 429 with Retry-After', async () => {
  const s = await setup({ RATE_LIMIT_PER_MIN: '2' });
  try {
    const body = { messages: [{ role: 'user', text: 'x' }] };
    for (let i = 0; i < 2; i++) await (await post(`${s.url}/v1/chat/stream`, body, auth)).text();
    const third = await post(`${s.url}/v1/chat/stream`, body, auth);
    assert.equal(third.status, 429);
    assert.ok(Number(third.headers.get('retry-after')) >= 1);
  } finally { await s.stop(); }
});

test('upstream errors are normalised and upstream details are not leaked', async () => {
  const s = await setup({}, { status: 500 });
  try {
    const r = await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'x' }] }, auth);
    assert.equal(r.status, 502);
    const text = await r.text();
    assert.deepEqual(JSON.parse(text), { error: 'upstream_error' });
    assert.ok(!text.includes('secret upstream detail'));
    s.behavior.status = 429;
    const r2 = await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'x' }] }, auth);
    assert.equal(r2.status, 429);
    s.behavior.status = 403;
    const r3 = await post(`${s.url}/v1/chat/stream`, { messages: [{ role: 'user', text: 'x' }] }, auth);
    assert.equal((await r3.json()).error, 'upstream_auth');
  } finally { await s.stop(); }
});

test('client disconnect aborts the upstream request (no wasted tokens)', async () => {
  const s = await setup({}, { parts: ['a', 'b', 'c'], delayMs: 20, hang: true });
  try {
    const ac = new AbortController();
    const r = await fetch(`${s.url}/v1/chat/stream`, {
      method: 'POST', headers: { 'content-type': 'application/json', ...auth },
      body: JSON.stringify({ messages: [{ role: 'user', text: 'x' }] }), signal: ac.signal,
    });
    const reader = r.body.getReader();
    await reader.read();
    ac.abort();
    for (let i = 0; i < 50 && !s.seen.aborted; i++) await new Promise((res) => setTimeout(res, 20));
    assert.equal(s.seen.aborted, true);
  } finally { await s.stop(); }
});

test('weather returns real upstream data, validates city, handles not-found', async () => {
  _clearWeatherCache();
  const s = await setup();
  try {
    const r = await fetch(`${s.url}/v1/weather?city=Mumbai`, { headers: auth });
    assert.equal(r.status, 200);
    const w = await r.json();
    assert.equal(w.city, 'Mumbai');
    assert.equal(w.tempC, 31.4);
    assert.equal(w.description, 'Partly cloudy');
    assert.equal((await fetch(`${s.url}/v1/weather?city=${encodeURIComponent('<script>')}`, { headers: auth })).status, 400);
    assert.equal((await fetch(`${s.url}/v1/weather`, { headers: auth })).status, 400);
    assert.equal((await fetch(`${s.url}/v1/weather?city=Atlantis`, { headers: auth })).status, 404);
  } finally { await s.stop(); }
});

test('unknown routes and methods', async () => {
  const s = await setup();
  try {
    assert.equal((await fetch(`${s.url}/nope`, { headers: auth })).status, 404);
    assert.equal((await fetch(`${s.url}/v1/chat/stream`, { headers: auth })).status, 405);
  } finally { await s.stop(); }
});

test('log redaction removes keys and bearer tokens', () => {
  const out = redact(`failed key=${API_KEY} Authorization: Bearer ${TOKEN}`);
  assert.ok(!out.includes(API_KEY));
  assert.ok(!out.includes(TOKEN));
});

test('persona prompt carries identity rules and language/style switches', () => {
  const p = buildSystemPrompt({ creator: 'Fardeen', style: 'detailed', language: 'english', mode: 'chat' });
  assert.match(p, /Mere configured creator record ke mutabik mujhe Fardeen ne develop kiya hai/);
  assert.match(p, /clear English/);
  assert.match(p, /detailed explanations/);
});
