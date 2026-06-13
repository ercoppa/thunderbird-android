package com.fsck.k9.ui.messagelist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.extensions.withArguments
import com.fsck.k9.ui.messageview.MessageViewFragment
import com.google.android.material.textview.MaterialTextView
import net.thunderbird.core.android.account.SortType
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Gmail-style conversation view: shows all messages of a conversation (across folders) as a vertical stack of
 * collapsible cards. The newest message is expanded by default; tapping a card header expands/collapses it. The
 * expanded message is rendered by an embedded [MessageViewFragment], whose options menu drives the toolbar.
 */
class ConversationViewFragment : Fragment() {
    private val viewModel: MessageListViewModel by viewModel()

    private lateinit var accountUuid: String
    private var threadRootId: Long = -1L
    private var initialReference: MessageReference? = null
    private var showAccountIndicator: Boolean = true

    private lateinit var container: LinearLayout

    private val expandedIds = mutableSetOf<Long>()
    private var focusedId: Long? = null
    private var renderedOrder: List<Long> = emptyList()
    private val cardViews = mutableMapOf<Long, View>()
    private val bodyViews = mutableMapOf<Long, View>()
    private val bodyContainerIds = mutableMapOf<Long, Int>()
    private var bottomSpacer: View? = null
    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val arguments = requireArguments()
        accountUuid = arguments.getString(ARG_ACCOUNT_UUID) ?: error("Missing argument $ARG_ACCOUNT_UUID")
        threadRootId = arguments.getLong(ARG_THREAD_ROOT_ID, -1L)
        showAccountIndicator = arguments.getBoolean(ARG_SHOW_ACCOUNT_INDICATOR, true)
        initialReference = MessageReference.parse(arguments.getString(ARG_INITIAL_REFERENCE))

        // Avoid orphaned child fragments restored after a configuration change; the stack is rebuilt from scratch.
        if (savedInstanceState != null) {
            childFragmentManager.fragments.toList().forEach { fragment ->
                childFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.conversation_view, parent, false)
        container = view.findViewById(R.id.conversation_container)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.getMessageListLiveData().observe(viewLifecycleOwner) { info ->
            updateConversation(info.messageListItems)
        }

        loadConversation()
    }

    private fun loadConversation() {
        val search = LocalMessageSearch().apply {
            id = "Conversation-$accountUuid-$threadRootId"
            addAccountUuid(accountUuid)
            and(MessageSearchField.THREAD_ID, threadRootId.toString(), SearchAttribute.EQUALS)
        }

        val config = MessageListConfig(
            search = search,
            showingThreadedList = false,
            sortType = SortType.SORT_DATE,
            sortAscending = true,
            sortDateAscending = true,
            activeMessage = null,
            sortOverrides = emptyMap(),
        )

        viewModel.loadMessageList(config)
    }

    private fun updateConversation(items: List<MessageListItem>) {
        if (items.isEmpty()) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        val ordered = items.sortedBy { it.messageDate }
        val newOrder = ordered.map { it.uniqueId }

        if (!initialized) {
            initialized = true
            val target = initialReference?.let { ref -> ordered.find { it.messageReference == ref } }
                ?: ordered.last()
            expandedIds.add(target.uniqueId)
            focusedId = target.uniqueId
            updateToolbarTitle(ordered.last())
        }

        if (newOrder == renderedOrder) {
            // Same messages in the same order: only refresh header state (e.g. read/unread).
            ordered.forEach { item -> cardViews[item.uniqueId]?.let { bindHeader(it, item) } }
        } else {
            fullBuild(ordered)
        }
    }

