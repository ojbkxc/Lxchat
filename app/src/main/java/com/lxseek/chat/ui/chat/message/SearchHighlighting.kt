package com.lxseek.chat.ui.chat.message

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import com.lxseek.chat.ui.chat.caseInsensitiveMatchRanges

// Search highlight colors are intentionally hardcoded amber/yellow to match the
// universal "find in page" convention (browsers, editors, IDEs) where high-contrast
// yellow-on-dark-text is the expected affordance. Mapping to tertiaryContainer would
// vary per theme and reduce scan-ability; the brown foreground (0xFF241A00) preserves
// WCAG AA contrast on the amber background across light/dark schemes.
internal val SearchHighlightBackground = Color(0xFFFFD54F)
internal val ActiveSearchHighlightBackground = Color(0xFFFFA000)
private val SearchHighlightForeground = Color(0xFF241A00)

internal data class SearchHighlightSpec(
    val query: String,
    val activeRange: IntRange?,
    val activeKey: String?,
    val matchKeys: List<String>,
    val onMatchPosition: (key: String, centerYInRoot: Float) -> Unit,
)

internal data class DisplaySearchMatch(
    val key: String,
    val displayRange: IntRange,
)

internal fun highlightedSearchText(
    text: AnnotatedString,
    query: String,
    activeOccurrence: Int?,
    highlightColor: Color,
    activeHighlightColor: Color,
): Pair<AnnotatedString, IntRange?> {
    val ranges = caseInsensitiveMatchRanges(text.text, query)
    if (ranges.isEmpty()) return text to null
    val builder = AnnotatedString.Builder(text)
    ranges.forEachIndexed { index, range ->
        builder.addStyle(
            SpanStyle(
                background = if (index == activeOccurrence) {
                    activeHighlightColor
                } else {
                    highlightColor
                },
                color = SearchHighlightForeground,
            ),
            start = range.first,
            end = range.last + 1,
        )
    }
    return builder.toAnnotatedString() to activeOccurrence?.let(ranges::getOrNull)
}

@Composable
internal fun SearchHighlightedPlainText(
    text: String,
    style: TextStyle,
    color: Color,
    spec: SearchHighlightSpec?,
    modifier: Modifier = Modifier,
) {
    val highlightColor = SearchHighlightBackground
    val activeHighlightColor = ActiveSearchHighlightBackground
    val ranges = remember(text, spec?.query) {
        caseInsensitiveMatchRanges(text, spec?.query.orEmpty())
    }
    val activeOccurrence = spec?.activeRange?.let { active ->
        ranges.indexOfFirst { it.first == active.first && it.last == active.last }
            .takeIf { it >= 0 }
    }
    val highlighted = remember(
        text,
        spec?.query,
        activeOccurrence,
        highlightColor,
        activeHighlightColor,
    ) {
        highlightedSearchText(
            text = AnnotatedString(text),
            query = spec?.query.orEmpty(),
            activeOccurrence = activeOccurrence,
            highlightColor = highlightColor,
            activeHighlightColor = activeHighlightColor,
        )
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    ReportSearchPositions(
        spec = spec,
        displayMatches = ranges.mapIndexedNotNull { index, range ->
            spec?.matchKeys?.getOrNull(index)?.let { key ->
                DisplaySearchMatch(key, range)
            }
        },
        layoutResult = layoutResult,
        coordinates = coordinates,
    )
    Text(
        text = highlighted.first,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it },
        modifier = modifier.onGloballyPositioned { coordinates = it },
    )
}

@Composable
internal fun ReportSearchPositions(
    spec: SearchHighlightSpec?,
    displayMatches: List<DisplaySearchMatch>,
    layoutResult: TextLayoutResult?,
    coordinates: LayoutCoordinates?,
) {
    LaunchedEffect(spec?.query, displayMatches, layoutResult, coordinates) {
        val activeSpec = spec ?: return@LaunchedEffect
        val layout = layoutResult ?: return@LaunchedEffect
        val coords = coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val rootY = coords.positionInRoot().y
        displayMatches.forEach { match ->
            if (match.displayRange.first in 0 until layout.layoutInput.text.length) {
                val box = layout.getBoundingBox(match.displayRange.first)
                activeSpec.onMatchPosition(
                    match.key,
                    rootY + (box.top + box.bottom) / 2f,
                )
            }
        }
    }
}
