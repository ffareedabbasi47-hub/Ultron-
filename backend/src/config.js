// Loads and validates configuration from environment variables. Secrets never leave this process.

function int(env, name, def, { min = 1, max = Number.MAX_SAFE_INTEGER } = {}) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  const n = Number.parseInt(raw, 10);
  if (!Number.isFinite(n) || n < min || n > max) {
    throw new Error(`Invalid ${name}: expected integer in [${min}, ${max}]`);
  }
  return n;
}

function list(value) {
  return (value ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function loadConfig(env = process.env) {
  const tokens = list(env.APP_TOKENS);
  const allowNoAuth = env.ALLOW_NO_AUTH === 'true';
  if (tokens.length === 0 && !allowNoAuth) {
    throw new Error(
      'APP_TOKENS is not set. Refusing to start an unauthenticated proxy. ' +
        'Set APP_TOKENS, or ALLOW_NO_AUTH=true for local development only.',
    );
  }
  for (const t of tokens) {
    if (t.length < 16) throw new Error('Each APP_TOKENS entry must be at least 16 characters.');
  }

  return Object.freeze({
    port: int(env, 'PORT', 8787, { min: 0, max: 65535 }),
    host: env.HOST || '0.0.0.0',
    geminiApiKey: env.GEMINI_API_KEY || '',
    geminiBaseUrl: (env.GEMINI_BASE_URL || 'https://generativelanguage.googleapis.com').replace(/\/+$/, ''),
    models: list(env.GEMINI_MODELS),
    appTokens: tokens,
    authEnabled: tokens.length > 0,
    rateLimitPerMin: int(env, 'RATE_LIMIT_PER_MIN', 30, { max: 10000 }),
    maxBodyBytes: int(env, 'MAX_BODY_BYTES', 64 * 1024, { max: 1024 * 1024 }),
    maxMessages: int(env, 'MAX_MESSAGES', 40, { max: 200 }),
    maxMessageChars: int(env, 'MAX_MESSAGE_CHARS', 8000, { max: 100000 }),
    upstreamTimeoutMs: int(env, 'UPSTREAM_TIMEOUT_MS', 45000, { max: 300000 }),
    creatorName: env.CREATOR_NAME || 'Fardeen',
    geocodingBaseUrl: (env.OPEN_METEO_GEOCODING_URL || 'https://geocoding-api.open-meteo.com').replace(/\/+$/, ''),
    forecastBaseUrl: (env.OPEN_METEO_FORECAST_URL || 'https://api.open-meteo.com').replace(/\/+$/, ''),
  });
}
