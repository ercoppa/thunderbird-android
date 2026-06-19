package com.fsck.k9.ui.messagelist

import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.activity.compose.MessageActions
import com.fsck.k9.controller.MessagingControllerWrapper
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.extensions.withArguments
import com.fsck.k9.ui.messageview.MessageViewFragment
import com.google.android.material.textview.MaterialTextView
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Gmail-style conversation view: shows all messages of a conversation (across folders) as a vertical stack of
 * collapsible cards. The newest message is expanded by default; tapping a card header expands/collapses it. The
 * expanded message is rendered by an embedded [MessageViewFragment], whose options menu drives the toolbar.
 */
class ConversationViewFragment : Fragment() {
    private val viewModel: MessageListViewModel by viewModel()
    private val messagingController: MessagingControllerWrapper by inject()

    private lateinit var accountUuid: String
    private var threadRootId: Long = -1L
    private var initialReference: MessageReference? = null
    private var initialMessageDate: Long = -1L
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
    private var currentItems: List<MessageListItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val arguments = requireArguments()
        accountUuid = arguments.getString(ARG_ACCOUNT_UUID) ?: error("Missing argument $ARG_ACCOUNT_UUID")
        threadRootId = arguments.getLong(ARG_THREAD_ROOT_ID, -1L)
        showAccountIndicator = arguments.getBoolean(ARG_SHOW_ACCOUNT_INDICATOR, true)
        initialReference = MessageReference.parse(arguments.getString(ARG_INITIAL_REFERENCE))
        initialMessageDate = arguments.getLong(ARG_INITIAL_DATE, -1L)

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

        requireActivity().addMenuProvider(conversationMenuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewModel.getMessageListLiveData().observe(viewLifecycleOwner) { info ->
            updateConversation(info.messageListItems)
        }

        loadConversation()
    }

    private val conversationMenuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.conversation_view_menu, menu)
        }

        override fun onPrepareMenu(menu: Menu) {
            val item = menu.findItem(R.id.toggle_unread) ?: return
            val target = markUnreadTarget()
            item.isVisible = target != null
            if (target != null) {
                val icon = ContextCompat.getDrawable(requireContext(), Icons.Outlined.MarkEmailUnread)
                // Add a little breathing room on the right edge of the toolbar.
                val rightInset = (resources.displayMetrics.density * TOOLBAR_ICON_RIGHT_PADDING_DP).toInt()
                item.icon = icon?.let { InsetDrawable(it, 0, 0, rightInset, 0) }
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return if (menuItem.itemId == R.id.toggle_unread) {
                onMarkConversationUnread()
                true
            } else {
                false
            }
        }
    }

    /**
     * The message the toolbar's mark-as-unread action operates on: the newest received (non-outgoing) message in the
     * conversation. For a single-message conversation that is simply the open message; with several received messages
     * it is the most recent one. Returns null when the conversation has no received message (e.g. only sent ones).
     */
    private fun markUnreadTarget(): MessageListItem? {
        return currentItems.lastOrNull { !it.isOutgoing }
    }

    private fun onMarkConversationUnread() {
        val target = markUnreadTarget() ?: return
        messagingController.setFlag(target.account.id, listOf(target.databaseId), Flag.SEEN, false)
        // Marking unread is a "deal with it later" action, so return to the folder list afterwards.
        requireActivity().onBackPressedDispatcher.onBackPressed()
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
        currentItems = ordered
        requireActivity().invalidateMenu()

        if (!initialized) {
            initialized = true
            // Drafts are never expanded inline (rendered as a message they look like an already-sent mail); open the
            // newest non-draft instead, falling back to nothing to expand if the conversation is only drafts.
            val target = (findInitialTarget(ordered) ?: ordered.last())
                .takeUnless { isDraft(it) }
                ?: ordered.lastOrNull { !isDraft(it) }
            if (target != null) {
                expandedIds.add(target.uniqueId)
                focusedId = target.uniqueId
            }
            updateToolbarTitle(ordered.last())
        }

        if (newOrder == renderedOrder) {
            // Same messages in the same order: only refresh header state (e.g. read/unread).
            ordered.forEach { item -> cardViews[item.uniqueId]?.let { bindHeader(it, item) } }
        } else {
            fullBuild(ordered)
        }
    }

    /**
     * Resolve which message the conversation should open on: the one the user actually tapped in the folder.
     *
     * Cross-folder threading deduplicates messages that share a Message-ID (e.g. the same email present in Inbox and
     * All Mail, or in Sent and a label), keeping only one copy per Message-ID. So the tapped copy's exact
     * [MessageReference] (its folder/uid) may not be in the conversation result. In that case we fall back to matching
     * by message date, since the surviving copy is the same logical email and carries the same Date header.
     */
    private fun findInitialTarget(ordered: List<MessageListItem>): MessageListItem? {
        initialReference?.let { ref ->
            ordered.find { it.messageReference == ref }?.let { return it }
        }
        if (initialMessageDate >= 0) {
            ordered.find { it.messageDate == initialMessageDate }?.let { return it }
        }
        return null
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
                if (isDraft(item)) editDraft(item) else toggleExpanded(item)
            }

            container.addView(card)
            cardViews[item.uniqueId] = card

            if (item.uniqueId in expandedIds && !isDraft(item)) {
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

        val draft = isDraft(item)
        card.findViewById<View>(R.id.conversation_card_draft_badge).isVisible = draft
        card.findViewById<ImageView>(R.id.conversation_card_edit_draft).apply {
            isVisible = draft
            if (draft) {
                setImageResource(Icons.Outlined.Edit)
                setOnClickListener { editDraft(item) }
            } else {
                setOnClickListener(null)
            }
        }

        applyExpandedChrome(card, item.uniqueId in expandedIds)
    }

    private fun isDraft(item: MessageListItem): Boolean {
        return item.account.draftsFolderId?.let { it == item.folderId } ?: false
    }

    private fun editDraft(item: MessageListItem) {
        MessageActions.actionEditDraft(requireContext(), item.messageReference)
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
        private const val ARG_INITIAL_DATE = "initialMessageDate"
        private const val ARG_SHOW_ACCOUNT_INDICATOR = "showAccountIndicator"

        // Base for deterministic body-container view ids (index added). Kept below the aapt id range so it can't
        // collide with generated resource ids.
        private const val BODY_CONTAINER_BASE_ID = 0x00AB0000

        private const val TOOLBAR_ICON_RIGHT_PADDING_DP = 8

        fun newInstance(
            accountUuid: String,
            threadRootId: Long,
            initialReference: MessageReference?,
            initialMessageDate: Long,
            showAccountIndicator: Boolean,
        ): ConversationViewFragment {
            return ConversationViewFragment().withArguments(
                ARG_ACCOUNT_UUID to accountUuid,
                ARG_THREAD_ROOT_ID to threadRootId,
                ARG_INITIAL_REFERENCE to initialReference?.toIdentityString(),
                ARG_INITIAL_DATE to initialMessageDate,
                ARG_SHOW_ACCOUNT_INDICATOR to showAccountIndicator,
            )
        }
    }
}
