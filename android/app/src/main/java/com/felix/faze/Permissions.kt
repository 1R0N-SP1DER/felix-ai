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
        "get_weather" to Tier.SAFE,
        "remember" to Tier.CONFIRM,
    )

    fun tier(tool: String): Tier = registry[tool] ?: Tier.BLOCK

    /** Every tool the app knows, with its tier (the sidebar's Tools list). */
    fun all(): List<Pair<String, Tier>> = registry.toList()

    fun summary(tool: String, args: JSONObject): String = when (tool) {
        "read_clipboard" -> "Read what is on your clipboard and show it to the model"
        "write_clipboard" -> "Put this on your clipboard: " + args.optString("text", "").replace('\n', ' ').take(80)
        "remember" -> "Remember this: " + args.optString("fact", "").replace('\n', ' ').take(120)
        else -> tool
    }
}

/** Asks the person. Blocks the calling (background) thread until they answer. Any failure counts as no. */
interface Approver {
    fun approve(tool: String, summary: String): Boolean
}

/** Wraps a [ToolRunner]: safe tools run, confirm tools wait for the person, everything else is refused. */
class GatedTools(private val inner: ToolRunner, private val approver: Approver) : ToolRunner {
    override fun schema(): JSONArray = inner.schema()

    override fun execute(name: String, args: JSONObject): ToolResult = when (PermissionPolicy.tier(name)) {
        Tier.SAFE -> inner.execute(name, args)
        Tier.BLOCK -> ToolResult(false, "$name is not allowed.")
        Tier.CONFIRM -> {
            val allowed = try {
                approver.approve(name, PermissionPolicy.summary(name, args))
            } catch (e: Exception) {
                false
            }
            if (allowed) inner.execute(name, args) else ToolResult(false, "The user declined $name. Do not try it again.")
        }
    }
}

data class ApprovalRequest(val id: Long, val tool: String, val summary: String)

/** Connects [GatedTools] (worker thread) to the approval card (screen). One request at a time; no answer in time means no. */
class ApprovalBroker(private val ui: (() -> Unit) -> Unit, private val timeoutSeconds: Long = 60, private val onChange: (ApprovalRequest?) -> Unit) : Approver {
    private val lock = Any()
    private var nextId = 1L
    private var pending: Pair<ApprovalRequest, CompletableFuture<Boolean>>? = null

    override fun approve(tool: String, summary: String): Boolean {
        val request: ApprovalRequest
        val future = CompletableFuture<Boolean>()
        synchronized(lock) {
            pending?.second?.complete(false) // a second request while one waits: the older one is refused
            request = ApprovalRequest(nextId++, tool, summary)
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
