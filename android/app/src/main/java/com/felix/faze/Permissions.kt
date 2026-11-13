package com.felix.faze

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

enum class Tier { SAFE, CONFIRM, BLOCK }

/** Same tiers as the Termux version (permission_policy.py). A tool nobody registered is blocked, never run. */
object PermissionPolicy {
    private val registry = mapOf(
        "get_current_datetime" to Tier.SAFE,
        "get_battery_status" to Tier.SAFE,
        "set_volume" to Tier.SAFE,
        "get_wifi_status" to Tier.SAFE,
        "read_clipboard" to Tier.CONFIRM,
        "write_clipboard" to Tier.CONFIRM,
        "vibrate" to Tier.SAFE,
        "search_web" to Tier.SAFE,
        "open_url" to Tier.SAFE,
        "open_app" to Tier.SAFE,
        "list_apps" to Tier.SAFE,
        "read_screen" to Tier.CONFIRM,
        "look_at_screen" to Tier.CONFIRM,
        "generate_image" to Tier.CONFIRM,
        "find_images" to Tier.SAFE,
        "set_mobile_data" to Tier.CONFIRM,
        "set_wifi" to Tier.CONFIRM,
        "set_bluetooth" to Tier.CONFIRM,
        "schedule_action" to Tier.CONFIRM,
        "schedule_task" to Tier.CONFIRM,
        "list_scheduled" to Tier.SAFE,
        "cancel_scheduled" to Tier.SAFE,
        "end_live" to Tier.SAFE,
        "tap" to Tier.CONFIRM,
        "long_press" to Tier.CONFIRM,
        "swipe" to Tier.CONFIRM,
        "type_text" to Tier.CONFIRM,
        "press_key" to Tier.CONFIRM,
        "get_weather" to Tier.SAFE,
        "remember" to Tier.CONFIRM,
        "recall" to Tier.SAFE,
        "create_tool" to Tier.CONFIRM,
    )

    fun tier(tool: String): Tier = registry[tool] ?: Tier.BLOCK

    /** Tools that move the screen for you. One yes covers all of them for [CONTROL_MINUTES] minutes, so a task of many taps is not many questions. */
    const val DECLINED = "The user declined"

    val controls = setOf("tap", "long_press", "swipe", "type_text", "press_key")

    /** Everything that looks at or drives the screen: the "Phone control" setting decides about these. */
    val phoneTools = controls + setOf("read_screen", "look_at_screen")

    /** Settings > Approval mode: ask (default) or yolo (every tool that would ask runs at once). */
    fun approvalMode(v: String): String = if (v == "yolo") "yolo" else "ask"

    /** Settings > Phone control: off (refused), ask (one yes covers a couple of minutes) or on (always allowed). */
    fun phoneMode(v: String): String = if (v == "off" || v == "on") v else "ask"
    const val CONTROL_MINUTES = 2L

    /** Every tool the app knows, with its tier (the sidebar's Tools list). */
    fun all(): List<Pair<String, Tier>> = registry.toList()

    private fun powersOf(args: JSONObject): String = args.optString("powers").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

    private fun whenText(args: JSONObject): String {
        val inMin = args.optInt("in_minutes", 0)
        val at = args.optString("at", "").trim()
        val first = if (inMin > 0) "in $inMin minutes" else if (at.isNotEmpty()) "at $at" else "at the time given"
        val every = args.optInt("repeat_minutes", 0)
        return if (every > 0) first + ", then " + ScheduleTime.everyText(every) else first
    }

    fun summary(tool: String, args: JSONObject): String = when (tool) {
        "read_clipboard" -> "Read what is on your clipboard and show it to the model"
        "tap", "long_press", "swipe", "type_text", "press_key" -> "Control your screen for $CONTROL_MINUTES minutes (tap, swipe, type, press keys). First: " + when (tool) {
            "tap" -> "tap at ${args.optInt("x")},${args.optInt("y")}"
            "long_press" -> "press and hold at ${args.optInt("x")},${args.optInt("y")}"
            "swipe" -> "swipe from ${args.optInt("x1")},${args.optInt("y1")} to ${args.optInt("x2")},${args.optInt("y2")}"
            "type_text" -> "type \"" + args.optString("text", "").replace('\n', ' ').take(60) + "\""
            else -> "press " + args.optString("key", "")
        }
        "set_mobile_data", "set_wifi", "set_bluetooth" -> ScheduledActions.label(tool, args.optBoolean("enabled")) + " now"
        "schedule_action" -> ScheduledActions.label(args.optString("action"), args.optBoolean("enabled")) + " " + whenText(args) + " (runs by itself, even with the phone locked)"
        "schedule_task" -> "Run this " + whenText(args) + ": " + args.optString("task", "").replace('\n', ' ').take(120)
        "read_screen" -> "Read what is on your screen and show it to the model"
        "look_at_screen" -> "Take a screenshot and send it to the vision model" + (args.optString("question", "").trim().let { if (it.isEmpty()) "" else ": " + it.replace('\n', ' ').take(100) })
        "write_clipboard" -> "Put this on your clipboard: " + args.optString("text", "").replace('\n', ' ').take(80)
        "remember" -> "Remember this" + (args.optString("category", "").trim().let { if (it.isEmpty()) "" else " [$it]" }) + ": " + args.optString("fact", "").replace('\n', ' ').take(120)
        "create_tool" -> "Create the tool ${args.optString("name")}${powersOf(args).let { if (it.isEmpty()) "" else " (powers: $it)" }}: ${args.optString("description").replace('\n', ' ').take(120)}"
        else -> tool
    }

