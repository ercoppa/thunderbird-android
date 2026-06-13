package com.fsck.k9.storage.messages

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.k9mail.legacy.mailstore.MessageDetailsAccessor
import app.k9mail.legacy.mailstore.MessageMapper
import app.k9mail.legacy.message.extractors.PreviewResult
import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.DatabasePreviewType
import com.fsck.k9.mailstore.LockableDatabase
import net.thunderbird.feature.search.legacy.sql.SqlWhereClause

internal class RetrieveMessageListOperations(private val lockableDatabase: LockableDatabase) {

    fun <T> getMessages(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        mapper: MessageMapper<out T?>,
    ): List<T> {
        return lockableDatabase.execute(false) { database ->
            database.rawQuery(
                """
SELECT 
  messages.id AS id, 
  uid, 
  folder_id, 
  sender_list, 
  to_list, 
  cc_list, 
  date, 
  internal_date, 
  subject, 
  preview_type,
  preview, 
  read, 
  flagged, 
  answered, 
  forwarded, 
  attachment_count, 
  root
FROM messages
JOIN threads ON (threads.message_id = messages.id)
LEFT JOIN FOLDERS ON (folders.id = messages.folder_id)
WHERE
  ($selection)
  AND empty = 0 AND deleted = 0
ORDER BY $sortOrder
                """,
                selectionArgs,
            ).use { cursor ->
                val cursorMessageAccessor = CursorMessageAccessor(cursor, includesThreadCount = false)
                buildList {
                    while (cursor.moveToNext()) {
                        val value = mapper.map(cursorMessageAccessor)
                        if (value != null) {
                            add(value)
                        }
                    }
                }
            }
        }
    }

    fun <T> getThreadedMessages(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        mapper: MessageMapper<out T?>,
    ): List<T> {
        val orderBy = SqlWhereClause.addPrefixToSelection(
            AGGREGATED_MESSAGES_COLUMNS,
            "aggregated.",
            sortOrder,
        )

        return lockableDatabase.execute(false) { database ->
            val mergedThreadCounts = computeMergedThreadCounts(database, selection, selectionArgs)
            database.rawQuery(
                """
SELECT
  messages.id AS id,
  uid,
  folder_id,
  sender_list,
  to_list,
  cc_list,
  aggregated.date AS date,
  aggregated.internal_date AS internal_date, 
  subject, 
  preview_type,
  preview, 
  aggregated.read AS read, 
  aggregated.flagged AS flagged, 
  aggregated.answered AS answered, 
  aggregated.forwarded AS forwarded, 
  aggregated.attachment_count AS attachment_count, 
  root, 
  aggregated.thread_count AS thread_count
FROM (
  SELECT 
    threads.root AS thread_root,
    MAX(date) AS date,
    MAX(internal_date) AS internal_date,
    MIN(read) AS read,
    MAX(flagged) AS flagged,
    MIN(answered) AS answered,
    MIN(forwarded) AS forwarded,
    SUM(attachment_count) AS attachment_count,
    COUNT(threads.root) AS thread_count                        
  FROM messages
  JOIN threads ON (threads.message_id = messages.id)
  JOIN folders ON (folders.id = messages.folder_id)
  WHERE
    threads.root IN (
      SELECT threads.root 
      FROM messages
      JOIN threads ON (threads.message_id = messages.id)
      WHERE messages.empty = 0 AND messages.deleted = 0
    )
    AND ($selection)
    AND messages.empty = 0 AND messages.deleted = 0
  GROUP BY threads.root
) aggregated
JOIN threads ON (threads.root = aggregated.thread_root)
JOIN messages ON (
  messages.id = threads.message_id
  AND messages.empty = 0 AND messages.deleted = 0
  AND messages.date = aggregated.date
)
JOIN folders ON (folders.id = messages.folder_id)
GROUP BY threads.root
ORDER BY $orderBy
                """,
                selectionArgs,
            ).use { cursor ->
                val cursorMessageAccessor =
                    CursorMessageAccessor(cursor, includesThreadCount = true, mergedThreadCounts = mergedThreadCounts)
                buildList {
                    while (cursor.moveToNext()) {
                        val value = mapper.map(cursorMessageAccessor)
                        if (value != null) {
                            add(value)
                        }
                    }
                }
            }
        }
    }

