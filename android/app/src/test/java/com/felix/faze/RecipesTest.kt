package com.felix.faze

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {
    private fun book() = RecipeBook(File.createTempFile("recipes", ".json").also { it.delete() })
    private val open = listOf(Step("open_app", "{\"name\":\"Chrome\"}", true))
    private val have = setOf("open_app", "set_volume")

    @Test fun wordOrderAndFillerDoNotChangeTheKey() {
        assertEquals(RecipeBook.keyOf("open chrome please"), RecipeBook.keyOf("please open the chrome"))
    }

    @Test fun onlyRepeatableSuccessfulShortChainsAreLearned() {
        assertTrue(RecipeBook.canLearn(open))
        assertFalse(RecipeBook.canLearn(emptyList()))
        assertFalse(RecipeBook.canLearn(listOf(Step("open_app", "{}", false))))
        assertFalse(RecipeBook.canLearn(listOf(Step("run_shell", "{}", true))))
        assertFalse(RecipeBook.canLearn(List(6) { open[0] }))
    }

    @Test fun saveFindHitAndReplace() {
        val b = book()
        assertTrue(b.save("open chrome", open, "Opened Chrome."))
        val r = b.find("chrome open", have)!!
        assertTrue(r.silent)
        assertEquals("Opened Chrome.", r.say)
        b.hit(r.key)
        assertEquals(1, b.find("open chrome", have)!!.hits)
        b.save("open chrome", open, "Done.")
        assertEquals(1, b.size())
        assertEquals("Done.", b.find("open chrome", have)!!.say)
    }

    @Test fun findNeedsEveryToolToStillExist() {
        val b = book()
        b.save("open chrome", open, "x")
        assertNull(b.find("open chrome", setOf("set_volume")))
    }

    @Test fun aRecipeThatKeepsFailingIsDropped() {
        val b = book()
        b.save("open chrome", open, "x")
        val key = RecipeBook.keyOf("open chrome")
        b.fail(key)
        assertEquals(1, b.size())
        b.fail(key)
        assertEquals(0, b.size())
    }

    @Test fun removeAndClear() {
        val b = book()
        b.save("open chrome", open, "x")
        b.save("volume ten", listOf(Step("set_volume", "{\"level\":10}", true)), "x")
        b.remove("chrome open")
        assertEquals(1, b.size())
        b.clear()
        assertEquals(0, b.size())
    }

    @Test fun stepsSurviveSaving() {
        val b = book()
        b.save("open chrome", open, "x")
        assertEquals(open, b.find("open chrome", have)!!.steps)
    }
}

class FeedbackTest {
    private class Fake : Feedback.Learner {
        val log = ArrayList<String>()
        var saves = true
        override fun learnTemplate(request: String, tool: String, args: JSONObject) { log.add("template:$tool") }
        override fun saveRecipe(request: String, steps: List<Step>, say: String): Boolean { log.add("recipe:${steps.size}"); return saves }
        override fun forgetRecipe(request: String) { log.add("forgetRecipe") }
        override fun confirmRecipe(request: String) { log.add("confirmRecipe") }
        override fun forgetShortcut(id: Int) { log.add("forget:$id") }
        override fun confirmShortcut(id: Int) { log.add("confirm:$id") }
    }

    private fun msg(chain: List<Step> = emptyList(), via: String = "", feedback: Int = 0, id: Int = -1) =
        ChatMessage(1, "assistant", "reply", chain = chain, via = via, viaId = id, feedback = feedback)

    private val battery = listOf(Step("get_battery_status", "{}", true))
    private val risky = listOf(Step("run_shell", "{}", true))

    @Test fun upOnASingleLearnableToolLearnsATemplate() {
        val f = Fake()
        val o = Feedback.rate(msg(battery, "model"), "battery", 1, f)
        assertEquals(1, o.feedback)
        assertEquals(listOf("template:get_battery_status"), f.log)
    }

    @Test fun upOnAChainSavesARecipe() {
        val f = Fake()
        Feedback.rate(msg(battery + battery, "model"), "x", 1, f)
        assertEquals(listOf("recipe:2"), f.log)
    }

    @Test fun upOnAnUnsafeChainSavesNothing() {
        val f = Fake()
        f.saves = false
        val o = Feedback.rate(msg(risky, "model"), "x", 1, f)
        assertTrue(o.note.contains("didn't save"))
    }

    @Test fun upConfirmsRecipesAndShortcutsAndLeavesBuiltInsAlone() {
        val f = Fake()
        Feedback.rate(msg(battery, "recipe"), "x", 1, f)
        Feedback.rate(msg(battery, "learned", id = 4), "x", 1, f)
        Feedback.rate(msg(battery, "built-in"), "x", 1, f)
        assertEquals(listOf("confirmRecipe", "confirm:4"), f.log)
    }

    @Test fun upOnAPlainAnswerLearnsNothing() {
        val f = Fake()
        Feedback.rate(msg(), "x", 1, f)
        assertTrue(f.log.isEmpty())
    }

    @Test fun downForgetsWhatWasUsed() {
        val f = Fake()
        assertEquals(-1, Feedback.rate(msg(battery, "recipe"), "x", -1, f).feedback)
        Feedback.rate(msg(battery, "learned", id = 7), "x", -1, f)
        assertEquals(listOf("forgetRecipe", "forget:7"), f.log)
    }

