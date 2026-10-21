// Strict request validation. Anything unexpected is rejected rather than forwarded upstream.

const ROLES = new Set(['user', 'assistant']);
const STYLES = new Set(['short', 'detailed']);
const LANGS = new Set(['hinglish', 'hindi', 'english']);
const MODES = new Set(['chat', 'study']);

export function validateChatBody(body, cfg) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    return { ok: false, error: 'Body must be a JSON object.' };
  }
  const { messages, style = 'short', language = 'hinglish', mode = 'chat', model } = body;

  if (!Array.isArray(messages) || messages.length === 0) {
    return { ok: false, error: 'messages must be a non-empty array.' };
  }
  if (messages.length > cfg.maxMessages) {
    return { ok: false, error: `Too many messages (max ${cfg.maxMessages}).` };
  }

  const clean = [];
  for (const m of messages) {
    if (m === null || typeof m !== 'object' || !ROLES.has(m.role) || typeof m.text !== 'string') {
      return { ok: false, error: 'Each message needs role (user|assistant) and text (string).' };
    }
    const text = m.text.trim();
    if (text.length === 0) return { ok: false, error: 'Empty message text.' };
    if (text.length > cfg.maxMessageChars) {
      return { ok: false, error: `Message too long (max ${cfg.maxMessageChars} chars).` };
    }
    clean.push({ role: m.role, text });
  }
  if (clean[clean.length - 1].role !== 'user') {
    return { ok: false, error: 'The last message must be from the user.' };
  }

  if (!STYLES.has(style)) return { ok: false, error: 'Invalid style.' };
  if (!LANGS.has(language)) return { ok: false, error: 'Invalid language.' };
  if (!MODES.has(mode)) return { ok: false, error: 'Invalid mode.' };

  let chosenModel = cfg.models[0];
  if (model !== undefined && model !== null && model !== '') {
    if (typeof model !== 'string' || !cfg.models.includes(model)) {
      return { ok: false, error: 'Model is not available on this backend.' };
    }
    chosenModel = model;
  }
  if (!chosenModel) return { ok: false, error: 'No model configured on the backend.', status: 503 };

  return { ok: true, value: { messages: clean, style, language, mode, model: chosenModel } };
}