    fun <T> getThread(threadId: Long, sortOrder: String, mapper: MessageMapper<out T?>): List<T> {
        return lockableDatabase.execute(false) { database ->
            val rootIds = collectConnectedThreadRoots(database, threadId)
            val messageRowIds = if (rootIds.isEmpty()) emptySet() else selectThreadMessageRowIds(database, rootIds)
            queryMessagesByRowIds(database, messageRowIds, sortOrder, mapper)
        }
    }

    /**
     * Selects which message rows to display for a conversation: one representative (lowest id) per Message-ID plus
     * every message that has no Message-ID. This is done in memory to avoid a correlated subquery that scales as
     * O(n^2), and the root lookup is chunked to stay within SQLite's bind-variable limit.
     */
    private fun selectThreadMessageRowIds(database: SQLiteDatabase, rootIds: Set<Long>): Set<Long> {
        val minRowIdByMessageId = HashMap<String, Long>()
        val rowIdsWithoutMessageId = mutableListOf<Long>()

        forEachSqlChunk(rootIds.map { it.toString() }) { placeholders, args ->
            database.rawQuery(
                """
SELECT messages.id, messages.message_id
FROM threads
JOIN messages ON (messages.id = threads.message_id)
WHERE threads.root IN ($placeholders)
  AND messages.empty = 0 AND messages.deleted = 0
                """,
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(0)
                    val messageId = cursor.getString(1)
                    if (messageId.isNullOrEmpty()) {
                        rowIdsWithoutMessageId += rowId
                    } else {
                        val existing = minRowIdByMessageId[messageId]
                        if (existing == null || rowId < existing) {
                            minRowIdByMessageId[messageId] = rowId
                        }
                    }
                }
            }
        }

