package com.fsck.k9.ui.messagelist

import androidx.lifecycle.LiveData
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import com.fsck.k9.search.getLegacyAccountUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountManager

class MessageListLiveData(
    private val messageListLoader: MessageListLoader,
    private val accountManager: LegacyAccountManager,
    private val messageListRepository: MessageListRepository,
    private val coroutineScope: CoroutineScope,
    val config: MessageListConfig,
) : LiveData<MessageListInfo>() {

    private val messageListChangedListener = MessageListChangedListener {
        loadMessageListAsync()
    }

    // Both flags are only accessed from the main dispatcher.
    private var loadRequested = false
    private var isLoading = false

    private fun loadMessageListAsync() {
        coroutineScope.launch(Dispatchers.Main) {
            loadRequested = true
            if (isLoading) return@launch

            isLoading = true
            try {
                // During a sync the change listener can fire for every single message. Coalesce these events
                // into one running query at a time; requests arriving while a query is in flight result in a
                // single re-query afterwards. This also prevents a slow stale result from overwriting a newer one.
                while (loadRequested) {
                    loadRequested = false
                    val messageList = withContext(Dispatchers.IO) {
                        messageListLoader.getMessageList(config)
                    }
                    if (messageList != null) {
                        value = messageList
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    override fun onActive() {
        super.onActive()

        registerMessageListChangedListenerAsync()
        loadMessageListAsync()
    }

    override fun onInactive() {
        super.onInactive()
        messageListRepository.removeListener(messageListChangedListener)
    }

    private fun registerMessageListChangedListenerAsync() {
        coroutineScope.launch(Dispatchers.IO) {
            val accountUuids = config.search.getLegacyAccountUuids(accountManager)

            for (accountUuid in accountUuids) {
                messageListRepository.addListener(accountUuid, messageListChangedListener)
            }
        }
    }
}
