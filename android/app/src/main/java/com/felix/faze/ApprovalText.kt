package com.felix.faze

/** The words of the approval dialog for one tool, in the shape of the web page's dialog (title, name, level, what, how it works). */
data class ApprovalInfo(val title: String, val name: String, val level: String, val what: String, val how: String)

object ApprovalText {
    fun build(tool: String, summary: String): ApprovalInfo = when (tool) {
        "read_clipboard" -> ApprovalInfo("Let Faze read your clipboard?", tool, "normal", summary, "Reads what you last copied and gives it to the model so it can answer with it. Nothing is changed.")
        "remember" -> ApprovalInfo("Let Faze remember this?", tool, "normal", summary, "Saves it in your memory so future chats can use it. You can read and delete every note in Settings > Agent > Memory.")
        "write_clipboard" -> ApprovalInfo("Let Faze change your clipboard?", tool, "normal", summary, "Replaces what you last copied with the text above.")
        else -> ApprovalInfo("Allow this action?", tool, "higher-level", summary, "Runs the tool $tool.")
    }
}
