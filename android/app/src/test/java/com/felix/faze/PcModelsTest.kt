package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcModelsTest {
    @Test fun readsTheNamesAndIgnoresEverythingElse() {
        val ok = JSONObject("{\"t\":\"pc_models_ok\",\"models\":[\"qwen2.5:1.5b\",\"\",\"llama3.2:3b\"]}")
        assertEquals(listOf("qwen2.5:1.5b", "llama3.2:3b"), PcModels.parse(ok))
        assertTrue(PcModels.parse(JSONObject("{\"t\":\"error\",\"message\":\"off\"}")).isEmpty())
        assertTrue(PcModels.parse(JSONObject("{}")).isEmpty())
    }

    @Test fun chosenPcModelStaysOnThePcAndCustomIsGone() {
        val plan = Fallbacks.plan("pc", setOf("groq", "gemini")) { if (it == "pc") "qwen2.5:1.5b" else "m" }
        assertEquals(listOf(Attempt("pc", "qwen2.5:1.5b")), plan)
        assertFalse("custom" in ModelSheet.live)
        assertFalse("custom" in ModelSheet.names)
        assertFalse(ModelSheet.thinkEnabled("pc"))
    }

    @Test fun theChosenModelIsSentToTheComputer() {
        var sent: JSONObject? = null
        val api = PcChat({ m, _, _ -> sent = m; JSONObject().put("content", "x") })
        val hi = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        api.chat("llama3.2:3b", hi, null, { }, { false })
        assertEquals("llama3.2:3b", sent!!.getString("model"))
        api.chat("ollama", hi, null, { }, { false })
        assertFalse(sent!!.has("model"))
    }
}
