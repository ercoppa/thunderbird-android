package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.mailstore.MessageListRepository

data class ContactSuggestion(val name: String, val email: String)

/**
 * Provides contact suggestions for the message list search box by looking at the senders of recently
 * received messages.
 */
class ContactSuggestionProvider(private val messageListRepository: MessageListRepository) {

    fun getSuggestions(query: String, accountUuids: List<String>): List<ContactSuggestion> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < MIN_QUERY_LENGTH) return emptyList()

        val escapedQuery = trimmedQuery
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        val selection = "sender_list LIKE ? ESCAPE '!'"
        val selectionArgs = arrayOf("%$escapedQuery%")

        val suggestions = LinkedHashMap<String, ContactSuggestion>()
        for (accountUuid in accountUuids) {
            val senderLists = messageListRepository.getMessages(
                accountUuid,
                selection,
                selectionArgs,
                "date DESC LIMIT $MESSAGE_SCAN_LIMIT",
            ) { message -> message.fromAddresses }

            for (address in senderLists.flatten()) {
                val email = address.address?.lowercase() ?: continue
                if (email in suggestions) continue

                val name = address.personal.orEmpty()
                val matches = email.contains(trimmedQuery, ignoreCase = true) ||
                    name.contains(trimmedQuery, ignoreCase = true)
                if (matches) {
                    suggestions[email] = ContactSuggestion(name = name.ifEmpty { email }, email = email)
                }
            }

            if (suggestions.size >= MAX_SUGGESTIONS) break
        }

        return suggestions.values.take(MAX_SUGGESTIONS)
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_SUGGESTIONS = 10
        const val COLUMN_NAME = "suggestion_name"
        const val COLUMN_EMAIL = "suggestion_email"

        private const val MESSAGE_SCAN_LIMIT = 200
    }
}
