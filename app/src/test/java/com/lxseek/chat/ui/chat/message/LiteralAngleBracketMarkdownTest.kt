package com.lxseek.chat.ui.chat.message

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralAngleBracketMarkdownTest {
    @Test
    fun renderPreparationLeavesHtmlLookingTextUntouched() {
        val source = "<widget id=\"x\">value</widget> <T>"

        assertEquals(source, source.toRenderableMarkdownText())
        assertFalse(source.toRenderableMarkdownText().contains('\u200B'))
    }

    @Test
    fun inlineHtmlNodesRenderTheirExactSourceText() {
        listOf(
            "<widget title=\"1 > 0\">value</widget>",
            "<button>Click me</button>",
            "<a href=\"https://example.com\">Example link</a>",
        ).forEach { source ->
            val root = parse(source)
            assertTrue(root.types().contains(MarkdownTokenTypes.HTML_TAG))
            val paragraph = root.children.single {
                it.type == MarkdownElementTypes.PARAGRAPH
            }

            assertEquals(
                source,
                source.buildMarkdownAnnotatedString(
                    textNode = paragraph,
                    style = TextStyle.Default,
                    annotatorSettings = literalHtmlAnnotatorSettings,
                ).text,
            )
        }
    }

    @Test
    fun unmatchedBacktickDoesNotHideFollowingHtmlTags() {
        val source = "`oops <button>Click</button>"
        val root = parse(source)
        assertTrue(root.types().contains(MarkdownTokenTypes.HTML_TAG))
        val paragraph = root.children.single {
            it.type == MarkdownElementTypes.PARAGRAPH
        }

        val rendered = source.buildMarkdownAnnotatedString(
            textNode = paragraph,
            style = TextStyle.Default,
            annotatorSettings = literalHtmlAnnotatorSettings,
        ).text

        assertTrue(rendered.contains("<button>Click</button>"))
    }

    @Test
    fun issueHtmlBlockRendersItsExactSourceRange() {
        val source = """
            Below are some basic HTML elements shown literally:

            <input type="text" name="username">
            <button>Click me</button>
            <a href="https://example.com">Example link</a>
        """.trimIndent()
        val root = parse(source)
        val htmlBlock = root.children.single {
            it.type == MarkdownElementTypes.HTML_BLOCK
        }
        val blockText = literalHtmlBlockText(source, htmlBlock)

        assertEquals(
            source.substring(htmlBlock.startOffset, htmlBlock.endOffset),
            blockText,
        )
        assertTrue(requireNotNull(blockText).contains("<input type=\"text\" name=\"username\">"))
        assertTrue(blockText.contains("<button>Click me</button>"))
        assertTrue(blockText.contains("<a href=\"https://example.com\">Example link</a>"))
        assertNull(literalHtmlBlockText(source, root.children.first()))

        val standalone = "<T>"
        val standaloneBlock = parse(standalone).children.single {
            it.type == MarkdownElementTypes.HTML_BLOCK
        }
        assertEquals(standalone, literalHtmlBlockText(standalone, standaloneBlock))
    }

    @Test
    fun commonMarkAutolinksAndCodeKeepParserSemantics() {
        val autolinks = "<irc://example.org/channel> <foo:bar> <person@example.com>"
        val autolinkRoot = parse(autolinks)
        assertEquals(autolinks, autolinks.toRenderableMarkdownText())
        assertEquals(2, autolinkRoot.countType(MarkdownElementTypes.AUTOLINK))
        assertEquals(1, autolinkRoot.countType(MarkdownTokenTypes.EMAIL_AUTOLINK))

        val code = """
            `<widget>`
            ```
            code
            ```not-close <button>
            more
            ```
        """.trimIndent()
        val codeRoot = parse(code)
        assertEquals(code, code.toRenderableMarkdownText())
        assertTrue(codeRoot.types().contains(MarkdownElementTypes.CODE_SPAN))
        assertTrue(codeRoot.types().contains(MarkdownElementTypes.CODE_FENCE))
        val fence = codeRoot.children.single {
            it.type == MarkdownElementTypes.CODE_FENCE
        }
        assertFalse(fence.types().contains(MarkdownTokenTypes.HTML_TAG))
    }

    @Test
    fun streamedTagCompletionKeepsPreparedSourceAppendOnly() {
        val chunks = listOf(
            "<",
            "<button",
            "<button>",
            "<button>Click",
            "<button>Click</button>",
        )
        val prepared = chunks.map(String::toRenderableMarkdownText)

        prepared.zipWithNext().forEach { (previous, next) ->
            assertTrue(next.startsWith(previous))
        }

        val final = prepared.last()
        val paragraph = parse(final).children.single {
            it.type == MarkdownElementTypes.PARAGRAPH
        }
        assertEquals(
            chunks.last(),
            final.buildMarkdownAnnotatedString(
                textNode = paragraph,
                style = TextStyle.Default,
                annotatorSettings = literalHtmlAnnotatorSettings,
            ).text,
        )
    }

    private fun parse(source: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

    private fun ASTNode.types(): Set<Any> = buildSet {
        fun visit(node: ASTNode) {
            add(node.type)
            node.children.forEach(::visit)
        }
        visit(this@types)
    }

    private fun ASTNode.countType(type: Any): Int {
        var count = 0
        fun visit(node: ASTNode) {
            if (node.type == type) count++
            node.children.forEach(::visit)
        }
        visit(this)
        return count
    }

    private val literalHtmlAnnotatorSettings = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(),
        codeSpanStyle = SpanStyle(),
        annotator = literalHtmlMarkdownAnnotator,
    )
}
