package com.fsck.k9.notification

/**
 * Remembers the Message-IDs of messages that were removed from the server while already marked as read.
 *
 * Some mail servers (e.g. Exchange with attachment scanning) deliver a message, then later replace it by
 * deleting the original and appending a copy with a new UID. To the sync code the copy looks like a brand-new
 * message, triggering another notification even if the user already read the original. Entries recorded here
 * allow the notification strategy to recognize such re-deliveries and stay silent.
 */
object ReadMessageIdTracker {
    private const val MAX_ENTRIES = 100

    private val messageIds = object : LinkedHashMap<String, Unit>(MAX_ENTRIES, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun rememberReadMessage(messageId: String) {
        messageIds[messageId] = Unit
    }

    @Synchronized
    fun wasRead(messageId: String): Boolean {
        return messageIds.containsKey(messageId)
    }
}
