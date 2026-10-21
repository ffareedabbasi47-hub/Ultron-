// Structured logging that never records request bodies, headers, prompts or secrets.

const PATTERNS = [
  /AIza[0-9A-Za-z_-]{20,}/g,
  /\bsk-[A-Za-z0-9_-]{16,}/g,
  /bearer\s+[A-Za-z0-9._~+/=-]{12,}/gi,
  /(api[_-]?key|token|secret|password)\s*[=:]\s*\S+/gi,
  /[A-Za-z0-9+/_-]{40,}={0,2}/g,
];

export function redact(text) {
  let out = String(text);
  for (const p of PATTERNS) out = out.replace(p, '[redacted]');
  return out;
}

export function makeLogger(sink = (line) => process.stdout.write(line + '\n')) {
  return (level, msg, fields = {}) => {
    const safe = {};
    for (const [k, v] of Object.entries(fields)) safe[k] = typeof v === 'string' ? redact(v) : v;
    sink(JSON.stringify({ t: new Date().toISOString(), level, msg: redact(msg), ...safe }));
  };
}
