package com.felix.faze

import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToInt
import org.json.JSONObject

/** Current weather from Open-Meteo (free, no key): geocode the place, then ask for today's conditions. */
object Weather {
    private val CODES = mapOf(
        0 to "clear sky", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "foggy", 48 to "foggy",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 56 to "freezing drizzle", 57 to "freezing drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain",
        71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains", 80 to "light showers", 81 to "showers",
        82 to "heavy showers", 85 to "snow showers", 86 to "heavy snow showers", 95 to "a thunderstorm",
        96 to "a thunderstorm with hail", 99 to "a thunderstorm with hail",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun num(value: Double?, unit: String = ""): String = if (value == null) "?" else "${value.roundToInt()}$unit"

    private fun json(transport: Transport, url: String): JSONObject {
        val r = try {
            transport.get(url, emptyMap(), 8000)
        } catch (e: IOException) {
            throw ToolFailure("could not reach the weather service (${e.javaClass.simpleName})")
        }
        if (r.status != 200) throw ToolFailure("the weather service answered ${r.status}")
        return try {
            JSONObject(r.body)
        } catch (e: Exception) {
            throw ToolFailure("the weather service sent something unreadable")
        }
    }

    fun get(transport: Transport, city: String): String {
        val name = city.trim()
        if (name.isEmpty()) throw ToolFailure("which place? Tell me a city, or set a default place under Keys")
        val geo = json(transport, "https://geocoding-api.open-meteo.com/v1/search?name=${enc(name)}&count=1&language=en&format=json")
        val place = geo.optJSONArray("results")?.optJSONObject(0) ?: throw ToolFailure("I couldn't find a place called $name")
        val data = json(
            transport,
            "https://api.open-meteo.com/v1/forecast?latitude=${place.optDouble("latitude")}&longitude=${place.optDouble("longitude")}" +
                "&timezone=auto&forecast_days=1" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        )
        val cur = data.optJSONObject("current")
        if (cur == null || !cur.has("temperature_2m")) throw ToolFailure("the weather service had no current conditions for that place")
        fun d(o: JSONObject, key: String): Double? = if (o.has(key) && !o.isNull(key)) o.optDouble(key) else null
        val country = place.optString("country", "")
        val where = place.optString("name", name) + if (country.isNotEmpty()) ", $country" else ""
        val sky = CODES[cur.optInt("weather_code", -1)] ?: "unknown conditions"
        var text = "$where: ${num(d(cur, "temperature_2m"), "°C")} (feels like ${num(d(cur, "apparent_temperature"), "°C")}), $sky, " +
            "humidity ${num(d(cur, "relative_humidity_2m"), "%")}, wind ${num(d(cur, "wind_speed_10m"), " km/h")}."
        val daily = data.optJSONObject("daily")
        fun first(key: String): Double? = daily?.optJSONArray(key)?.let { if (it.length() > 0 && !it.isNull(0)) it.optDouble(0) else null }
        val hi = first("temperature_2m_max")
        val lo = first("temperature_2m_min")
        val rain = first("precipitation_probability_max")
        if (hi != null && lo != null) {
            text += " Today: high ${num(hi, "°C")}, low ${num(lo, "°C")}" + if (rain != null) ", ${num(rain, "%")} chance of rain." else "."
        }
        return text
    }
}
