// Gemini adapter. Yields normalized events: { delta } ... { done, usage }.
// The API key is sent only server -> Google, in a header, and is never logged or returned.

export class UpstreamError extends Error {
  constructor(code, status) {
    super(code);
    this.code = code; // normalized: upstream_auth | rate_limited | bad_request | upstream_error | timeout | not_configured
    this.status = status;
  }
}

function mapStatus(status) {
  if (status === 401 || status === 403) return new UpstreamError('upstream_auth', 502);
  if (status === 429) return new UpstreamError('rate_limited', 429);
  if (status === 400 || status === 404) return new UpstreamError('bad_request', 502);
  return new UpstreamError('upstream_error', 502);
}

export async function* streamChat({ cfg, model, system, messages, signal, fetchImpl = fetch }) {
  if (!cfg.geminiApiKey) throw new UpstreamError('not_configured', 503);

  const url = `${cfg.geminiBaseUrl}/v1beta/models/${encodeURIComponent(model)}:streamGenerateContent?alt=sse`;
  const body = {
    systemInstruction: { parts: [{ text: system }] },
    contents: messages.map((m) => ({
      role: m.role === 'assistant' ? 'model' : 'user',
      parts: [{ text: m.text }],
    })),
    generationConfig: { temperature: 0.7, maxOutputTokens: 2048 },
  };

  let res;
  try {
    res = await fetchImpl(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': cfg.geminiApiKey },
      body: JSON.stringify(body),
      signal,
    });
  } catch (err) {
    if (err?.name === 'AbortError' || err?.name === 'TimeoutError') throw new UpstreamError('timeout', 504);
    throw new UpstreamError('upstream_error', 502);
  }
  if (!res.ok) {
    await res.body?.cancel?.().catch(() => {});
    throw mapStatus(res.status);
  }

  const decoder = new TextDecoder();
  let buf = '';
  let usage;

  const handleLine = (line) => {
    if (!line.startsWith('data:')) return null;
    const payload = line.slice(5).trim();
    if (!payload || payload === '[DONE]') return null;
    try {
      return JSON.parse(payload);
    } catch {
      return null;
    }
  };

  try {
    for await (const chunk of res.body) {
      buf += decoder.decode(chunk, { stream: true });
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).replace(/\r$/, '');
        buf = buf.slice(nl + 1);
        const obj = handleLine(line);
        if (!obj) continue;
        if (obj.usageMetadata) {
          usage = {
            promptTokens: obj.usageMetadata.promptTokenCount,
            outputTokens: obj.usageMetadata.candidatesTokenCount,
            totalTokens: obj.usageMetadata.totalTokenCount,
          };
        }
        const parts = obj.candidates?.[0]?.content?.parts;
        if (Array.isArray(parts)) {
          const text = parts.map((p) => (typeof p.text === 'string' ? p.text : '')).join('');
          if (text) yield { delta: text };
        }
        if (obj.promptFeedback?.blockReason) throw new UpstreamError('blocked', 200);
      }
    }
  } catch (err) {
    if (err instanceof UpstreamError) throw err;
    if (err?.name === 'AbortError' || err?.name === 'TimeoutError') throw new UpstreamError('timeout', 504);
    throw new UpstreamError('upstream_error', 502);
  }
  yield { done: true, usage };
}
