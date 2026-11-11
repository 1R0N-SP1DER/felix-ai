package com.felix.faze

/** The words of the approval dialog for one tool, in the shape of the web page's dialog (title, name, level, what, how it works). */
data class ApprovalInfo(val title: String, val name: String, val level: String, val what: String, val how: String)

object ApprovalText {
    fun build(tool: String, summary: String): ApprovalInfo = when (tool) {
        "read_clipboard" -> ApprovalInfo("Let Faze read your clipboard?", tool, "normal", summary, "Reads what you last copied and gives it to the model so it can answer with it. Nothing is changed.")
        "remember" -> ApprovalInfo("Let Faze remember this?", tool, "normal", summary, "Saves it in your memory so future chats can use it. You can read and delete every note in Settings > Agent > Memory.")
        "create_tool" -> ApprovalInfo("Let Faze add a new tool?", tool, "higher-level", summary,
            if (summary.contains("(powers: ")) "Faze wrote the code below and asks for the powers listed above. The tool uses them for real, and once you approve it runs without asking again each time. Read the code and the powers before you approve. You can delete the tool later in Settings > Agent."
            else "Faze wrote the code below. It runs in a locked sandbox: no files, no phone access, and only the websites listed. Read it before you approve. You can delete the tool later in Settings > Agent.")
        "write_clipboard" -> ApprovalInfo("Let Faze change your clipboard?", tool, "normal", summary, "Replaces what you last copied with the text above.")
        "schedule_task" -> ApprovalInfo("Let Faze schedule this?", tool, "normal", summary, "At the time shown, Faze will run the task as if you had typed it, and tell you the result in a notification. Anything that needs your approval then will wait for you. You can see and cancel scheduled tasks in Settings > Agent.")
        else -> ApprovalInfo("Allow this action?", tool, "higher-level", summary, "Runs the tool $tool.")
    }
}
