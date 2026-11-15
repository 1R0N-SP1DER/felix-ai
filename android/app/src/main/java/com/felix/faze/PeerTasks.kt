package com.felix.faze

import org.json.JSONObject

/** What the phone does when a computer hands it a task: it starts a new chat and runs the task there, with all the usual approvals. The answer arrives in that chat, which syncs back. */
class PeerTasks(private val busy: () -> Boolean, private val start: (String) -> Unit) {
    fun handle(msg: JSONObject): JSONObject? {
        if (msg.optString("t") != "phone_task") return null
        val task = msg.optString("task").trim().take(2000)
        if (task.isEmpty()) return JSONObject().put("t", "error").put("message", "no task given")
        if (busy()) return JSONObject().put("t", "error").put("message", "the phone is busy with another chat")
        start(task)
        return JSONObject().put("t", "phone_task_ok").put("note", "started on the phone, see the new chat there")
    }
}
