package com.felix.faze

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastRouteTest {
    private fun schema(vararg names: String): JSONArray {
        val a = JSONArray()
        for (n in names) {
            val props = JSONObject()
            val required = JSONArray()
            when (n) {
                "open_app" -> { props.put("app", JSONObject().put("type", "string")); required.put("app") }
                "get_weather" -> { props.put("city", JSONObject().put("type", "string")); required.put("city") }
                "set_volume" -> { props.put("level", JSONObject().put("type", "integer")); required.put("level") }
            }
            a.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", "d")
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))))
        }
        return a
    }

    private val all = schema("vibrate", "get_battery_status", "get_current_datetime", "read_clipboard", "write_clipboard", "set_volume", "get_weather", "open_app", "tap")

    private fun store(): LearnedRoutes = LearnedRoutes(File.createTempFile("learned", ".json").also { it.delete() })

    private fun router(learned: LearnedRoutes = store(), needle: NeedleClient? = null, enabled: Boolean = true) =
        FastRoute(learned, needle, { enabled }, { true }, { it != "tap" })

    // ---- the words

    @Test fun requestsAreReducedToTheirWords() {
        assertEquals(listOf("check", "battery"), Phrase.tokens("Hey Faze, can you please check my battery for me?"))
        assertEquals(listOf("whats", "time"), Phrase.tokens("What's the time?"))
        assertEquals(listOf("open", "spotify"), Phrase.tokens("Okay, open Spotify right now"))
        assertTrue(Phrase.tokens("please").isEmpty())
    }

    // ---- built-in rules

    @Test fun builtInRulesPickTheObviousTools() {
        fun tool(m: String) = BuiltInRoutes.route(m)?.tool
        assertEquals("vibrate", tool("vibrate my phone"))
        assertEquals("get_battery_status", tool("what's my battery"))
        assertEquals("get_current_datetime", tool("what time is it"))
        assertEquals("get_current_datetime", tool("hey faze what's the time please"))
        assertEquals("read_clipboard", tool("what's on my clipboard"))
        assertEquals("set_volume", tool("set volume to 8"))
        assertEquals(8, BuiltInRoutes.route("turn volume to 8")!!.args.getInt("level"))
        assertEquals("Lagos", BuiltInRoutes.route("weather in Lagos today")!!.args.getString("city"))
        assertEquals("", BuiltInRoutes.route("what's the weather")!!.args.getString("city"))
        assertEquals("hello there", BuiltInRoutes.route("copy \"hello there\" to my clipboard")!!.args.getString("text"))
    }

    @Test fun builtInRulesLeaveRealQuestionsToTheModel() {
        for (m in listOf("tell me about battery chemistry in electric cars", "weather tomorrow", "weather in Lagos and then open maps", "what is the history of weather", "who won the match", "how are you"))
            assertNull(m, BuiltInRoutes.route(m))
    }

    // ---- learning

    @Test fun aPhraseBecomesATemplateWithSlots() {
        val m = Templates.make("open Spotify", JSONObject().put("app", "Spotify"))!!
        assertEquals(listOf("open", "{app}"), m.template)
        assertEquals(mapOf("app" to "youtube"), Templates.match(m.template, Phrase.tokens("could you open YouTube")))
        assertEquals(listOf("whats", "weather", "in", "{city}"), Templates.make("what's the weather in Paris?", JSONObject().put("city", "Paris"))!!.template)
        val v = Templates.make("turn it up to 8", JSONObject().put("level", 8))!!
        assertEquals(listOf("turn", "it", "up", "to", "{#level}"), v.template)
        assertEquals(5, Templates.argsFor(v.template, v.empties, Templates.match(v.template, Phrase.tokens("turn it up to 5"))!!).getInt("level"))
        assertNull(Templates.match(v.template, Phrase.tokens("turn it up to loud")))
    }

    @Test fun anArgumentTheUserDidNotSayIsKeptEmpty() {
        val m = Templates.make("what's the weather", JSONObject().put("city", ""))!!
        assertEquals(listOf("whats", "weather"), m.template)
        assertEquals(listOf("city"), m.empties)
        assertEquals("", Templates.argsFor(m.template, m.empties, emptyMap()).getString("city"))
    }

    @Test fun badCandidatesAreNotLearned() {
        assertNull(Templates.make("open it", JSONObject().put("app", "Spotify"))) // the argument is not in the words
        assertNull(Templates.make("Spotify", JSONObject().put("app", "Spotify"))) // nothing but a slot
        assertNull(Templates.make("open the app for the music that I like to hear on long drives", JSONObject().put("app", "Spotify"))) // too long
        assertNull(Templates.make("open spotify and play drake", JSONObject().put("app", "spotify and play drake"))) // two requests in one
    }

    @Test fun joinedRequestsDoNotMatchAShortcut() {
        val t = listOf("open", "{app}")
        assertNull(Templates.match(t, Phrase.tokens("open spotify and play drake")))
        assertNull(Templates.match(t, Phrase.tokens("open")))
        assertNull(Templates.match(t, Phrase.tokens("open one two three four five")))
    }

    @Test fun learnedShortcutsAreSavedDedupedAndForgotten() {
        val s = store()
        assertTrue(s.add("open_app", listOf("open", "{app}"), emptyList()))
        assertFalse(s.add("open_app", listOf("open", "{app}"), emptyList()))
        assertEquals(1, s.size())
        assertFalse(s.add("search_web", listOf("open", "{app}"), emptyList())) // same words, other tool: unclear, so none
        assertEquals(0, s.size())
        s.add("vibrate", listOf("buzz"), emptyList())
        val id = s.list().single().id
        s.hit(id)
        assertEquals(1, s.list().single().hits)
        s.fail(id)
        s.fail(id)
        assertEquals(0, s.size())
        s.add("vibrate", listOf("buzz"), emptyList())
        s.clear()
        assertEquals(0, s.size())
    }

    @Test fun theLeastUsedShortcutMakesRoomWhenFull() {
        val s = store()
        for (i in 1..LearnedRoutes.MAX) s.add("vibrate", listOf("word$i"), emptyList())
        s.hit(s.list().last().id)
        assertTrue(s.add("vibrate", listOf("fresh"), emptyList()))
        assertEquals(LearnedRoutes.MAX, s.size())
        assertTrue(s.list().any { it.template == listOf("fresh") })
        assertTrue(s.list().any { it.template == listOf("word${LearnedRoutes.MAX}") })
    }

    // ---- the router

    @Test fun whatWorkedOnceRunsStraightAwayNextTime() {
        val r = router()
        r.learn("hey faze open Spotify please", "open_app", JSONObject().put("app", "Spotify"))
        val route = r.route("could you open YouTube", all)!!
        assertEquals("open_app", route.tool)
        assertEquals("youtube", route.args.getString("app"))
        assertEquals("learned", route.source)
        assertNull(r.route("could you open", all))
    }

    @Test fun onlySafeLearnableToolsAreLearnedFromPlainWords() {
        val s = store()
        val r = router(s)
        r.learn("tap the screen", "tap", JSONObject())
        r.learn("copy hello to clipboard", "write_clipboard", JSONObject().put("text", "hello"))
        r.learn("open youtube.com", "open_app", JSONObject().put("app", "youtube.com"))
        r.learn("what's my battery", "get_battery_status", JSONObject()) // a built-in rule already covers it
        assertEquals(0, s.size())
        r.learn("how much juice is left", "get_battery_status", JSONObject())
        assertEquals(1, s.size())
        assertEquals("get_battery_status", r.route("how much juice is left?", all)!!.tool)
    }

    @Test fun aWebAddressNeverMatchesALearnedShortcut() {
        val r = router()
        r.learn("open Spotify", "open_app", JSONObject().put("app", "Spotify"))
        assertNull(r.route("open youtube.com", all))
        assertNull(r.route("open https://x.org", all))
    }

    @Test fun aShortcutThatKeepsFailingIsDropped() {
        val s = store()
        val r = router(s)
        r.learn("open Spotify", "open_app", JSONObject().put("app", "Spotify"))
        val route = r.route("open maps", all)!!
        r.outcome(route, false)
        assertEquals(1, s.size())
        r.outcome(route, false)
        assertEquals(0, s.size())
    }

    @Test fun aToolMissingFromTheListIsNotRouted() {
        val r = router()
        assertNull(r.route("vibrate", schema("get_battery_status")))
        assertNotNull(r.route("vibrate", schema("vibrate")))
    }

    @Test fun switchedOffItDoesNothing() {
        val r = router(enabled = false)
        assertNull(r.route("vibrate", all))
        r.learn("open Spotify", "open_app", JSONObject().put("app", "Spotify"))
        assertNull(router(r.let { store() }, enabled = true).route("open maps", all))
    }

    // ---- Needle

    private fun needleBody(name: String, args: String = "{}", conf: Double = 0.9, type: String = "call", n: Int = 1) =
        """{"type":"$type","success":true,"confidence":$conf,"function_calls":[${(1..n).joinToString(",") { """{"name":"$name","arguments":$args}""" }}]}"""

    @Test fun needleIsTrustedOnlyWhenConfidentAndTheCallFitsTheTool() {
        assertEquals("vibrate", NeedleClient.parse(needleBody("vibrate"), all)!!.tool)
        assertEquals("needle", NeedleClient.parse(needleBody("vibrate"), all)!!.source)
        assertNull(NeedleClient.parse(needleBody("vibrate", conf = 0.4), all))
        assertNull(NeedleClient.parse(needleBody("vibrate", type = "text"), all))
        assertNull(NeedleClient.parse(needleBody("vibrate", n = 2), all))
        assertNull(NeedleClient.parse(needleBody("fly_to_moon"), all))
        assertNull(NeedleClient.parse(needleBody("vibrate", """{"strength":3}"""), all)) // an argument the tool does not have
        assertNull(NeedleClient.parse(needleBody("open_app"), all)) // a required argument is missing
        assertEquals("spotify", NeedleClient.parse(needleBody("open_app", """{"app":"spotify"}"""), all)!!.args.getString("app"))
        assertNull(NeedleClient.parse("not json", all))
    }

    @Test fun needleIsAskedLastAndWaitedForOnlyWhenItIsUp() {
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), needleBody("vibrate")) }
        val r = router(needle = NeedleClient(t))
        assertEquals("built-in", r.route("vibrate my phone", all)!!.source)
        assertTrue(t.calls.isEmpty()) // the built-in rule answered first
        assertEquals("needle", r.route("buzz the phone", all)!!.source)
        assertEquals("http://127.0.0.1:8765/complete", t.calls.single().url)
        assertTrue(t.calls.single().body.contains("buzz the phone"))
    }

    @Test fun aMissingNeedleServerIsNotAskedAgainForAMinute() {
        val t = FakeTransport()
        t.onPost = { _, _ -> throw IOException("connection refused") }
        var now = 0L
        val r = router(needle = NeedleClient(t, clock = { now }))
        assertNull(r.route("buzz the phone", all))
        assertNull(r.route("buzz the phone", all))
        assertEquals(1, t.calls.size)
        now = 61_000
        assertNull(r.route("buzz the phone", all))
        assertEquals(2, t.calls.size)
    }

    @Test fun longMessagesAreNotSentToNeedle() {
        val t = FakeTransport()
        val n = NeedleClient(t)
        assertNull(n.ask("x".repeat(200), all))
        assertTrue(t.calls.isEmpty())
    }
}
