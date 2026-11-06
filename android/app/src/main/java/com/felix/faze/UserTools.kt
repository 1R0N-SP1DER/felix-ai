package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ToolParam(val name: String, val type: String)

data class SavedTool(val name: String, val description: String, val params: List<ToolParam>, val code: String, val domains: List<String>, val created: Long)

/** Checks and builds a tool the model proposes. Returns the tool, or the reason it was refused (the model is told, so it can fix it). */
object ToolSpec {
    const val MAX_TOOLS = 30
    private val secretLike = listOf(Regex("gsk_[A-Za-z0-9]{10,}"), Regex("AIza[A-Za-z0-9_\\-]{20,}"), Regex("sk-[A-Za-z0-9_\\-]{16,}"), Regex("tvly-[A-Za-z0-9]{10,}"))
    private val domainOk = Regex("^[a-z0-9]([a-z0-9.\\-]{0,60}[a-z0-9])?\\.[a-z]{2,}$")

    fun build(name: String, description: String, paramSpec: String, code: String, domainSpec: String, reserved: Set<String>, now: Long): Pair<SavedTool?, String?> {
        val n = name.trim()
        if (!Regex("[a-z][a-z0-9_]{2,30}").matches(n)) return null to "the tool name must be lower case letters, digits and underscores, 3 to 31 characters, starting with a letter"
        if (n in reserved) return null to "'$n' is already a tool; pick another name"
        val desc = description.trim().replace(Regex("\\s+"), " ")
        if (desc.length < 10 || desc.length > 200) return null to "the description must be 10 to 200 characters and say what the tool does"
        val params = ArrayList<ToolParam>()
        for (part in paramSpec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val bits = part.split(':').map { it.trim() }
            val type = bits.getOrNull(1)?.lowercase() ?: "string"
            if (bits.size > 2 || !Regex("[a-z][A-Za-z0-9_]{0,20}").matches(bits[0]) || type !in setOf("string", "number", "boolean")) return null to "parameters must look like 'city:string, days:number' (types: string, number, boolean)"
            if (params.any { it.name == bits[0] }) return null to "parameter '${bits[0]}' is listed twice"
            params.add(ToolParam(bits[0], type))
        }
        if (params.size > 5) return null to "a tool can have at most 5 parameters"
        val src = code.trim()
        if (src.length < 20 || src.length > 6000) return null to "the code must be 20 to 6000 characters"
        if (!Regex("function\\s+run\\s*\\(").containsMatchIn(src)) return null to "the code must define function run(args) that returns the result"
        if (secretLike.any { it.containsMatchIn(src) }) return null to "the code contains something that looks like a key; never put keys in a tool"
        val domains = domainSpec.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (domains.size > 5) return null to "a tool can reach at most 5 sites"
        for (d in domains) if (!domainOk.matches(d) || Regex("^[0-9.]+$").matches(d)) return null to "'$d' is not a valid site name (use names like api.open-meteo.com)"
        if (src.contains("http_get") && domains.isEmpty()) return null to "the code calls http_get, so list the sites it may reach in 'domains'"
        return SavedTool(n, desc, params, src, domains, now) to null
    }
}

/** The tools the model made and you approved: one small file in the app's private folder. */
class ToolStore(private val file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    @Synchronized
    fun list(): List<SavedTool> {
        if (!file.isFile) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val ps = o.optJSONArray("params") ?: JSONArray()
                val ds = o.optJSONArray("domains") ?: JSONArray()
                SavedTool(
                    o.getString("name"), o.getString("description"),
                    (0 until ps.length()).map { i -> ToolParam(ps.getJSONObject(i).getString("name"), ps.getJSONObject(i).getString("type")) },
                    o.getString("code"), (0 until ds.length()).map { i -> ds.getString(i) }, o.optLong("created", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(items: List<SavedTool>) {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        for (t in items) a.put(
            JSONObject().put("name", t.name).put("description", t.description).put("code", t.code).put("created", t.created)
                .put("params", JSONArray().also { p -> t.params.forEach { p.put(JSONObject().put("name", it.name).put("type", it.type)) } })
                .put("domains", JSONArray(t.domains)),
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun get(name: String): SavedTool? = list().firstOrNull { it.name == name }

    fun has(name: String): Boolean = get(name) != null

    /** Saves a tool (replacing one with the same name). False if there are already too many. */
    @Synchronized
    fun save(tool: SavedTool): Boolean {
        val items = list().filter { it.name != tool.name }
        if (items.size >= ToolSpec.MAX_TOOLS) return false
        write(items + tool)
        return true
    }

    @Synchronized
    fun remove(name: String) { write(list().filter { it.name != name }) }

    @Synchronized
    fun clear() { file.delete() }
}

/** Ties the store and the sandbox to the tool list the model sees. */
class UserTools(private val store: ToolStore, private val sandbox: JsSandbox, private val reserved: () -> Set<String>) {
    fun has(name: String): Boolean = store.has(name)

    private fun spec(args: JSONObject, now: Long) = ToolSpec.build(
        args.optString("name"), args.optString("description"), args.optString("parameters"), args.optString("code"), args.optString("domains"), reserved(), now,
    )

    /** Everything that can be checked before the person is asked: the fields, and that the code compiles. Null if fine. */
    fun precheck(args: JSONObject): String? {
        val (tool, error) = spec(args, 0)
        if (tool == null) return error
        return sandbox.syntaxProblem(tool.code)
    }

    fun create(args: JSONObject): ToolResult {
        val (tool, error) = spec(args, System.currentTimeMillis())
        if (tool == null) return ToolResult(false, error ?: "invalid tool")
        if (!store.save(tool)) return ToolResult(false, "there are already ${ToolSpec.MAX_TOOLS} saved tools; delete some in Settings first")
        return ToolResult(true, "saved the tool ${tool.name}; you can call it now")
    }

    fun run(name: String, args: JSONObject): ToolResult {
        val tool = store.get(name) ?: return ToolResult(false, "no such tool: $name")
        return try {
            ToolResult(true, sandbox.run(tool.code, args.toString(), tool.domains))
        } catch (e: SandboxError) {
            ToolResult(false, e.message ?: "the tool failed")
        }
    }

    /** One OpenAI-style function entry per saved tool. */
    fun schemas(): List<JSONObject> = store.list().map { t ->
        val props = JSONObject()
        for (p in t.params) props.put(p.name, JSONObject().put("type", p.type))
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", t.name).put("description", t.description + (if (t.domains.isEmpty()) "" else " (reaches ${t.domains.joinToString(", ")})"))
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(t.params.map { it.name }))),
        )
    }
}
