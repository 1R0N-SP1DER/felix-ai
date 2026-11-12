package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val ok: Boolean, val text: String)

/** An expected, explainable failure: the message goes back to the model as the tool's result. */
class ToolFailure(message: String) : Exception(message)

interface ToolRunner {
    fun schema(): JSONArray
    fun execute(name: String, args: JSONObject): ToolResult

    /** Checked before the person is asked to approve: a reason to refuse right away (so no approval is wasted), or null. */
    fun precheck(name: String, args: JSONObject): String? = null
}

interface ToolSettings {
    fun tavilyKey(): String
    fun weatherLocation(): String
}

/** Everything that needs a real phone. The tools above it are plain logic, tested without one. */
interface DeviceApi {
    fun now(): String
    fun battery(): String
    fun wifi(): String
    fun setVolume(level: Int): String
    fun openApp(name: String): String
    fun listApps(search: String): String
    fun openUrl(url: String): String
    fun readClipboard(): String
    fun writeClipboard(text: String): String
    fun vibrate(): String

    /** What is on the screen right now, as text (needs root). Throws with a readable reason. */
    fun readScreen(): String
}

object AppMatch {
    /** Best launcher app for what the user said: exact name, then name starts with it, then contains it. Apps are (label, package). */
    fun find(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.first.lowercase() == q }?.let { return it }
        apps.filter { it.first.lowercase().startsWith(q) }.minByOrNull { it.first.length }?.let { return it }
        apps.filter { it.first.lowercase().contains(q) }.minByOrNull { it.first.length }?.let { return it }
        return apps.filter { q.contains(it.first.lowercase()) && it.first.length >= 3 }.maxByOrNull { it.first.length }
    }
}

private class Param(val name: String, val type: String, val description: String)

