package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageStore
import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.notification.ReadMessageIdTracker
import java.util.Date
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.legacy.Log
import app.k9mail.legacy.mailstore.MoreMessages as StoreMoreMessages

class K9BackendFolder(
    private val messageStore: MessageStore,
    private val saveMessageDataCreator: SaveMessageDataCreator,
    folderServerId: String,
) : BackendFolder {
    private val databaseId: String
    private val folderId: Long
    override val name: String
    override val visibleLimit: Int

    init {
        data class Init(val folderId: Long, val name: String, val visibleLimit: Int)

        val init = messageStore.getFolder(folderServerId) { folder ->
            Init(
                folderId = folder.id,
                name = folder.name,
                visibleLimit = folder.visibleLimit,
            )
        } ?: error("Couldn't find folder $folderServerId")

        databaseId = init.folderId.toString()
        folderId = init.folderId
        name = init.name
        visibleLimit = init.visibleLimit
    }

    override fun getMessageServerIds(): Set<String> {
        return messageStore.getMessageServerIds(folderId)
    }

    override fun getAllMessagesAndEffectiveDates(): Map<String, Long?> {
        return messageStore.getAllMessagesAndEffectiveDates(folderId)
    }

    override fun destroyMessages(messageServerIds: List<String>) {
        rememberReadMessageIds(messageServerIds)
        messageStore.destroyMessages(folderId, messageServerIds)
    }

    // Messages destroyed during sync may be re-delivered by the server with a new UID (e.g. after an
    // attachment scan). Remember the Message-IDs of read messages so the copy doesn't trigger a notification.
    private fun rememberReadMessageIds(messageServerIds: List<String>) {
        for (messageServerId in messageServerIds) {
            try {
                if (Flag.SEEN !in messageStore.getMessageFlags(folderId, messageServerId)) continue

                messageStore.getHeaders(folderId, messageServerId, setOf("Message-ID"))
                    .firstOrNull()
                    ?.let { header -> ReadMessageIdTracker.rememberReadMessage(header.value) }
            } catch (e: MessageNotFoundException) {
                Log.v(e, "Couldn't read Message-ID of message %s before destroying it", messageServerId)
            }
        }
    }

    override fun clearAllMessages() {
        val messageServerIds = messageStore.getMessageServerIds(folderId)
        messageStore.destroyMessages(folderId, messageServerIds)
    }

    override fun getMoreMessages(): MoreMessages {
        return messageStore.getFolder(folderId) { folder ->
            folder.moreMessages.toMoreMessages()
        } ?: MoreMessages.UNKNOWN
    }

    override fun setMoreMessages(moreMessages: MoreMessages) {
        messageStore.setMoreMessages(folderId, moreMessages.toStoreMoreMessages())
    }

    override fun setLastChecked(timestamp: Long) {
        messageStore.setLastChecked(folderId, timestamp)
    }

    override fun setStatus(status: String?) {
        messageStore.setStatus(folderId, status)
    }

    override fun isMessagePresent(messageServerId: String): Boolean {
        return messageStore.isMessagePresent(folderId, messageServerId)
    }

    override fun getMessageFlags(messageServerId: String): Set<Flag> {
        return messageStore.getMessageFlags(folderId, messageServerId)
    }

    override fun setMessageFlag(messageServerId: String, flag: Flag, value: Boolean) {
        messageStore.setMessageFlag(folderId, messageServerId, flag, value)
    }

    override fun saveMessage(message: Message, downloadState: MessageDownloadState) {
        requireMessageServerId(message)

        val messageData = saveMessageDataCreator.createSaveMessageData(message, downloadState)
        messageStore.saveRemoteMessage(folderId, message.uid, messageData)
    }

    override fun getOldestMessageDate(): Date? {
        return messageStore.getOldestMessageDate(folderId)
    }

    override fun getFolderExtraString(name: String): String? {
        return messageStore.getFolderExtraString(folderId, name)
    }

    override fun setFolderExtraString(name: String, value: String?) {
        messageStore.setFolderExtraString(folderId, name, value)
    }

    override fun getFolderExtraNumber(name: String): Long? {
        return messageStore.getFolderExtraNumber(folderId, name)
    }

    override fun setFolderExtraNumber(name: String, value: Long) {
        messageStore.setFolderExtraNumber(folderId, name, value)
    }

    private fun StoreMoreMessages.toMoreMessages(): MoreMessages = when (this) {
        StoreMoreMessages.UNKNOWN -> MoreMessages.UNKNOWN
        StoreMoreMessages.FALSE -> MoreMessages.FALSE
        StoreMoreMessages.TRUE -> MoreMessages.TRUE
    }

    private fun MoreMessages.toStoreMoreMessages(): StoreMoreMessages = when (this) {
        MoreMessages.UNKNOWN -> StoreMoreMessages.UNKNOWN
        MoreMessages.FALSE -> StoreMoreMessages.FALSE
        MoreMessages.TRUE -> StoreMoreMessages.TRUE
    }

    private fun requireMessageServerId(message: Message) {
        if (message.uid.isNullOrEmpty()) {
            error("Message requires a server ID to be set")
        }
    }
}