    private fun fullBuild(ordered: List<MessageListItem>) {
        // Drop any expanded ids that no longer exist in the conversation.
        expandedIds.retainAll(ordered.mapTo(mutableSetOf()) { it.uniqueId })
        if (focusedId !in expandedIds) {
            focusedId = ordered.lastOrNull { it.uniqueId in expandedIds }?.uniqueId
        }

        childFragmentManager.fragments.toList().forEach { fragment ->
            childFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        container.removeAllViews()
        cardViews.clear()
        bodyViews.clear()
        bodyContainerIds.clear()

        ordered.forEachIndexed { index, item ->
            val card = layoutInflater.inflate(R.layout.conversation_message_card, container, false)
            val body = card.findViewById<View>(R.id.conversation_card_body)
            val bodyId = BODY_CONTAINER_BASE_ID + index
            body.id = bodyId
            bodyContainerIds[item.uniqueId] = bodyId
            bodyViews[item.uniqueId] = body

            bindHeader(card, item)
            card.findViewById<View>(R.id.conversation_card_header).setOnClickListener {
                toggleExpanded(item)
            }

            container.addView(card)
            cardViews[item.uniqueId] = card

            if (item.uniqueId in expandedIds) {
                expandBody(item, bodyId, focus = item.uniqueId == focusedId)
            }
        }

        // Trailing spacer so a short newest message can still sit at the top of the screen with blank space below.
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        container.addView(spacer)
        bottomSpacer = spacer

        renderedOrder = ordered.map { it.uniqueId }
        refreshActiveMenu()
        scrollToFocused()
    }

    private fun bindHeader(card: View, item: MessageListItem) {
        card.findViewById<MaterialTextView>(R.id.conversation_card_sender).text = item.displayName
        card.findViewById<MaterialTextView>(R.id.conversation_card_date).text = item.displayMessageDateTime
        val snippet = card.findViewById<MaterialTextView>(R.id.conversation_card_snippet)
        snippet.text = item.previewText.ifBlank { item.subject.orEmpty() }
        card.findViewById<View>(R.id.conversation_card_unread_indicator).isVisible = !item.isRead

        applyExpandedChrome(card, item.uniqueId in expandedIds)
    }

    /**
     * When expanded, the embedded message view shows its own (richer) header with sender, date and actions, so the
     * card header is hidden to avoid duplicating the sender/time. Tapping that message header collapses the message
     * again (wired via [MessageViewFragment]'s embedded collapse listener).
     */
    private fun applyExpandedChrome(card: View, expanded: Boolean) {
        card.findViewById<View>(R.id.conversation_card_header).isVisible = !expanded
    }

    private fun toggleExpanded(item: MessageListItem) {
        val bodyId = bodyContainerIds[item.uniqueId] ?: return
        val card = cardViews[item.uniqueId] ?: return

        if (item.uniqueId in expandedIds) {
            collapseBody(item)
        } else {
            expandBody(item, bodyId, focus = true)
        }

        applyExpandedChrome(card, item.uniqueId in expandedIds)
        refreshActiveMenu()
    }

    private fun collapseItem(item: MessageListItem) {
        if (item.uniqueId !in expandedIds) return
        val card = cardViews[item.uniqueId] ?: return
        collapseBody(item)
        applyExpandedChrome(card, expanded = false)
        refreshActiveMenu()
    }

    private fun expandBody(item: MessageListItem, bodyId: Int, focus: Boolean) {
        expandedIds.add(item.uniqueId)
        bodyViews[item.uniqueId]?.isVisible = true

        val tag = fragmentTag(item.uniqueId)
        if (childFragmentManager.findFragmentByTag(tag) == null) {
            val fragment = MessageViewFragment.newInstance(
                reference = item.messageReference,
                showAccountIndicator = showAccountIndicator,
                embedded = true,
            )
            // Tapping the message's own header collapses it again.
            fragment.embeddedCollapseListener = { collapseItem(item) }
            childFragmentManager.beginTransaction()
                .replace(bodyId, fragment, tag)
                .commit()
        }

        if (focus) {
            setFocused(item.uniqueId)
        }
    }

    private fun collapseBody(item: MessageListItem) {
        expandedIds.remove(item.uniqueId)

        childFragmentManager.findFragmentByTag(fragmentTag(item.uniqueId))?.let { fragment ->
            childFragmentManager.beginTransaction().remove(fragment).commit()
        }
        bodyViews[item.uniqueId]?.isVisible = false

        if (focusedId == item.uniqueId) {
            focusedId = expandedIds.lastOrNull()
        }
    }

    private fun setFocused(uniqueId: Long) {
        if (focusedId == uniqueId) return

        focusedId?.let { previous ->
            (childFragmentManager.findFragmentByTag(fragmentTag(previous)) as? MessageViewFragment)
                ?.setMenuVisibility(false)
        }
        focusedId = uniqueId
    }

    private fun refreshActiveMenu() {
        val focused = focusedId ?: return
        (childFragmentManager.findFragmentByTag(fragmentTag(focused)) as? MessageViewFragment)
            ?.setMenuVisibility(true)
        requireActivity().invalidateMenu()
    }

    private fun scrollToFocused() {
        val focused = focusedId ?: return
        val card = cardViews[focused] ?: return
        val body = bodyViews[focused]
        val scrollView = view as? NestedScrollView ?: return

        // Position the conversation on the focused (newest) message so the user reads it first and scrolls up for
        // older ones. The message body is a WebView whose height grows asynchronously, so we keep the focused card
        // pinned to the top across layout passes until it stabilizes, bailing if the user starts scrolling. A bottom
        // spacer is sized so that even a short newest message can reach the top, leaving blank space below it.
        val spacer = bottomSpacer
        scrollView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                private var lastBodyHeight = -1
                private var expectedScrollY = -1

                override fun onGlobalLayout() {
                    val bodyHeight = body?.height ?: 0
                    if (bodyHeight == 0) return

                    if (expectedScrollY >= 0 && scrollView.scrollY != expectedScrollY) {
                        // The user has scrolled; stop pinning.
                        removeSelf()
                        return
                    }

                    // Ensure there is enough room below the focused card for it to scroll to the very top.
                    val desiredSpacer = (scrollView.height - card.height).coerceAtLeast(0)
                    if (spacer != null && spacer.height != desiredSpacer) {
                        spacer.layoutParams = spacer.layoutParams.apply { height = desiredSpacer }
                        return
                    }

                    scrollView.scrollTo(0, card.top)
                    expectedScrollY = scrollView.scrollY

                    if (bodyHeight == lastBodyHeight) {
                        removeSelf()
                    }
                    lastBodyHeight = bodyHeight
                }

                private fun removeSelf() {
                    if (scrollView.viewTreeObserver.isAlive) {
                        scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            },
        )
    }

    private fun updateToolbarTitle(newest: MessageListItem) {
        val subject = newest.subject?.takeIf { it.isNotBlank() }
            ?: getString(R.string.general_no_subject)
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = subject
    }

    private fun fragmentTag(uniqueId: Long): String = "conversation-message-$uniqueId"

    companion object {
        private const val ARG_ACCOUNT_UUID = "accountUuid"
        private const val ARG_THREAD_ROOT_ID = "threadRootId"
        private const val ARG_INITIAL_REFERENCE = "initialReference"
        private const val ARG_SHOW_ACCOUNT_INDICATOR = "showAccountIndicator"

        // Base for deterministic body-container view ids (index added). Kept below the aapt id range so it can't
        // collide with generated resource ids.
        private const val BODY_CONTAINER_BASE_ID = 0x00AB0000

        fun newInstance(
            accountUuid: String,
            threadRootId: Long,
            initialReference: MessageReference?,
            showAccountIndicator: Boolean,
        ): ConversationViewFragment {
            return ConversationViewFragment().withArguments(
                ARG_ACCOUNT_UUID to accountUuid,
                ARG_THREAD_ROOT_ID to threadRootId,
                ARG_INITIAL_REFERENCE to initialReference?.toIdentityString(),
                ARG_SHOW_ACCOUNT_INDICATOR to showAccountIndicator,
            )
        }
    }
}