    /** Extra text for the approval dialog: for a new tool, its parameters, the sites it may reach, and the code itself. */
    fun detail(tool: String, args: JSONObject): String = when (tool) {
        "tap", "long_press", "swipe", "type_text", "press_key" -> "After you allow this, FAZE can tap, swipe, type and press keys on your screen without asking again for $CONTROL_MINUTES minutes. Press Stop to take it back."
        "create_tool" -> "Parameters: ${args.optString("parameters").ifBlank { "none" }}\nMay reach: ${args.optString("domains").ifBlank { "no websites" }}\nPowers: ${powersOf(args).ifEmpty { "none (locked sandbox)" }}\n\n${args.optString("code")}"
        else -> ""
    }
}

/** Asks the person. Blocks the calling (background) thread until they answer. Any failure counts as no. */
interface Approver {
    fun approve(tool: String, summary: String): Boolean

    /** Same question with extra text to show (the code of a new tool). Plain approvers can ignore the extra text. */
    fun approve(tool: String, summary: String, detail: String): Boolean = approve(tool, summary)
}

/** Wraps a [ToolRunner]: safe tools run, confirm tools wait for the person, everything else is refused. */
class GatedTools(
    private val inner: ToolRunner,
    private val approver: Approver,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val approvalMode: () -> String = { "ask" },
    private val phoneControl: () -> String = { "ask" },
    private val extraTier: (String) -> Tier? = { null },
) : ToolRunner {
    @Volatile private var controlUntil = 0L

    /** Takes back the "control the screen" yes (the Stop button). */
    fun revokeControl() { controlUntil = 0L }

    override fun schema(): JSONArray = inner.schema()

    override fun precheck(name: String, args: JSONObject): String? = inner.precheck(name, args)

    /** Built-in tools have fixed tiers; a tool the person approved earlier (see [extraTier]) may run; anything else is refused. */
    private fun tierOf(name: String): Tier = PermissionPolicy.tier(name).let { if (it == Tier.BLOCK) extraTier(name) ?: it else it }

    /** Called with a one-line summary after every tool the gate runs, declined ones included. */
    @Volatile var logger: ((String) -> Unit)? = null

    override fun execute(name: String, args: JSONObject): ToolResult {
        val r = run(name, args)
        try { logger?.invoke(ActivityLog.summary(name, args, r)) } catch (e: Exception) { /* logging never breaks a tool */ }
        return r
    }

    private fun run(name: String, args: JSONObject): ToolResult = when (tierOf(name)) {
        Tier.SAFE -> inner.execute(name, args)
        Tier.BLOCK -> ToolResult(false, "$name is not allowed.")
        Tier.CONFIRM -> {
            val refusal = inner.precheck(name, args)
            val phone = if (name in PermissionPolicy.phoneTools) PermissionPolicy.phoneMode(phoneControl()) else "ask"
            if (phone == "off") {
                ToolResult(false, "Phone control is turned off in Settings, so $name is not allowed. Tell the user they can turn it on in Settings > Agent > Phone control.")
            } else if (refusal != null) {
                ToolResult(false, refusal) // bad arguments are refused in every mode; nothing was shown to the person
            } else if (phone == "on" || PermissionPolicy.approvalMode(approvalMode()) == "yolo") {
                inner.execute(name, args) // the person chose "no questions" in Settings
            } else if (name in PermissionPolicy.controls && clock() < controlUntil) {
                inner.execute(name, args) // the person said yes a moment ago to controlling the screen
            } else if (refusal != null) {
                ToolResult(false, refusal) // fix it and try again; nothing was shown to the person
            } else {
                val allowed = try {
                    approver.approve(name, PermissionPolicy.summary(name, args), PermissionPolicy.detail(name, args))
                } catch (e: Exception) {
                    false
                }
                if (allowed && name in PermissionPolicy.controls) controlUntil = clock() + PermissionPolicy.CONTROL_MINUTES * 60_000
                if (allowed) inner.execute(name, args) else ToolResult(false, "${PermissionPolicy.DECLINED} $name. Do not try it again.")
            }
        }
    }
}

data class ApprovalRequest(val id: Long, val tool: String, val summary: String, val detail: String = "")

/** Connects [GatedTools] (worker thread) to the approval card (screen). One request at a time; no answer in time means no. */
class ApprovalBroker(private val ui: (() -> Unit) -> Unit, private val timeoutSeconds: Long = 60, private val onChange: (ApprovalRequest?) -> Unit) : Approver {
    private val lock = Any()
    private var nextId = 1L
    private var pending: Pair<ApprovalRequest, CompletableFuture<Boolean>>? = null

    override fun approve(tool: String, summary: String): Boolean = approve(tool, summary, "")

    override fun approve(tool: String, summary: String, detail: String): Boolean {
        val request: ApprovalRequest
        val future = CompletableFuture<Boolean>()
        synchronized(lock) {
            pending?.second?.complete(false) // a second request while one waits: the older one is refused
            request = ApprovalRequest(nextId++, tool, summary, detail)
            pending = Pair(request, future)
        }
        ui { onChange(request) }
        val answer = try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
        synchronized(lock) {
            if (pending?.first?.id == request.id) pending = null
        }
        ui { onChange(null) }
        return answer
    }

    /** Called by the buttons. An old or unknown id is ignored. */
    fun resolve(id: Long, allow: Boolean) {
        synchronized(lock) {
            val p = pending ?: return
            if (p.first.id == id) p.second.complete(allow)
        }
    }

    /** Stop pressed or the screen is going away: whatever waits is refused. */
    fun denyAll() {
        synchronized(lock) { pending?.second?.complete(false) }
    }
}
