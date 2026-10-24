package com.programmieramt.olleebadge

/**
 * Chat markers: while a named chat has a notification, the watch shows that chat's two characters
 * instead of the count. Written one per line as "Name = XY".
 */
object Markers {
    class Rule(val name: String, val cell: String)

    fun parse(text: String): List<Rule> = text.lines().mapNotNull { line ->
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[0].trim()
        val cell = parts[1].trim().take(2)
        if (name.isEmpty() || cell.isEmpty()) null else Rule(name, cell)
    }

    /**
     * The cell for a chat called [chatName]: the first rule whose name is the chat name, or its
     * first word(s) ("Alex" also matches "Alex Smith"). Case doesn't matter.
     */
    fun cellFor(rules: List<Rule>, chatName: String?): String? {
        val chat = chatName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return rules.firstOrNull {
            chat.equals(it.name, ignoreCase = true) || chat.startsWith(it.name + " ", ignoreCase = true)
        }?.cell
    }
}