        return buildSet {
            addAll(rowIdsWithoutMessageId)
            addAll(minRowIdByMessageId.values)
        }
    }

    private fun <T> queryMessagesByRowIds(
        database: SQLiteDatabase,
        rowIds: Set<Long>,
        sortOrder: String,
        mapper: MessageMapper<out T?>,
    ): List<T> {
        if (rowIds.isEmpty()) return emptyList()

        return buildList {
            forEachSqlChunk(rowIds.map { it.toString() }) { placeholders, args ->
                database.rawQuery(
                    """
SELECT
  messages.id AS id,
  uid,
  folder_id,
  sender_list,
  to_list,
  cc_list,
  date,
  internal_date,
  subject,
  preview_type,
  preview,
  read,
  flagged,
  answered,
  forwarded,
  attachment_count,
  root
FROM messages
LEFT JOIN threads ON (threads.message_id = messages.id)
LEFT JOIN folders ON (folders.id = messages.folder_id)
WHERE messages.id IN ($placeholders)
ORDER BY $sortOrder
                    """,
                    args,
                ).use { cursor ->
                    val cursorMessageAccessor = CursorMessageAccessor(cursor, includesThreadCount = false)
                    while (cursor.moveToNext()) {
                        val value = mapper.map(cursorMessageAccessor)
                        if (value != null) {
                            add(value)
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the set of thread roots that belong to the same conversation as [startRootId], following links formed
     * by shared Message-IDs across folders (e.g. an Inbox reply and its Sent original).
     *
     * The walk is bounded: messages without a Message-ID (or with an empty one) are ignored as links, and the total
     * number of roots is capped. Without this, a single empty/shared Message-ID can connect unrelated threads and
     * make the walk cover the entire mailbox.
     */
    private fun collectConnectedThreadRoots(database: SQLiteDatabase, startRootId: Long): Set<Long> {
        val roots = mutableSetOf(startRootId)
        var frontier = setOf(startRootId)

        while (frontier.isNotEmpty() && roots.size <= MAX_CONNECTED_THREAD_ROOTS) {
            val messageIds = messageIdsForRoots(database, frontier)
            if (messageIds.isEmpty()) break

            val connectedRoots = rootsForMessageIds(database, messageIds)
            frontier = connectedRoots - roots
            roots += frontier
        }

        return roots
    }

    private fun messageIdsForRoots(database: SQLiteDatabase, rootIds: Set<Long>): Set<String> {
        val messageIds = mutableSetOf<String>()
        forEachSqlChunk(rootIds.map { it.toString() }) { placeholders, args ->
            database.rawQuery(
                """
SELECT DISTINCT messages.message_id
FROM threads
JOIN messages ON (messages.id = threads.message_id)
WHERE threads.root IN ($placeholders)
  AND messages.message_id IS NOT NULL
  AND messages.message_id != ''
                """,
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { messageIds += it }
                }
            }
        }
        return messageIds
    }

    private fun rootsForMessageIds(database: SQLiteDatabase, messageIds: Set<String>): Set<Long> {
        val roots = mutableSetOf<Long>()
        forEachSqlChunk(messageIds.toList()) { placeholders, args ->
            database.rawQuery(
                """
SELECT DISTINCT threads.root
FROM messages
JOIN threads ON (threads.message_id = messages.id)
WHERE messages.message_id IN ($placeholders)
  AND threads.root IS NOT NULL
                """,
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) roots += cursor.getLong(0)
                }
            }
        }
        return roots
    }

    @Suppress("LongMethod")
    private fun computeMergedThreadCounts(
        database: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>,
    ): Map<Long, Int> {
        val displayedRoots = rootsForSelection(database, selection, selectionArgs)
        if (displayedRoots.isEmpty()) return emptyMap()

        // Group thread roots that share a Message-ID into components using a fixed number of queries and an
        // in-memory union-find. Walking the graph with separate queries per displayed thread doesn't scale to
        // large folders and could exceed SQLite's limit on bind variables.
        val parent = HashMap<Long, Long>()

        fun find(root: Long): Long {
            var current = root
            while (true) {
                val next = parent[current] ?: break
                if (next == current) break
                current = next
            }
            parent[root] = current
            return current
        }

        fun union(first: Long, second: Long) {
            val firstComponent = find(first)
            val secondComponent = find(second)
            if (firstComponent != secondComponent) {
                parent[secondComponent] = firstComponent
            }
        }

        database.rawQuery(
            """
SELECT threads.root, messages.message_id
FROM threads
JOIN messages ON (messages.id = threads.message_id)
WHERE threads.root IS NOT NULL AND messages.message_id IS NOT NULL AND messages.message_id != ''
            """,
            null,
        ).use { cursor ->
            val firstRootByMessageId = HashMap<String, Long>()
            while (cursor.moveToNext()) {
                val root = cursor.getLong(0)
                val messageId = cursor.getString(1) ?: continue
                val firstRoot = firstRootByMessageId.putIfAbsent(messageId, root)
                if (firstRoot != null && firstRoot != root) {
                    union(firstRoot, root)
                }
            }
        }

        // Count "real" messages per component: distinct Message-IDs plus messages without a Message-ID.
        val distinctMessageIdsByComponent = HashMap<Long, MutableSet<String>>()
        database.rawQuery(
            """
SELECT threads.root, messages.message_id
FROM threads
JOIN messages ON (messages.id = threads.message_id)
WHERE threads.root IS NOT NULL
  AND messages.empty = 0 AND messages.deleted = 0
  AND messages.message_id IS NOT NULL AND messages.message_id != ''
            """,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val component = find(cursor.getLong(0))
                val messageId = cursor.getString(1) ?: continue
                distinctMessageIdsByComponent.getOrPut(component) { mutableSetOf() }.add(messageId)
            }
        }

        val messagesWithoutMessageIdByComponent = HashMap<Long, Int>()
        database.rawQuery(
            """
SELECT threads.root, COUNT(*)
FROM threads
JOIN messages ON (messages.id = threads.message_id)
WHERE threads.root IS NOT NULL
  AND messages.empty = 0 AND messages.deleted = 0
  AND (messages.message_id IS NULL OR messages.message_id = '')
GROUP BY threads.root
            """,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val component = find(cursor.getLong(0))
                messagesWithoutMessageIdByComponent.merge(component, cursor.getInt(1), Int::plus)
            }
        }

        return buildMap {
            for (root in displayedRoots) {
                val component = find(root)
                val count = (distinctMessageIdsByComponent[component]?.size ?: 0) +
                    (messagesWithoutMessageIdByComponent[component] ?: 0)
                put(root, count)
            }
        }
    }

    private fun rootsForSelection(
        database: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>,
    ): Set<Long> {
        return database.rawQuery(
            """
SELECT DISTINCT threads.root
FROM messages
JOIN threads ON (threads.message_id = messages.id)
JOIN folders ON (folders.id = messages.folder_id)
WHERE ($selection)
  AND messages.empty = 0 AND messages.deleted = 0
  AND threads.root IS NOT NULL
            """,
            selectionArgs,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) add(cursor.getLong(0))
                }
            }
        }
    }

}

