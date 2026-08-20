package com.lxseek.chat.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/**
 * The Compose renderer does not provide default output for inline HTML AST nodes. Treat model
 * output as literal Markdown text instead of executable HTML and append the original source range.
 */
internal val literalHtmlMarkdownAnnotator: MarkdownAnnotator = markdownAnnotator { content, child ->
    if (child.type == MarkdownTokenTypes.HTML_TAG) {
        append(child.getTextInNode(content))
        true
    } else {
        false
    }
}

internal fun literalHtmlBlockText(content: String, node: ASTNode): String? =
    if (node.type == MarkdownElementTypes.HTML_BLOCK) {
        node.getTextInNode(content).toString()
    } else {
        null
    }

/**
 * Block HTML bypasses the annotated-string path entirely, so render its exact source range as
 * selectable plain text. The original Markdown source remains available for selection semantics.
 */
@Composable
internal fun LiteralHtmlMarkdownBlock(
    model: MarkdownComponentModel,
    modifier: Modifier = Modifier,
) {
    val text = remember(model.content, model.node) {
        AnnotatedString(requireNotNull(literalHtmlBlockText(model.content, model.node)))
    }
    MarkdownText(
        content = text,
        node = model.node,
        modifier = modifier,
        style = model.typography.paragraph,
        sourceContent = model.content,
    )
}
