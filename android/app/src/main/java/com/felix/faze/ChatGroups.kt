package com.felix.faze

data class ChatGroup(val title: String, val items: List<ChatRecord>)

/** The sidebar's grouping, as in groupChats() in core/web/app.js: newest first, bucketed by age, empty groups left out. */
object ChatGroups {
    private const val DAY = 86_400_000L

    /** [startOfToday] is local midnight in milliseconds. A non-empty [query] keeps chats whose title or any message contains it. */
    fun group(chats: List<ChatRecord>, startOfToday: Long, query: String = ""): List<ChatGroup> {
        val q = query.trim().lowercase()
        val sorted = chats
            .filter { c -> q.isEmpty() || c.title.lowercase().contains(q) || c.messages.any { it.text.lowercase().contains(q) } }
            .sortedByDescending { it.updated }
        val buckets = listOf("Today", "Yesterday", "Previous 7 days", "Older").map { it to ArrayList<ChatRecord>() }
        for (c in sorted) {
            val age = startOfToday - c.updated
            val i = if (age <= 0) 0 else if (age <= DAY) 1 else if (age <= 7 * DAY) 2 else 3
            buckets[i].second.add(c)
        }
        return buckets.filter { it.second.isNotEmpty() }.map { ChatGroup(it.first, it.second) }
    }
}
