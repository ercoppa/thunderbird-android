package app.k9mail.html.cleaner

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class HtmlProcessor(
    private val customClasses: Set<String>,
    private val htmlHeadProvider: HtmlHeadProvider,
) {
    private val htmlSanitizer = HtmlSanitizer()

    fun processForDisplay(html: String): String {
        return htmlSanitizer.sanitize(html)
            .foldQuotedText()
            .addCustomHeadContents()
            .addCustomClasses()
            .toCompactString()
    }

    /**
     * Collapses the quoted reply history (and anything after it, e.g. trailing signatures) behind a clickable
     * disclosure so the actual message isn't buried in noise. Uses a native `<details>` element, since JavaScript is
     * disabled in the message view.
     */
    private fun Document.foldQuotedText() = apply {
        val boundary = body().selectFirst(QUOTE_BOUNDARY_SELECTOR) ?: return@apply
        val parent = boundary.parent() ?: return@apply

        // Don't fold when the quote is the whole message (nothing to read above it).
        if (!hasReadableContentBefore(boundary)) return@apply

        val details = Element("details").addClass(QUOTE_FOLD_CLASS)
        details.appendElement("summary").addClass(QUOTE_TOGGLE_CLASS).text(QUOTE_TOGGLE_LABEL)

        boundary.before(details)

        // Move the quote and everything after it within its container into the <details>.
        val nodesToFold = mutableListOf<Node>()
        var node: Node? = boundary
        while (node != null) {
            val next = node.nextSibling()
            nodesToFold.add(node)
            node = next
        }
        nodesToFold.forEach { details.appendChild(it) }

        // Keep the empty parent from collapsing layout if it only held the quote.
        if (parent.childNodeSize() == 0) parent.appendChild(TextNode(""))
    }

    private fun hasReadableContentBefore(boundary: Element): Boolean {
        // Walk up to <body>, and at each level check everything that comes before the current node. We must include
        // text nodes, not just elements: plain-text messages put the reply text and the "On ... wrote:" attribution
        // as bare text inside a <pre>, so checking only element siblings would wrongly conclude there is nothing
        // above the quote and skip folding.
        var node: Node = boundary
        while (true) {
            var sibling = node.previousSibling()
            while (sibling != null) {
                when (sibling) {
                    is TextNode -> if (!sibling.isBlank) return true
                    is Element -> if (sibling.text().isNotBlank()) return true
                }
                sibling = sibling.previousSibling()
            }

            val parent = node.parent() ?: break
            if (parent.nodeName().equals("body", ignoreCase = true)) break
            node = parent
        }
        return false
    }

    private fun Document.addCustomHeadContents() = apply {
        head().append(htmlHeadProvider.headHtml)
    }

    private fun Document.toCompactString(): String {
        outputSettings()
            .prettyPrint(false)
            .indentAmount(0)

        return html()
    }

    private fun Document.addCustomClasses() = apply {
        if (customClasses.isNotEmpty()) {
            body().apply {
                customClasses.forEach(::addClass)
            }
        }
    }

    private companion object {
        const val QUOTE_BOUNDARY_SELECTOR = "blockquote, .gmail_quote, #divRplyFwdMsg, #appendonsend"
        const val QUOTE_FOLD_CLASS = "k9mail-quote-fold"
        const val QUOTE_TOGGLE_CLASS = "k9mail-quote-toggle"
        const val QUOTE_TOGGLE_LABEL = "•••"
    }
}
