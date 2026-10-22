import http from 'node:http';
import crypto from 'node:crypto';
import { buildSystemPrompt } from './persona.js';
import { validateChatBody } from './validate.js';
import { RateLimiter } from './ratelimit.js';
import { streamChat, UpstreamError } from './gemini.js';
import { getWeather, WeatherError } from './weather.js';
import { makeLogger } from './log.js';

export const VERSION = '0.1.0';

const sha = (s) => crypto.createHash('sha256').update(s).digest();

function bearer(req) {
  const h = req.headers.authorization;
  if (typeof h !== 'string') return null;
  const m = /^Bearer\s+(.+)$/i.exec(h);
  return m ? m[1].trim() : null;
}

function tokenValid(cfg, presented) {
  if (!presented) return false;
  const p = sha(presented);
  let ok = false;
  for (const t of cfg.appTokens) ok = crypto.timingSafeEqual(p, sha(t)) || ok; // no early exit
  return ok;
}

function sendJson(res, status, obj, headers = {}) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...headers,
  });
  res.end(body);
}

async function readJson(req, maxBytes) {
  const ct = req.headers['content-type'] ?? '';
  if (!ct.toLowerCase().startsWith('application/json')) {
    throw Object.assign(new Error('unsupported_media_type'), { status: 415 });
  }
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > maxBytes) throw Object.assign(new Error('payload_too_large'), { status: 413 });
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > maxBytes) throw Object.assign(new Error('payload_too_large'), { status: 413 });
    chunks.push(c);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw Object.assign(new Error('invalid_json'), { status: 400 });
  }
}

export function createServer(cfg, deps = {}) {
  const fetchImpl = deps.fetchImpl ?? fetch;
  const log = deps.log ?? makeLogger();
  const limiter = new RateLimiter({ limit: cfg.rateLimitPerMin, now: deps.now });
  const authFailLimiter = new RateLimiter({ limit: 20, now: deps.now });

  async function handle(req, res) {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const path = url.pathname;
    const ip = req.socket.remoteAddress ?? 'unknown';

    if (path === '/health') {
      if (req.method !== 'GET') return sendJson(res, 405, { error: 'method_not_allowed' });
      return sendJson(res, 200, {
        ok: true,
        version: VERSION,
        provider: 'gemini',
        configured: Boolean(cfg.geminiApiKey) && cfg.models.length > 0,
        auth: cfg.authEnabled,
      });
    }

    // --- authentication ---
    let limitKey = `ip:${ip}`;
    if (cfg.authEnabled) {
      const presented = bearer(req);
      if (!tokenValid(cfg, presented)) {
        const f = authFailLimiter.check(ip);
        if (!f.allowed) return sendJson(res, 429, { error: 'rate_limited' }, { 'retry-after': String(f.retryAfterSec) });
        return sendJson(res, 401, { error: 'unauthorized' });
      }
      limitKey = `tok:${sha(presented).toString('hex').slice(0, 16)}`;
    }

    if (path === '/v1/models') {
      if (req.method !== 'GET') return sendJson(res, 405, { error: 'method_not_allowed' });
      return sendJson(res, 200, { models: cfg.models, default: cfg.models[0] ?? null });
    }

    if (path === '/v1/chat/stream' || path === '/v1/weather') {
      const rl = limiter.check(limitKey);
      if (!rl.allowed) return sendJson(res, 429, { error: 'rate_limited' }, { 'retry-after': String(rl.retryAfterSec) });
    }

    if (path === '/v1/chat/stream') {
      if (req.method !== 'POST') return sendJson(res, 405, { error: 'method_not_allowed' });
      if (!cfg.geminiApiKey || cfg.models.length === 0) return sendJson(res, 503, { error: 'not_configured' });

      let body;
      try {
        body = await readJson(req, cfg.maxBodyBytes);
      } catch (e) {
        return sendJson(res, e.status ?? 400, { error: e.message });
      }
      const v = validateChatBody(body, cfg);
      if (!v.ok) return sendJson(res, v.status ?? 400, { error: 'invalid_request', detail: v.error });

      const { messages, style, language, mode, model, rules } = v.value;
      const ac = new AbortController();
      const timer = setTimeout(() => ac.abort(), cfg.upstreamTimeoutMs);
      res.on('close', () => {
        clearTimeout(timer);
        ac.abort(); // client went away or finished: stop paying for upstream tokens
      });

      const system = buildSystemPrompt({ creator: cfg.creatorName, style, language, mode, rules });
      const it = streamChat({ cfg, model, system, messages, signal: ac.signal, fetchImpl })[Symbol.asyncIterator]();
      const t0 = Date.now();

      let first;
      try {
        first = await it.next();
      } catch (e) {
        const err = e instanceof UpstreamError ? e : new UpstreamError('upstream_error', 502);
        log('warn', 'chat upstream failed before stream', { code: err.code });
        return sendJson(res, err.status, { error: err.code });
      }

      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
        'x-accel-buffering': 'no',
        'x-content-type-options': 'nosniff',
      });
      const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);

      const emit = (ev) => {
        if (ev.delta) send({ delta: ev.delta });
        else if (ev.done) send({ done: true, usage: ev.usage, model });
      };

      try {
        let step = first;
        while (!step.done) {
          emit(step.value);
          step = await it.next();
        }
        log('info', 'chat ok', { model, ms: Date.now() - t0, turns: messages.length });
      } catch (e) {
        const err = e instanceof UpstreamError ? e : new UpstreamError('upstream_error', 502);
        log('warn', 'chat upstream failed mid-stream', { code: err.code });
        if (!res.writableEnded) send({ error: err.code, code: err.code });
      } finally {
        clearTimeout(timer);
        if (!res.writableEnded) res.end();
      }
      return;
    }

    if (path === '/v1/weather') {
      if (req.method !== 'GET') return sendJson(res, 405, { error: 'method_not_allowed' });
      const city = (url.searchParams.get('city') ?? '').trim();
      if (city.length < 1 || city.length > 80 || !/^[\p{L}\p{N} .,'-]+$/u.test(city)) {
        return sendJson(res, 400, { error: 'invalid_city' });
      }
      try {
        const w = await getWeather({ cfg, city, fetchImpl });
        return sendJson(res, 200, w);
      } catch (e) {
        const err = e instanceof WeatherError ? e : new WeatherError('upstream_error', 502);
        return sendJson(res, err.status, { error: err.code });
      }
    }

    return sendJson(res, 404, { error: 'not_found' });
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((e) => {
      log('error', 'unhandled', { message: e?.message ?? 'unknown' });
      if (!res.headersSent) sendJson(res, 500, { error: 'internal_error' });
      else res.end();
    });
  });
  server.requestTimeout = 120_000;
  server.headersTimeout = 15_000;
  return server;
}