private class CursorMessageAccessor(
    val cursor: Cursor,
    val includesThreadCount: Boolean,
    val mergedThreadCounts: Map<Long, Int>? = null,
) : MessageDetailsAccessor {
    override val id: Long
        get() = cursor.getLong(0)
    override val messageServerId: String
        get() = cursor.getString(1)
    override val folderId: Long
        get() = cursor.getLong(2)
    override val fromAddresses: List<Address>
        get() = Address.unpack(cursor.getString(3)).toList()
    override val toAddresses: List<Address>
        get() = Address.unpack(cursor.getString(4)).toList()
    override val ccAddresses: List<Address>
        get() = Address.unpack(cursor.getString(5)).toList()
    override val messageDate: Long
        get() = cursor.getLong(6)
    override val internalDate: Long
        get() = cursor.getLong(7)
    override val subject: String?
        get() = cursor.getString(8)
    override val preview: PreviewResult
        get() {
            return when (DatabasePreviewType.fromDatabaseValue(cursor.getString(9))) {
                DatabasePreviewType.NONE -> PreviewResult.none()
                DatabasePreviewType.TEXT -> PreviewResult.text(cursor.getString(10))
                DatabasePreviewType.ENCRYPTED -> PreviewResult.encrypted()
                DatabasePreviewType.ERROR -> PreviewResult.error()
            }
        }
    override val isRead: Boolean
        get() = cursor.getInt(11) == 1
    override val isStarred: Boolean
        get() = cursor.getInt(12) == 1
    override val isAnswered: Boolean
        get() = cursor.getInt(13) == 1
    override val isForwarded: Boolean
        get() = cursor.getInt(14) == 1
    override val hasAttachments: Boolean
        get() = cursor.getInt(15) > 0
    override val threadRoot: Long
        get() = cursor.getLong(16)
    override val threadCount: Int
        get() = if (includesThreadCount) mergedThreadCounts?.get(threadRoot) ?: cursor.getInt(17) else 0
}

private val AGGREGATED_MESSAGES_COLUMNS = arrayOf(
    "date",
    "internal_date",
    "attachment_count",
    "read",
    "flagged",
    "answered",
    "forwarded",
)

// SQLite limits the number of bound parameters per statement (historically 999). Keep IN() lists below that.
private const val MAX_SQL_VARIABLES = 900

// Safety bound on how many thread roots a single conversation may span, to keep the cross-folder walk cheap even if
// the data links many threads together.
private const val MAX_CONNECTED_THREAD_ROOTS = 500

/**
 * Runs [action] once per chunk of [keys] small enough to be used as bound parameters in a SQL `IN (...)` clause,
 * passing the comma-separated placeholder string and the matching argument array.
 */
private inline fun forEachSqlChunk(keys: List<String>, action: (placeholders: String, args: Array<String>) -> Unit) {
    if (keys.isEmpty()) return
    for (chunk in keys.chunked(MAX_SQL_VARIABLES)) {
        val placeholders = chunk.joinToString(separator = ",") { "?" }
        action(placeholders, chunk.toTypedArray())
    }
}