    @Test fun theSameTapAgainTakesItBack() {
        val f = Fake()
        assertEquals(0, Feedback.rate(msg(battery, feedback = 1), "x", 1, f).feedback)
        assertTrue(f.log.isEmpty())
    }
}

class AgentRecipeTest {
    /** Like FakeTools but its tool list is whatever it can run, so recipes with any tool can be found. */
    private class Tools(private val results: Map<String, ToolResult>) : ToolRunner {
        val executed = ArrayList<Pair<String, JSONObject>>()

        override fun schema(): org.json.JSONArray {
            val out = org.json.JSONArray()
            for (name in results.keys) out.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", "d")))
            return out
        }

        override fun execute(name: String, args: JSONObject): ToolResult {
            executed.add(Pair(name, args))
            return results[name] ?: ToolResult(true, "ok")
        }
    }

    private fun book(): RecipeBook = RecipeBook(File.createTempFile("recipes", ".json").also { it.delete() })
    private fun agent(chat: FakeChat, tools: Tools, b: RecipeBook, offline: Boolean) = Agent(chat, tools, 5, null, b, { offline })
    private fun run(a: Agent, msg: String, ev: Events = Events()) = a.run("m", "S", listOf("user" to msg), ev.events) { false }

    @Test fun offlineReplayNeverCallsTheModelAndSilentStepsSayTheSavedReply() {
        val b = book()
        b.save("open chrome", listOf(Step("open_app", "{\"name\":\"Chrome\"}", true)), "Opened Chrome.")
        val chat = FakeChat()
        val tools = Tools(mapOf("open_app" to ToolResult(true, "Opened Chrome.")))
        assertEquals("Opened Chrome.", run(agent(chat, tools, b, true), "chrome open"))
        assertTrue(chat.calls.isEmpty())
        assertEquals(1, tools.executed.size)
        assertEquals(1, b.list().single().hits)
    }

    @Test fun nonSilentStepsReturnTheToolsOwnText() {
        val b = book()
        b.save("my battery", listOf(Step("get_battery_status", "{}", true)), "old text")
        val chat = FakeChat()
        val tools = Tools(mapOf("get_battery_status" to ToolResult(true, "70%")))
        assertEquals("70%", run(agent(chat, tools, b, true), "my battery"))
        assertTrue(chat.calls.isEmpty())
    }

    @Test fun onlineIgnoresRecipes() {
        val b = book()
        b.save("open chrome", listOf(Step("open_app", "{}", true)), "Opened Chrome.")
        val chat = FakeChat(text("From the model."))
        val tools = Tools(mapOf("open_app" to ToolResult(true, "ok")))
        assertEquals("From the model.", run(agent(chat, tools, b, false), "open chrome"))
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun aFailingFirstStepFallsThroughToTheModel() {
        val b = book()
        b.save("open chrome", listOf(Step("open_app", "{}", true)), "x")
        val chat = FakeChat(text("Could not open it."))
        val tools = Tools(mapOf("open_app" to ToolResult(false, "no app")))
        assertEquals("Could not open it.", run(agent(chat, tools, b, true), "open chrome"))
        assertEquals(1, b.list().single().fails)
    }

    @Test fun aLaterFailureIsReportedWithoutTheModel() {
        val b = book()
        b.save("do both", listOf(Step("open_app", "{}", true), Step("set_volume", "{}", true)), "x")
        val chat = FakeChat()
        val tools = Tools(mapOf("open_app" to ToolResult(true, "ok"), "set_volume" to ToolResult(false, "denied by system")))
        val out = run(agent(chat, tools, b, true), "do both")
        assertTrue(out.contains("set volume failed"))
        assertTrue(chat.calls.isEmpty())
    }

    @Test fun stepsAndViaAreReported() {
        val b = book()
        b.save("open chrome", listOf(Step("open_app", "{}", true)), "x")
        val tools = Tools(mapOf("open_app" to ToolResult(true, "ok")))
        val steps = ArrayList<String>()
        var via = ""
        val ev = AgentEvents({}, {}, { _, _ -> })
        ev.onStep = { n, _, ok -> steps.add("$n:$ok") }
        ev.onVia = { how, _ -> via = how }
        agent(FakeChat(), tools, b, true).run("m", "S", listOf("user" to "open chrome"), ev) { false }
        assertEquals(listOf("open_app:true"), steps)
        assertEquals("recipe", via)
    }
}

class ChatStoreFeedbackTest {
    @Test fun chainViaModelAndFeedbackSurviveSaving() {
        val dir = java.io.File.createTempFile("chats", "").also { it.delete() }
        val store = ChatStore(dir)
        val reply = ChatMessage(2, "assistant", "Opened.", chain = listOf(Step("open_app", "{\"name\":\"Chrome\"}", true)), via = "learned", viaId = 3, model = "groq", feedback = 1)
        store.save("c1", listOf(ChatMessage(1, "user", "open chrome"), reply))
        val back = store.load("c1")!!.messages[1]
        assertEquals(reply, back)
    }
}
