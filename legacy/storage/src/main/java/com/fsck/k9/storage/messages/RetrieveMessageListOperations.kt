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

            val rootPlaceholders = rootIds.joinToString(separator = ",") { "?" }
            val rootArgs = rootIds.map { it.toString() }.toTypedArray()

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
FROM threads
JOIN messages ON (messages.id = threads.message_id)
LEFT JOIN FOLDERS ON (folders.id = messages.folder_id)
WHERE
  root IN ($rootPlaceholders)
  AND messages.empty = 0 AND messages.deleted = 0
  AND (
    messages.message_id IS NULL
    OR messages.id = (
      SELECT MIN(m2.id)
      FROM threads t2
      JOIN messages m2 ON (m2.id = t2.message_id)
      WHERE t2.root IN ($rootPlaceholders)
        AND m2.empty = 0 AND m2.deleted = 0
        AND m2.message_id = messages.message_id
    )
  )
ORDER BY $sortOrder
                """,
                rootArgs + rootArgs,
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

    private fun collectConnectedThreadRoots(database: SQLiteDatabase, startRootId: Long): Set<Long> {
        val roots = mutableSetOf(startRootId)
        var frontier = setOf(startRootId)

        while (frontier.isNotEmpty()) {
            val messageIds = messageIdsForRoots(database, frontier)
            if (messageIds.isEmpty()) break

            val connectedRoots = rootsForMessageIds(database, messageIds)
            frontier = connectedRoots - roots
            roots += frontier
        }

        return roots
    }

    private fun messageIdsForRoots(database: SQLiteDatabase, rootIds: Set<Long>): Set<String> {
        if (rootIds.isEmpty()) return emptySet()
        val placeholders = rootIds.joinToString(separator = ",") { "?" }
        val args = rootIds.map { it.toString() }.toTypedArray()

        return database.rawQuery(
            """
SELECT DISTINCT messages.message_id
FROM threads
JOIN messages ON (messages.id = threads.message_id)
WHERE threads.root IN ($placeholders)
  AND messages.message_id IS NOT NULL
            """,
            args,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { add(it) }
                }
            }
        }
    }

    private fun rootsForMessageIds(database: SQLiteDatabase, messageIds: Set<String>): Set<Long> {
        if (messageIds.isEmpty()) return emptySet()
        val placeholders = messageIds.joinToString(separator = ",") { "?" }
        val args = messageIds.toTypedArray()

        return database.rawQuery(
            """
SELECT DISTINCT threads.root
FROM messages
JOIN threads ON (threads.message_id = messages.id)
WHERE messages.message_id IN ($placeholders)
  AND threads.root IS NOT NULL
            """,
            args,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) add(cursor.getLong(0))
                }
            }
        }
    }

    private fun computeMergedThreadCounts(
        database: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>,
    ): Map<Long, Int> {
        val displayedRoots = rootsForSelection(database, selection, selectionArgs)
        if (displayedRoots.isEmpty()) return emptyMap()

        val counts = HashMap<Long, Int>()
        val processed = HashSet<Long>()
        for (root in displayedRoots) {
            if (root in processed) continue
            val connectedRoots = collectConnectedThreadRoots(database, root)
            val count = countRealMessagesInRoots(database, connectedRoots)
            for (connectedRoot in connectedRoots) {
                if (connectedRoot in displayedRoots) counts[connectedRoot] = count
            }
            processed += connectedRoots
        }
        return counts
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

    private fun countRealMessagesInRoots(database: SQLiteDatabase, rootIds: Set<Long>): Int {
        if (rootIds.isEmpty()) return 0
        val placeholders = rootIds.joinToString(separator = ",") { "?" }
        val args = rootIds.map { it.toString() }.toTypedArray()

        return database.rawQuery(
            """
SELECT
  (SELECT COUNT(DISTINCT messages.message_id)
   FROM threads
   JOIN messages ON (messages.id = threads.message_id)
   WHERE threads.root IN ($placeholders)
     AND messages.empty = 0 AND messages.deleted = 0
     AND messages.message_id IS NOT NULL)
  + (SELECT COUNT(*)
     FROM threads
     JOIN messages ON (messages.id = threads.message_id)
     WHERE threads.root IN ($placeholders)
       AND messages.empty = 0 AND messages.deleted = 0
       AND messages.message_id IS NULL)
            """,
            args + args,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
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
