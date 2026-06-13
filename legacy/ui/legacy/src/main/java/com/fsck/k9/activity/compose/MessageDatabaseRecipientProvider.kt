package com.fsck.k9.activity.compose

import app.k9mail.legacy.mailstore.MessageListRepository
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class DatabaseRecipient(val name: String?, val email: String)

/**
 * Provides recipient suggestions taken from the addresses seen in previously sent/received messages, so the
 * compose recipient field can suggest people the user has corresponded with even if they are not in the phone's
 * address book.
 */
class MessageDatabaseRecipientProvider : KoinComponent {
    private val accountManager: LegacyAccountDtoManager by inject()
    private val messageListRepository: MessageListRepository by inject()

    fun getRecipients(query: String): List<DatabaseRecipient> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < MIN_QUERY_LENGTH) return emptyList()

        val escapedQuery = trimmedQuery
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        val likeArg = "%$escapedQuery%"
        val selection = "(sender_list LIKE ? ESCAPE '!' OR to_list LIKE ? ESCAPE '!' OR cc_list LIKE ? ESCAPE '!')"
        val selectionArgs = arrayOf(likeArg, likeArg, likeArg)

        val result = LinkedHashMap<String, DatabaseRecipient>()
        for (account in accountManager.getAccounts()) {
            val addressLists = messageListRepository.getMessages(
                accountUuid = account.uuid,
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = "date DESC LIMIT $PER_ACCOUNT_MESSAGE_LIMIT",
            ) { message -> message.fromAddresses + message.toAddresses + message.ccAddresses }

            for (address in addressLists.flatten()) {
                val email = address.address
                val key = email.lowercase()
                if (key in result) continue

                val name = address.personal
                val matches = email.contains(trimmedQuery, ignoreCase = true) ||
                    (name?.contains(trimmedQuery, ignoreCase = true) == true)
                if (matches) {
                    result[key] = DatabaseRecipient(name = name, email = email)
                }
            }

            if (result.size >= MAX_RESULTS) break
        }

        return result.values.take(MAX_RESULTS)
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULTS = 10
        private const val PER_ACCOUNT_MESSAGE_LIMIT = 50
    }
}
