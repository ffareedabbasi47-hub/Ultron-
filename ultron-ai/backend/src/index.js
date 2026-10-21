import fs from 'node:fs';
import { loadConfig } from './config.js';
import { createServer } from './server.js';

// Minimal .env loader (no dependency). Real environment variables always win.
try {
  const text = fs.readFileSync(new URL('../.env', import.meta.url), 'utf8');
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    const key = line.slice(0, eq).trim();
    const value = line.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
    if (process.env[key] === undefined) process.env[key] = value;
  }
} catch {
  /* no .env file: fine in production where env vars are injected */
}

const cfg = loadConfig();
const server = createServer(cfg);
server.listen(cfg.port, cfg.host, () => {
  console.log(
    JSON.stringify({
      msg: 'ULTRON backend listening',
      port: cfg.port,
      auth: cfg.authEnabled,
      providerConfigured: Boolean(cfg.geminiApiKey) && cfg.models.length > 0,
    }),
  );
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => server.close(() => process.exit(0)));
}
