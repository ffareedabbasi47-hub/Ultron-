// Real weather via Open-Meteo (no API key). Returns only data actually received.

const WMO = new Map([
  [0, 'Clear sky'], [1, 'Mainly clear'], [2, 'Partly cloudy'], [3, 'Overcast'],
  [45, 'Fog'], [48, 'Rime fog'],
  [51, 'Light drizzle'], [53, 'Drizzle'], [55, 'Dense drizzle'],
  [61, 'Light rain'], [63, 'Rain'], [65, 'Heavy rain'],
  [71, 'Light snow'], [73, 'Snow'], [75, 'Heavy snow'],
  [80, 'Rain showers'], [81, 'Rain showers'], [82, 'Violent rain showers'],
  [95, 'Thunderstorm'], [96, 'Thunderstorm with hail'], [99, 'Thunderstorm with heavy hail'],
]);

const cache = new Map(); // city(lowercase) -> { at, value }
const TTL_MS = 10 * 60 * 1000;

export class WeatherError extends Error {
  constructor(code, status) {
    super(code);
    this.code = code;
    this.status = status;
  }
}

export async function getWeather({ cfg, city, fetchImpl = fetch, now = () => Date.now() }) {
  const key = city.toLowerCase();
  const hit = cache.get(key);
  if (hit && now() - hit.at < TTL_MS) return hit.value;

  const signal = AbortSignal.timeout(10_000);
  let geo;
  try {
    const g = await fetchImpl(
      `${cfg.geocodingBaseUrl}/v1/search?name=${encodeURIComponent(city)}&count=1&language=en&format=json`,
      { signal },
    );
    if (!g.ok) throw new WeatherError('upstream_error', 502);
    geo = (await g.json()).results?.[0];
  } catch (e) {
    if (e instanceof WeatherError) throw e;
    throw new WeatherError('upstream_error', 502);
  }
  if (!geo) throw new WeatherError('city_not_found', 404);

  let cur;
  try {
    const f = await fetchImpl(
      `${cfg.forecastBaseUrl}/v1/forecast?latitude=${geo.latitude}&longitude=${geo.longitude}` +
        '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto',
      { signal },
    );
    if (!f.ok) throw new WeatherError('upstream_error', 502);
    cur = (await f.json()).current;
  } catch (e) {
    if (e instanceof WeatherError) throw e;
    throw new WeatherError('upstream_error', 502);
  }
  if (!cur || typeof cur.temperature_2m !== 'number') throw new WeatherError('upstream_error', 502);

  const value = {
    city: geo.name,
    country: geo.country ?? null,
    tempC: cur.temperature_2m,
    feelsC: cur.apparent_temperature ?? null,
    humidity: cur.relative_humidity_2m ?? null,
    windKph: cur.wind_speed_10m ?? null,
    description: WMO.get(cur.weather_code) ?? 'Unknown',
    observedAt: cur.time ?? null,
  };
  cache.set(key, { at: now(), value });
  return value;
}

export function _clearWeatherCache() {
  cache.clear();
}