class Tools(
    private val device: DeviceApi,
    private val transport: Transport,
    private val settings: ToolSettings,
    private val memory: MemoryStore? = null,
    private val userTools: UserTools? = null,
    private val eyes: Eyes? = null,
    private val hands: Hands? = null,
    private val scheduler: SchedulerApi? = null,
    private val switches: Switches? = null,
    private val liveControl: LiveControl? = null,
    private val stage: StageControl? = null,
) : ToolRunner {

    private fun tool(name: String, description: String, vararg params: Param): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for (p in params) {
            props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
            required.put(p.name)
        }
        val parameters = JSONObject().put("type", "object").put("properties", props).put("required", required)
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description).put("parameters", parameters))
    }

    override fun schema(): JSONArray {
        val out = JSONArray()
        out.put(tool("get_current_datetime", "Call this whenever the user asks for date or time. Returns current date and time. No arguments."))
        out.put(tool("get_battery_status", "Call this whenever the user asks about battery, charge level, or power status. Returns battery percentage. No arguments."))
        out.put(tool("set_volume", "Call this whenever the user asks to change, raise, lower, mute, or set phone volume. Sets media volume.",
            Param("level", "integer", "volume level, an integer from 0 to 15")))
        out.put(tool("get_wifi_status", "Call this whenever the user asks about WiFi connection or network status. Returns WiFi state. No arguments."))
        out.put(tool("read_clipboard", "Call this whenever the user asks about clipboard contents. Returns clipboard text. No arguments."))
        out.put(tool("write_clipboard", "Call this whenever the user asks to copy, save, or put text into clipboard. Saves text.",
            Param("text", "string", "the exact text to copy to the clipboard")))
        if (memory != null) out.put(tool("remember", "Save ONE short fact about the user for future chats (a lasting preference, name, plan or habit). Call it only when the user asks you to remember something or clearly states something lasting about themselves. Never save secrets, passwords, keys or numbers.", Param("fact", "string", "The fact, as one short sentence"), Param("category", "string", "Where it belongs, such as me, work/faze or people/sister. Use me for facts about the user. A new name creates a new category.")))
        if (memory != null) out.put(tool("recall", "Read the saved notes of one memory category (and its sub-categories), or list the categories with 'index'. Call it before answering questions about a category you were not shown.", Param("category", "string", "A category path such as work/faze, or index")))
        if (userTools != null) {
            out.put(tool("create_tool", "Make a new reusable tool when the user asks for something none of your tools can do and a short JavaScript function could (for example: fetch data from a public web API and reshape it). The code must define function run(args) returning a string or value. Plain JavaScript only (no libraries, no async), plus http_get(url) (https, only the sites you list in domains), now() and log(). For more, declare powers: network (http_get to any https site, and http_post(url, body, contentType)), shell (shell(command) runs sh and returns the output), root (root(command) runs su), files (read_file(path), write_file(path, text), list_dir(path)), screenshot (screenshot() saves a PNG and returns its path), intent (start_intent(json) starts an Android intent: action, data, type, package, class, extras). sleep(ms) works with any power. A tool can only call the functions of the powers it declared. The user reads the code and the powers and approves them once; after that the tool runs without asking.",
                Param("name", "string", "lower_case_name, 3 to 31 characters"), Param("description", "string", "what the tool does, one sentence"),
                Param("parameters", "string", "comma separated, like city:string, days:number (may be empty)"), Param("code", "string", "the JavaScript, defining function run(args)"),
                Param("domains", "string", "comma separated sites http_get may reach, like api.open-meteo.com (empty if none, or if you declare the network power)"),
                Param("powers", "string", "comma separated powers from: network, shell, root, files, screenshot, intent (empty if the tool needs none)")))
            for (s in userTools.schemas()) out.put(s)
        }
        out.put(tool("vibrate", "Call this whenever the user asks to vibrate the phone or get attention physically. No arguments."))
        out.put(tool("search_web", "Call this for current, real-world information like news or live facts. Performs a web search. Never answer from memory.",
            Param("query", "string", "what to search for")))
        out.put(tool("open_url", "Call this when the user asks to open a link or website. Opens the http(s) link on the device.",
            Param("url", "string", "the full link, starting with https://")))
        out.put(tool("open_app", "Call this when the user asks to open, launch or start an app (YouTube, Spotify, Chrome, Settings...). Launches it by name.",
            Param("app", "string", "the app's name, for example YouTube or Spotify")))
        out.put(tool("list_apps", "Call this to see which apps are installed on the phone, optionally filtered by name. Use it when open_app cannot find an app.",
            Param("search", "string", "part of an app's name to filter by, or a single space for every app")))
        out.put(tool("get_weather", "Call this for the current weather or today's temperature, rain chance and conditions in a place. Fast and reliable; prefer it over search_web for weather. Pass an empty city for the user's default place.",
            Param("city", "string", "the place to get the weather for, for example Lagos; an empty string means the user's default place")))
        out.put(tool("read_screen", "Call this to see what is on the phone's screen right now: which app is in front and its visible text, buttons and fields, each with the point to tap. Use it when the user asks what is on the screen, asks you to read something in another app, or before you try to do something in an app. No arguments."))
        if (eyes != null) out.put(tool("look_at_screen", "Call this to LOOK at the phone's screen with a vision model and answer a question about it: pictures, games, video, charts, or an app whose text read_screen cannot see. Slower than read_screen, so try read_screen first for plain text and buttons. The screenshot is sent to a cloud model.",
            Param("question", "string", "what to find out, for example: what is in this picture, or which button looks like Send")))
        if (switches != null) {
            out.put(tool("set_mobile_data", "Call this when the user asks to turn mobile data (cellular data) on or off right now. Works with the screen locked.", Param("enabled", "boolean", "true to turn it on, false to turn it off")))
            out.put(tool("set_wifi", "Call this when the user asks to turn Wi-Fi on or off right now. Works with the screen locked.", Param("enabled", "boolean", "true to turn it on, false to turn it off")))
            out.put(tool("set_bluetooth", "Call this when the user asks to turn Bluetooth on or off right now. Works with the screen locked.", Param("enabled", "boolean", "true to turn it on, false to turn it off")))
        }
        if (scheduler != null && switches != null) {
            out.put(tool("schedule_action", "Flip a switch at a set time, even with the phone locked and with no conversation: use for 'turn off my mobile data at 11pm', 'turn Wi-Fi on at 7am', 'turn Bluetooth off in an hour'. action is set_mobile_data, set_wifi or set_bluetooth. Give EITHER in_minutes OR at (HH:mm 24 hour clock, the next time it is that time); put 0 in in_minutes or an empty string in at for the one you do not use.",
                Param("action", "string", "set_mobile_data, set_wifi or set_bluetooth"),
                Param("enabled", "boolean", "true to turn it on, false to turn it off"),
                Param("in_minutes", "integer", "Minutes from now, or 0 when using at"),
                Param("at", "string", "Clock time HH:mm on a 24 hour clock, or empty when using in_minutes"),
                Param("repeat_minutes", "integer", "0 to do it once, otherwise how often: 1440 daily. At least 15")))
        }
        if (scheduler != null) {
            out.put(tool("schedule_task", "Run something later or on repeat. Use when the user says things like 'remind me in 20 minutes', 'every morning at 7 tell me the weather', 'check my battery every hour'. Give EITHER in_minutes OR at (HH:mm 24 hour clock, the next time it is that time); put 0 in in_minutes or an empty string in at for the one you do not use. The task is then run as if the user had typed it, at that time. Write the task as the instruction to follow later.",
                Param("task", "string", "What to do at that time, as a short instruction"),
                Param("in_minutes", "integer", "Minutes from now, or 0 when using at"),
                Param("at", "string", "Clock time HH:mm on a 24 hour clock, or empty when using in_minutes"),
                Param("repeat_minutes", "integer", "0 to run once, otherwise how often to repeat: 60 hourly, 1440 daily. At least 15")))
            out.put(tool("list_scheduled", "Call this when the user asks what is scheduled or what reminders they have. No arguments."))
            out.put(tool("cancel_scheduled", "Cancel a scheduled task. Use list_scheduled first if you do not know its id.", Param("id", "string", "The task id, like t1")))
        }
        if (stage != null) {
            out.put(tool("show_visual", "Show something on the live screen: a diagram, chart, table, illustration, code or a small interactive demo. Use it whenever seeing it helps more than hearing it (explaining how something works, comparing options, showing code or a plan). Write ONE complete self-contained HTML page: inline CSS and JavaScript only, SVG is fine, no links, no web fonts, no images from the web (data: images only). It is shown on a dark phone-sized stage, so use large readable text and a layout that fits a narrow screen. Calling it again replaces what is shown. Keep speaking short while it is shown.",
                Param("title", "string", "A few words naming it"),
                Param("html", "string", "The complete HTML page")))
            out.put(tool("clear_visual", "Take the picture off the live screen when it is no longer needed. No arguments."))
        }
        if (liveControl != null) out.put(tool("end_live", "Call this when the user asks to end, stop or leave live mode or the voice chat (for example: end live, stop the call, bye). Say a short goodbye in the same reply; the conversation closes after you finish speaking. No arguments."))
        if (hands != null) {
            out.put(tool("tap", "Call this to tap the screen at a point. Read the screen first (read_screen) to get the point of the button or field. Then read it again to check what happened.",
                Param("x", "integer", "pixels from the left edge"), Param("y", "integer", "pixels from the top edge")))
            out.put(tool("long_press", "Call this to press and hold a point on the screen (opens menus, selects text). Same points as tap.",
                Param("x", "integer", "pixels from the left edge"), Param("y", "integer", "pixels from the top edge")))
            out.put(tool("swipe", "Call this to swipe or scroll: drag from one point to another. To scroll a list down, swipe from a lower point up to a higher one.",
                Param("x1", "integer", "start, pixels from the left"), Param("y1", "integer", "start, pixels from the top"),
                Param("x2", "integer", "end, pixels from the left"), Param("y2", "integer", "end, pixels from the top")))
            out.put(tool("type_text", "Call this to type text into the field that is focused (tap the field first). Up to ${InputCommands.MAX_TEXT} characters.",
                Param("text", "string", "the text to type")))
            out.put(tool("press_key", "Call this to press a key. Keys: ${InputCommands.KEYS.keys.joinToString(", ")}.",
                Param("key", "string", "one of the keys listed")))
        }
        return out
    }

    fun names(): List<String> {
        val s = schema()
        return (0 until s.length()).map { s.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    override fun precheck(name: String, args: JSONObject): String? = when {
        name != "create_tool" -> null
        userTools == null -> "tools can't be made here"
        else -> userTools.precheck(args)
    }

    override fun execute(name: String, args: JSONObject): ToolResult {
        return try {
            when (name) {
                "remember" -> {
                    val r = memory?.add(args.optString("fact", ""), args.optString("category", "")) ?: MemoryResult(false, "memory is not available")
                    if (r.ok) ok(r.message) else fail(r.message)
                }
                "recall" -> if (memory == null) fail("memory is not available") else ok(memory.recall(args.optString("category", "")))
                "create_tool" -> userTools?.create(args) ?: fail("tools can't be made here")
                "get_current_datetime" -> ok(device.now())
                "get_battery_status" -> ok(device.battery())
                "get_wifi_status" -> ok(device.wifi())
                "read_clipboard" -> ok(device.readClipboard())
                "vibrate" -> ok(device.vibrate())
                "write_clipboard" -> {
                    val text = args.optString("text", "")
                    if (text.isEmpty()) fail("there is nothing to copy") else ok(device.writeClipboard(text))
                }
                "set_volume" -> {
                    if (!args.has("level")) fail("level is required, an integer from 0 to 15")
                    else ok(device.setVolume(args.optInt("level", 0).coerceIn(0, 15)))
                }
                "open_url" -> {
                    val url = args.optString("url", "").trim()
                    if (!url.startsWith("https://") && !url.startsWith("http://")) fail("the link must start with https:// or http://")
                    else ok(device.openUrl(url))
                }
                "open_app" -> {
                    val app = args.optString("app", "").trim()
                    if (app.isEmpty()) fail("which app?") else ok(device.openApp(app))
                }
                "list_apps" -> ok(device.listApps(args.optString("search", "")))
                "read_screen" -> ok(device.readScreen())
                "set_mobile_data" -> switches?.let { ok(it.mobileData(flag(args))) } ?: fail("not available")
                "set_wifi" -> switches?.let { ok(it.wifi(flag(args))) } ?: fail("not available")
                "set_bluetooth" -> switches?.let { ok(it.bluetooth(flag(args))) } ?: fail("not available")
                "schedule_action" -> if (scheduler == null || switches == null) fail("scheduling is not available") else {
                    val tool = args.optString("action", "").trim()
                    if (tool !in ScheduledActions.ALLOWED) throw ToolFailure("action must be one of: ${ScheduledActions.ALLOWED.joinToString(", ")}")
                    val enabled = flag(args)
                    val inMin = args.optInt("in_minutes", 0).takeIf { it > 0 }
                    val whenAt = ScheduleTime.resolve(inMin, args.optString("at", "").ifBlank { null }, scheduler.now(), scheduler.zone())
                    val every = ScheduleTime.repeat(args.optInt("repeat_minutes", 0))
                    val label = ScheduledActions.label(tool, enabled)
                    val task = scheduler.add(label, whenAt, every, ScheduledAction(tool, enabled))
                    ok("Scheduled ${task.id}: $label at ${ScheduleTime.describe(task.nextAt, scheduler.now(), scheduler.zone())}, ${ScheduleTime.everyText(every)}. It runs by itself, even with the phone locked.")
                }
                "schedule_task" -> scheduler?.let { s ->
                    val inMin = args.optInt("in_minutes", 0).takeIf { it > 0 }
                    val at = args.optString("at", "").ifBlank { null }
                    val whenAt = ScheduleTime.resolve(inMin, at, s.now(), s.zone())
                    val every = ScheduleTime.repeat(args.optInt("repeat_minutes", 0))
                    val task = s.add(args.optString("task", ""), whenAt, every)
                    ok("Scheduled ${task.id}: \"${task.prompt}\" at ${ScheduleTime.describe(task.nextAt, s.now(), s.zone())}, ${ScheduleTime.everyText(task.everyMinutes)}.")
                } ?: fail("scheduling is not available")
                "list_scheduled" -> scheduler?.let { s ->
                    val items = s.list()
                    if (items.isEmpty()) ok("Nothing is scheduled.")
                    else ok(items.joinToString("\n") { "${it.id}: \"${it.prompt}\" next at ${ScheduleTime.describe(it.nextAt, s.now(), s.zone())}, ${ScheduleTime.everyText(it.everyMinutes)}" })
                } ?: fail("scheduling is not available")
                "cancel_scheduled" -> scheduler?.let { s ->
                    val id = args.optString("id", "")
                    if (s.cancel(id)) ok("Cancelled $id.") else fail("there is no scheduled task $id")
                } ?: fail("scheduling is not available")
                "show_visual" -> stage?.show(args.optString("title", "").trim(), args.optString("html", ""))?.let { ok(it) } ?: fail("the stage is not available")
                "clear_visual" -> stage?.let { it.clear(); ok("The stage is clear.") } ?: fail("the stage is not available")
                "end_live" -> liveControl?.let { if (it.active()) { it.endAfterSpeech(); ok("Ending after the goodbye.") } else fail("there is no live or voice chat running") } ?: fail("not available")
                "tap" -> hands?.let { ok(it.tap(int(args, "x"), int(args, "y"))) } ?: fail("controlling the screen is not available")
                "long_press" -> hands?.let { ok(it.longPress(int(args, "x"), int(args, "y"))) } ?: fail("controlling the screen is not available")
                "swipe" -> hands?.let { ok(it.swipe(int(args, "x1"), int(args, "y1"), int(args, "x2"), int(args, "y2"))) } ?: fail("controlling the screen is not available")
                "type_text" -> hands?.let { ok(it.typeText(args.optString("text", ""))) } ?: fail("controlling the screen is not available")
                "press_key" -> hands?.let { ok(it.pressKey(args.optString("key", ""))) } ?: fail("controlling the screen is not available")
                "look_at_screen" -> if (eyes == null) fail("looking at the screen is not available") else ok(eyes.look(args.optString("question", "")))
                "get_weather" -> {
                    val city = args.optString("city", "").trim().ifEmpty { settings.weatherLocation().trim() }
                    ok(Weather.get(transport, city))
                }
                "search_web" -> {
                    val query = args.optString("query", "").trim()
                    if (query.isEmpty()) fail("what should I search for?") else ok(Search.tavily(transport, settings.tavilyKey(), query))
                }
                else -> if (userTools != null && userTools.has(name)) userTools.run(name, args) else fail("unknown tool: $name")
            }
        } catch (e: ToolFailure) {
            fail(e.message ?: "$name failed")
        } catch (e: Exception) {
            fail("$name failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun int(args: JSONObject, name: String): Int {
        if (!args.has(name) || args.isNull(name)) throw ToolFailure("$name is required (a whole number of pixels)")
        return args.optInt(name, Int.MIN_VALUE).also { if (it == Int.MIN_VALUE) throw ToolFailure("$name must be a whole number") }
    }

    private fun flag(args: JSONObject): Boolean {
        if (!args.has("enabled") || args.isNull("enabled")) throw ToolFailure("enabled is required (true or false)")
        return args.optBoolean("enabled")
    }

    private fun ok(text: String) = ToolResult(true, text)

    private fun fail(text: String) = ToolResult(false, text)
}
