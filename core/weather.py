"""
felix-ai Core -- weather.py
Current weather from Open-Meteo (free, no key, one small request): geocode the place, then ask for the
conditions. Fast and reliable, so a weather question never depends on web-search quota.
"""

import time

import requests

GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST = "https://api.open-meteo.com/v1/forecast"
CACHE_SECONDS = 600.0
_places = {}
_forecasts = {}

# WMO weather interpretation codes
CODES = {
    0: "clear sky", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "foggy", 48: "foggy",
    51: "light drizzle", 53: "drizzle", 55: "heavy drizzle", 56: "freezing drizzle", 57: "freezing drizzle",
    61: "light rain", 63: "rain", 65: "heavy rain", 66: "freezing rain", 67: "freezing rain",
    71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains", 80: "light showers", 81: "showers",
    82: "heavy showers", 85: "snow showers", 86: "heavy snow showers", 95: "a thunderstorm",
    96: "a thunderstorm with hail", 99: "a thunderstorm with hail",
}


class WeatherError(Exception):
    pass


def _get_json(url: str, params: dict, get, timeout: float) -> dict:
    try:
        r = get(url, params=params, timeout=timeout)
    except requests.RequestException as e:
        raise WeatherError(f"could not reach the weather service ({type(e).__name__})")
    if r.status_code != 200:
        raise WeatherError(f"the weather service answered {r.status_code}")
    try:
        return r.json()
    except ValueError:
        raise WeatherError("the weather service sent something unreadable")


def find_place(name: str, get=requests.get, timeout: float = 8.0) -> dict:
    key = name.strip().lower()
    if key in _places:
        return _places[key]
    data = _get_json(GEOCODE, {"name": name.strip(), "count": 1, "language": "en", "format": "json"}, get, timeout)
    found = (data.get("results") or [None])[0]
    if not found:
        raise WeatherError(f"I couldn't find a place called {name.strip()}")
    _places[key] = found
    return found


def _num(value, unit: str = "") -> str:
    return f"{round(value)}{unit}" if isinstance(value, (int, float)) else "?"


def get_weather(city: str, get=requests.get, now=time.monotonic, timeout: float = 8.0) -> str:
    city = (city or "").strip()
    if not city:
        raise WeatherError("which place? Tell me a city, or set weather.location")
    place = find_place(city, get, timeout)
    key = (place["latitude"], place["longitude"])
    cached = _forecasts.get(key)
    if cached and now() - cached[0] < CACHE_SECONDS:
        data = cached[1]
    else:
        data = _get_json(FORECAST, {
            "latitude": place["latitude"], "longitude": place["longitude"], "timezone": "auto", "forecast_days": 1,
            "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
            "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        }, get, timeout)
        _forecasts[key] = (now(), data)
    cur, day = data.get("current") or {}, data.get("daily") or {}
    if "temperature_2m" not in cur:
        raise WeatherError("the weather service had no current conditions for that place")
    where = place.get("name", city) + (f", {place['country']}" if place.get("country") else "")
    sky = CODES.get(cur.get("weather_code"), "unknown conditions")
    text = (f"{where}: {_num(cur['temperature_2m'], '°C')} (feels like {_num(cur.get('apparent_temperature'), '°C')}), {sky}, "
            f"humidity {_num(cur.get('relative_humidity_2m'), '%')}, wind {_num(cur.get('wind_speed_10m'), ' km/h')}.")
    hi, lo, rain = ((day.get(k) or [None])[0] for k in ("temperature_2m_max", "temperature_2m_min", "precipitation_probability_max"))
    if hi is not None and lo is not None:
        text += f" Today: high {_num(hi, '°C')}, low {_num(lo, '°C')}" + (f", {_num(rain, '%')} chance of rain." if rain is not None else ".")
    return text
