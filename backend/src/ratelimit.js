// Fixed-window per-key rate limiter (in-memory; use a shared store if you run several instances).

export class RateLimiter {
  constructor({ limit, windowMs = 60_000, now = () => Date.now() }) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.now = now;
    this.hits = new Map();
  }

  /** Returns { allowed, retryAfterSec }. */
  check(key) {
    const t = this.now();
    this.#sweep(t);
    const entry = this.hits.get(key);
    if (!entry || t - entry.start >= this.windowMs) {
      this.hits.set(key, { start: t, count: 1 });
      return { allowed: true, retryAfterSec: 0 };
    }
    if (entry.count >= this.limit) {
      return { allowed: false, retryAfterSec: Math.max(1, Math.ceil((entry.start + this.windowMs - t) / 1000)) };
    }
    entry.count += 1;
    return { allowed: true, retryAfterSec: 0 };
  }

  #sweep(t) {
    if (this.hits.size < 1000) return;
    for (const [k, v] of this.hits) if (t - v.start >= this.windowMs) this.hits.delete(k);
  }
}
