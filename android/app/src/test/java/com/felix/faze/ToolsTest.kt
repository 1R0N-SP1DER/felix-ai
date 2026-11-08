package com.felix.faze

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolsTest {
    private val device = FakeDevice()
    private val settings = FakeSettings()
    private val transport = FakeTransport()
    private val tools = Tools(device, transport, settings)

    private fun run(name: String, args: String = "{}") = tools.execute(name, JSONObject(args))

    @Test
    fun theSchemaListsEveryToolWithItsParameters() {
        assertEquals(
            listOf("get_current_datetime", "get_battery_status", "set_volume", "get_wifi_status", "read_clipboard", "write_clipboard", "vibrate",
                "search_web", "open_url", "open_app", "list_apps", "get_weather", "read_screen"),
            tools.names(),
        )
        val schema = tools.schema()
        val byName = (0 until schema.length()).associate { val f = schema.getJSONObject(it).getJSONObject("function"); f.getString("name") to f }
        for ((name, f) in byName) {
            assertTrue(name, f.getString("description").isNotEmpty())
            assertEquals(name, "object", f.getJSONObject("parameters").getString("type"))
        }
        assertEquals("integer", byName.getValue("set_volume").getJSONObject("parameters").getJSONObject("properties").getJSONObject("level").getString("type"))
        assertEquals("city", byName.getValue("get_weather").getJSONObject("parameters").getJSONArray("required").getString(0))
        assertEquals(0, byName.getValue("vibrate").getJSONObject("parameters").getJSONArray("required").length())
    }

    @Test
    fun simpleToolsPassThrough() {
        assertEquals(ToolResult(true, "Battery is at 76%, not charging"), run("get_battery_status"))
        assertEquals("Connected to Wi-Fi.", run("get_wifi_status").text)
        assertEquals("hello", run("read_clipboard").text)
        assertEquals("Vibrated.", run("vibrate").text)
        assertTrue(run("get_current_datetime").text.startsWith("Wednesday"))
    }

    @Test
    fun readScreenAsksThePhone() {
        assertEquals("Screen 1080x2400, app in front: com.example.app", run("read_screen").text)
        assertTrue(device.log.contains("screen"))
    }

    @Test
    fun volumeIsClampedAndRequired() {
        assertEquals("Media volume set to 15 of 15.", run("set_volume", "{\"level\": 99}").text)
        assertEquals("Media volume set to 0 of 15.", run("set_volume", "{\"level\": -4}").text)
        assertEquals("Media volume set to 6 of 15.", run("set_volume", "{\"level\": \"6\"}").text)
        val missing = run("set_volume")
        assertFalse(missing.ok)
        assertTrue(missing.text.contains("level"))
    }

    @Test
    fun clipboardAndAppsNeedTheirArguments() {
        assertFalse(run("write_clipboard", "{\"text\": \"\"}").ok)
        assertEquals("Copied to the clipboard.", run("write_clipboard", "{\"text\": \"hi\"}").text)
        assertFalse(run("open_app", "{\"app\": \"  \"}").ok)
        assertEquals("Opened YouTube.", run("open_app", "{\"app\": \" YouTube \"}").text)
        assertEquals("Installed apps (2): Chrome, YouTube.", run("list_apps", "{\"search\": \"\"}").text)
        assertTrue(device.log.contains("open:YouTube"))
    }

    @Test
    fun onlyWebLinksAreOpened() {
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/x", "intent://x", "github.com", "")) {
            val r = run("open_url", JSONObject().put("url", bad).toString())
            assertFalse(bad, r.ok)
        }
        assertTrue(run("open_url", "{\"url\": \"https://github.com\"}").ok)
        assertTrue(run("open_url", "{\"url\": \"http://example.com\"}").ok)
    }

    @Test
    fun unknownToolsAndFailuresAreExplained() {
        val unknown = run("delete_everything")
        assertFalse(unknown.ok)
        assertEquals("unknown tool: delete_everything", unknown.text)
        device.failWith = ToolFailure("battery information is not available")
        assertEquals(ToolResult(false, "battery information is not available"), run("get_battery_status"))
        device.failWith = IllegalStateException("boom")
        assertEquals(ToolResult(false, "get_battery_status failed: boom"), run("get_battery_status"))
    }

    // ---- app matching

    private val apps = listOf(Pair("Chrome", "com.chrome"), Pair("YouTube", "com.yt"), Pair("YouTube Music", "com.ytm"), Pair("Settings", "com.set"), Pair("X", "com.x"))

    @Test
    fun appMatching() {
        assertEquals("com.yt", AppMatch.find("youtube", apps)!!.second)
        assertEquals("com.yt", AppMatch.find("you", apps)!!.second) // starts with: the shortest name wins
        assertEquals("com.ytm", AppMatch.find("music", apps)!!.second)
        assertEquals("com.yt", AppMatch.find("the youtube app", apps)!!.second) // what was said contains the name
        assertNull(AppMatch.find("spotify", apps))
        assertNull(AppMatch.find("   ", apps))
        assertNull(AppMatch.find("a letter x here", apps)) // one-letter names are never matched by containment
    }

    // ---- weather

    private val geocode = "{\"results\": [{\"name\": \"Lagos\", \"country\": \"Nigeria\", \"latitude\": 6.45, \"longitude\": 3.4}]}"
    private val forecast = "{\"current\": {\"temperature_2m\": 30.6, \"apparent_temperature\": 35.2, \"relative_humidity_2m\": 78, \"weather_code\": 2, " +
        "\"wind_speed_10m\": 11.6}, \"daily\": {\"temperature_2m_max\": [32.1], \"temperature_2m_min\": [25.0], \"precipitation_probability_max\": [40]}}"
    private val expected = "Lagos, Nigeria: 31°C (feels like 35°C), partly cloudy, humidity 78%, wind 12 km/h. Today: high 32°C, low 25°C, 40% chance of rain."

    private fun weatherNet(geo: String = geocode, fore: String = forecast, status: Int = 200) {
        transport.onGet = { url -> HttpResult(status, emptyMap(), if (url.contains("geocoding")) geo else fore) }
    }

    @Test
    fun weatherReadsLikeASentence() {
        weatherNet()
        assertEquals(ToolResult(true, expected), run("get_weather", "{\"city\": \"Lagos\"}"))
        assertTrue(transport.calls[0].url.contains("name=Lagos"))
        assertTrue(transport.calls[1].url.contains("latitude=6.45"))
        assertTrue(transport.calls[1].url.contains("forecast_days=1"))
    }

    @Test
    fun anEmptyCityMeansTheDefaultPlace() {
        weatherNet()
        settings.city = " Lagos "
        assertEquals(expected, run("get_weather", "{\"city\": \"\"}").text)
        assertTrue(transport.calls[0].url.contains("name=Lagos"))
        settings.city = ""
        val none = run("get_weather", "{\"city\": \"\"}")
        assertFalse(none.ok)
        assertTrue(none.text.contains("which place"))
    }

    @Test
    fun weatherProblemsAreSaidPlainly() {
        weatherNet(geo = "{}")
        assertEquals("I couldn't find a place called Atlantis", run("get_weather", "{\"city\": \"Atlantis\"}").text)
        weatherNet(status = 503)
        assertTrue(run("get_weather", "{\"city\": \"Lagos\"}").text.contains("answered 503"))
        transport.onGet = { throw IOException("no route") }
        assertTrue(run("get_weather", "{\"city\": \"Lagos\"}").text.contains("could not reach the weather service"))
        weatherNet(geo = "<html>")
        assertTrue(run("get_weather", "{\"city\": \"Lagos\"}").text.contains("unreadable"))
        weatherNet(fore = "{\"current\": {}}")
        assertTrue(run("get_weather", "{\"city\": \"Lagos\"}").text.contains("no current conditions"))
    }

    @Test
    fun oddWeatherCodesAndMissingDailyNumbersAreNotInvented() {
        weatherNet(fore = "{\"current\": {\"temperature_2m\": 20.0, \"weather_code\": 999}}")
        val text = run("get_weather", "{\"city\": \"Lagos\"}").text
        assertTrue(text.contains("unknown conditions"))
        assertFalse(text.contains("Today:"))
        assertTrue(text, text.contains("(feels like ?)"))
    }

    // ---- search

    private val good = "{\"answer\": \"Python is a programming language.\", \"results\": [" +
        "{\"title\": \"Python.org\", \"url\": \"https://python.org\", \"content\": \"The  official\\nsite " + "x".repeat(400) + "\"}," +
        "{\"title\": \"Wikipedia\", \"url\": \"https://en.wikipedia.org/wiki/Python\", \"content\": \"An encyclopedia entry.\"}]}"

    @Test
    fun searchFormatsAnswerAndSources() {
        transport.onPost = { _, _ -> HttpResult(200, emptyMap(), good) }
        val text = run("search_web", "{\"query\": \"what is python\"}").text
        val lines = text.split("\n")
        assertTrue(lines[0].contains("what is python"))
        assertEquals("Summary: Python is a programming language.", lines[1])
        assertTrue(lines[2].startsWith("1. Python.org: The official site xxx"))
        assertTrue(lines[2].length <= 270)
        assertEquals("   https://python.org", lines[3])
        val call = transport.calls[0]
        assertEquals("https://api.tavily.com/search", call.url)
        assertEquals("Bearer tvly-test", call.headers["Authorization"])
        val body = JSONObject(call.body)
        assertEquals("what is python", body.getString("query"))
        assertEquals(5, body.getInt("max_results"))
        assertTrue(body.getBoolean("include_answer"))
    }

    @Test
    fun searchErrorsArePlain() {
        val cases = listOf(401 to "refused the key", 403 to "refused the key", 432 to "limit reached (432)", 429 to "limit reached (429)", 500 to "answered 500")
        for ((status, text) in cases) {
            transport.onPost = { _, _ -> HttpResult(status, emptyMap(), "{}") }
            val r = run("search_web", "{\"query\": \"q\"}")
            assertFalse(r.ok)
            assertTrue("$status: ${r.text}", r.text.contains(text))
        }
        transport.onPost = { _, _ -> HttpResult(200, emptyMap(), "{\"results\": []}") }
        assertTrue(run("search_web", "{\"query\": \"q\"}").text.contains("found nothing"))
        transport.onPost = { _, _ -> throw IOException("x") }
        assertTrue(run("search_web", "{\"query\": \"q\"}").text.contains("could not be reached"))
        transport.onPost = { _, _ -> HttpResult(200, emptyMap(), "not json") }
        assertTrue(run("search_web", "{\"query\": \"q\"}").text.contains("unreadable"))
        settings.tavily = ""
        assertTrue(run("search_web", "{\"query\": \"q\"}").text.contains("no key"))
        settings.tavily = "k"
        assertFalse(run("search_web", "{\"query\": \"  \"}").ok)
    }

    @Test
    fun anAnswerAloneIsEnough() {
        transport.onPost = { _, _ -> HttpResult(200, emptyMap(), "{\"answer\": \"Just this.\", \"results\": []}") }
        assertTrue(run("search_web", "{\"query\": \"q\"}").text.contains("Summary: Just this."))
        if (transport.calls.isEmpty()) fail("no request was made")
    }
}
