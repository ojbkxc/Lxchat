package com.lxseek.chat.ui.chat.message

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.lxseek.chat.util.NoAutoScrollSelectionContainer
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

private const val STREAM_TAIL_FADE_CODE_POINTS = 42
private const val STREAM_TAIL_ALPHA_BANDS = 6
private const val STREAM_TAIL_NEWEST_ALPHA = 0.38f
private const val STREAM_TAIL_ALPHA_PER_SECOND = 2f
private const val STREAM_TAIL_FADE_TICK_MS = 40L
private const val LONG_DOCUMENT_THRESHOLD_CHARS = 8_000
private const val LONG_DOCUMENT_RENDER_INTERVAL_MS = 120L

/**
 * A parsed block whose source range is closed and can therefore keep the same identity for the
 * rest of an append-only generation. Its Markdown tree is built exactly once off the main thread.
 */
@Stable
internal class StableMarkdownBlock(
    val startOffset: Int,
    val endOffset: Int,
    val sourceContent: String,
    val root: ASTNode,
) {
    val identity: Int = 31 * startOffset + sourceContent.hashCode()
}

/**
 * The only mutable Markdown block in an append-only document. It is reparsed off the main thread,
 * but it is always rendered through the exact same Markdown component tree as stable and final
 * blocks. Keeping a dedicated tail identity also preserves its composition across stream → final.
 */
@Stable
internal class LiveMarkdownBlock(
    val startOffset: Int,
    val sourceContent: String,
    val root: ASTNode,
) {
    val lastVisibleSourceOffset: Int? =
        sourceContent.indexOfLast { !it.isWhitespace() }.takeIf { it >= 0 }?.plus(1)
}

@Immutable
internal data class StreamingMarkdownSnapshot(
    val inputContent: String,
    val stableBlocks: List<StableMarkdownBlock>,
    val tail: String,
    val liveBlock: LiveMarkdownBlock?,
    val isStreaming: Boolean,
)

private data class StreamingMarkdownInput(
    val revision: Long,
    val content: String,
    val isStreaming: Boolean,
)

/**
 * Identifies the final visible source position in the live Markdown block. Markdown text
 * components use it to apply alpha to only the actual trailing text node, without changing any
 * typography, padding, measurement, or Markdown structure.
 */
@Immutable
internal data class StreamingGlyphFadeSpec(
    val lastVisibleSourceOffset: Int?,
)

internal val LocalStreamingGlyphFadeSpec =
    compositionLocalOf<StreamingGlyphFadeSpec?> { null }

@Immutable
internal data class StreamingTailFadeSample(
    val observedAtMs: Long,
    val birthTimesMs: LongArray,
)

/**
 * Retains the arrival time of only the bounded fading suffix. Appends never reset older glyphs;
 * when the Markdown scanner promotes a prefix, suffix timestamps are retained as well.
 */
internal class StreamingTailFadeTracker(
    private val capacity: Int = STREAM_TAIL_FADE_CODE_POINTS,
) {
    private var previousText = ""
    private val birthTimesMs = java.util.ArrayDeque<Long>()

    fun update(text: String, nowMs: Long): StreamingTailFadeSample {
        require(nowMs >= 0L)
        if (capacity <= 0) {
            previousText = text
            birthTimesMs.clear()
            return StreamingTailFadeSample(nowMs, LongArray(0))
        }

        when {
            text == previousText -> Unit
            text.startsWith(previousText) -> {
                val appendedCount = text.codePointCount(previousText.length, text.length)
                appendBirths(appendedCount, nowMs)
            }
            previousText.endsWith(text) -> {
                // A closed Markdown block was promoted out of the live tail. The remaining text is
                // the old suffix, so its glyph ages remain valid.
                val keep = min(text.codePointCount(0, text.length), capacity)
                while (birthTimesMs.size > keep) birthTimesMs.removeFirst()
            }
            else -> {
                birthTimesMs.clear()
                appendBirths(min(text.codePointCount(0, text.length), capacity), nowMs)
            }
        }
        previousText = text
        return StreamingTailFadeSample(
            observedAtMs = nowMs,
            birthTimesMs = birthTimesMs.map { it }.toLongArray(),
        )
    }

    private fun appendBirths(count: Int, nowMs: Long) {
        if (count <= 0) return
        if (count >= capacity) {
            birthTimesMs.clear()
            repeat(capacity) { birthTimesMs.addLast(nowMs) }
            return
        }
        repeat(count) {
            birthTimesMs.addLast(nowMs)
            if (birthTimesMs.size > capacity) birthTimesMs.removeFirst()
        }
    }
}

private data class MarkdownFence(
    val marker: Char,
    val length: Int,
)

/**
 * Kelivo-style append-only scanner with stronger CommonMark fence handling. Only appended code
 * units are scanned. Closed blocks are parsed once; only the still-open tail is reparsed as tokens
 * arrive, and both kinds of block use the same Markdown rendering path.
 */
internal class IncrementalMarkdownDocument(
    private val flavour: MarkdownFlavourDescriptor,
) {
    private var source = ""
    private val stableBlocks = mutableListOf<StableMarkdownBlock>()
    private var scanCursor = 0
    private var lineStart = 0
    private var blockStart = 0
    private var fence: MarkdownFence? = null
    private var finalized = false
    private var liveBlock: LiveMarkdownBlock? = null

    internal var scannedCodeUnits: Long = 0L
        private set

    fun update(
        preparedSource: String,
        inputContent: String,
        isStreaming: Boolean,
    ): StreamingMarkdownSnapshot {
        if (
            preparedSource == source &&
            ((isStreaming && !finalized) || (!isStreaming && finalized))
        ) {
            return snapshot(inputContent, isStreaming)
        }

        val appendOnly = !finalized && preparedSource.startsWith(source)
        if (!appendOnly) {
            reset()
            scannedCodeUnits += preparedSource.length
        } else {
            scannedCodeUnits += preparedSource.length - source.length
        }
        source = preparedSource
        finalized = false

        scanCompletedLines()
        if (!isStreaming) {
            // Do not promote the live tail at terminalization. Its dedicated keyed composition
            // must survive stream → final so selection/status changes cannot replace the Markdown
            // subtree or briefly collapse its measured height.
            finalized = true
        }
        updateLiveBlock()
        return snapshot(inputContent, isStreaming)
    }

    private fun reset() {
        source = ""
        stableBlocks.clear()
        scanCursor = 0
        lineStart = 0
        blockStart = 0
        fence = null
        finalized = false
        liveBlock = null
    }

    private fun scanCompletedLines() {
        while (scanCursor < source.length) {
            val newline = source.indexOf('\n', scanCursor)
            if (newline < 0) {
                // Resume from this position when the incomplete line receives more characters.
                scanCursor = source.length
                return
            }

            val line = source.substring(lineStart, newline).removeSuffix("\r")
            updateFence(line)
            if (fence == null && line.isBlank() && lineStart > blockStart) {
                val end = newline + 1
                if (commit(blockStart, end)) {
                    blockStart = end
                    liveBlock = null
                }
            }
            lineStart = newline + 1
            scanCursor = newline + 1
        }
    }

    private fun updateFence(line: String) {
        var indent = 0
        while (indent < line.length && indent < 4 && line[indent] == ' ') indent++
        if (indent > 3 || indent >= line.length) return

        val marker = line[indent]
        if (marker != '`' && marker != '~') return
        var markerEnd = indent
        while (markerEnd < line.length && line[markerEnd] == marker) markerEnd++
        val markerLength = markerEnd - indent
        if (markerLength < 3) return

        val active = fence
        if (active == null) {
            fence = MarkdownFence(marker, markerLength)
        } else if (
            marker == active.marker &&
            markerLength >= active.length &&
            line.substring(markerEnd).isBlank()
        ) {
            fence = null
        }
    }

    private fun commit(start: Int, end: Int): Boolean {
        if (end <= start) return true
        val blockText = source.substring(start, end)
        if (blockText.isBlank()) return true
        val root = runCatching {
            MarkdownParser(flavour).buildMarkdownTreeFromString(blockText)
        }.getOrNull() ?: return false
        stableBlocks += StableMarkdownBlock(
            startOffset = start,
            endOffset = end,
            sourceContent = blockText,
            root = root,
        )
        return true
    }

    private fun updateLiveBlock() {
        val tail = source.substring(blockStart.coerceIn(0, source.length))
        if (tail.isEmpty()) {
            liveBlock = null
            return
        }
        if (liveBlock?.startOffset == blockStart && liveBlock?.sourceContent == tail) return
        val root = runCatching {
            MarkdownParser(flavour).buildMarkdownTreeFromString(tail)
        }.getOrNull() ?: return
        liveBlock = LiveMarkdownBlock(
            startOffset = blockStart,
            sourceContent = tail,
            root = root,
        )
    }

    private fun snapshot(
        inputContent: String,
        isStreaming: Boolean,
    ): StreamingMarkdownSnapshot = StreamingMarkdownSnapshot(
        inputContent = inputContent,
        stableBlocks = stableBlocks.toList(),
        tail = source.substring(blockStart.coerceIn(0, source.length)),
        liveBlock = liveBlock,
        isStreaming = isStreaming,
    )
}

/**
 * One persistent worker per rendered message. A conflated channel keeps only the newest pending
 * snapshot while the current delta is parsed, so CPU parsing is sequential and can never pile up.
 */
@Stable
private class StreamingMarkdownRenderState(
    flavour: MarkdownFlavourDescriptor,
    initialContent: String,
    initialIsStreaming: Boolean,
) {
    private val document = IncrementalMarkdownDocument(flavour)
    private val inputs = Channel<StreamingMarkdownInput>(Channel.CONFLATED)
    private val offeredRevision = AtomicLong(0L)
    private val _snapshot = MutableStateFlow(
        StreamingMarkdownSnapshot(
            inputContent = initialContent,
            stableBlocks = emptyList(),
            tail = initialContent,
            liveBlock = null,
            isStreaming = initialIsStreaming,
        )
    )
    val snapshot: StateFlow<StreamingMarkdownSnapshot> = _snapshot.asStateFlow()

    fun offer(content: String, isStreaming: Boolean) {
        val revision = offeredRevision.incrementAndGet()
        inputs.trySend(StreamingMarkdownInput(revision, content, isStreaming))
    }

    suspend fun run() {
        var lastRenderedAtMs = 0L
        for (received in inputs) {
            var input = received
            while (true) {
                // Inputs are conflated while a long tail waits/parses. Consume the newest value
                // before every cadence decision. Polling at most once per display frame lets a
                // terminal/stop snapshot bypass the long-document 120 ms cadence immediately.
                while (true) {
                    val newer = inputs.tryReceive().getOrNull() ?: break
                    input = newer
                }
                val minimumIntervalMs =
                    if (
                        input.isStreaming &&
                        input.content.length >= LONG_DOCUMENT_THRESHOLD_CHARS
                    ) {
                        LONG_DOCUMENT_RENDER_INTERVAL_MS
                    } else {
                        0L
                    }
                val remainingDelay =
                    (lastRenderedAtMs + minimumIntervalMs - SystemClock.uptimeMillis())
                        .coerceAtLeast(0L)
                if (remainingDelay <= 0L) break
                delay(minOf(remainingDelay, 16L))
            }
            val next = withContext(Dispatchers.Default) {
                document.update(
                    preparedSource = input.content.toRenderableMarkdownText(),
                    inputContent = input.content,
                    isStreaming = input.isStreaming,
                )
            }
            // Parsing is not cooperatively cancellable. A revision gate provides mapLatest
            // semantics anyway: if tokens arrived during parsing, keep the previous measured tree
            // until the newest parse succeeds instead of flashing this stale snapshot.
            if (offeredRevision.get() == input.revision) {
                _snapshot.value = next
                lastRenderedAtMs = SystemClock.uptimeMillis()
            }
        }
    }

    fun close() {
        inputs.close()
    }
}

@Composable
internal fun StreamingMarkdownDocument(
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
    modifier: Modifier = Modifier,
    selectionEnabled: Boolean = !isStreaming,
) {
    var hasStreamed by remember { mutableStateOf(isStreaming) }
    SideEffect {
        if (isStreaming) hasStreamed = true
    }

    // A historical message can use the library's normal full-document path. A message that was
    // observed streaming stays on the incremental path during terminalization, preserving every
    // already-rendered stable block instead of replacing the whole subtree at Stop/Done.
    if (!isStreaming && !hasStreamed) {
        MarkdownSelectionHost(selectionEnabled) {
            Column(modifier = modifier) {
                MarkdownTextContent(
                    text = content,
                    renderContext = renderContext,
                )
            }
        }
        return
    }

    val state = remember(renderContext.flavour) {
        StreamingMarkdownRenderState(
            flavour = renderContext.flavour,
            initialContent = content,
            initialIsStreaming = isStreaming,
        )
    }
    LaunchedEffect(state) {
        state.run()
    }
    LaunchedEffect(state, content, isStreaming) {
        // One offer per actual input snapshot. A SideEffect here also ran after fade-clock
        // recompositions and needlessly woke the parser worker for unchanged text.
        state.offer(content, isStreaming)
    }
    DisposableEffect(state) {
        onDispose(state::close)
    }

    val snapshot by state.snapshot.collectAsState()
    MarkdownSelectionHost(selectionEnabled) {
        Column(modifier = modifier) {
            snapshot.stableBlocks.forEach { block ->
                key(block.startOffset, block.identity) {
                    StableMarkdownBlockContent(block, renderContext)
                }
            }
            snapshot.liveBlock?.let { block ->
                // The key is the tail's document start, not its changing content. Appending text
                // and terminalization therefore retain the same Markdown subtree and fade clocks.
                key("live-tail", block.startOffset) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalStreamingGlyphFadeSpec provides StreamingGlyphFadeSpec(
                            lastVisibleSourceOffset = block.lastVisibleSourceOffset,
                        )
                    ) {
                        ParsedMarkdownBlockContent(
                            sourceContent = block.sourceContent,
                            root = block.root,
                            renderContext = renderContext,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownSelectionHost(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    // A stable selection owner is cheaper and safer than replacing the complete Markdown subtree
    // at stream completion. This mirrors Kelivo's persistent SelectionArea; the renderer never
    // changes call sites merely because the message became terminal.
    NoAutoScrollSelectionContainer(
        enabled = enabled,
        content = content,
    )
}

@Composable
private fun StableMarkdownBlockContent(
    block: StableMarkdownBlock,
    renderContext: ChatMarkdownRenderContext,
) {
    ParsedMarkdownBlockContent(
        sourceContent = block.sourceContent,
        root = block.root,
        renderContext = renderContext,
    )
}

@Composable
private fun ParsedMarkdownBlockContent(
    sourceContent: String,
    root: ASTNode,
    renderContext: ChatMarkdownRenderContext,
) {
    val state = remember(sourceContent, root) {
        State.Success(
            node = root,
            content = sourceContent,
            linksLookedUp = false,
            referenceLinkHandler = ReferenceLinkHandlerImpl(),
        )
    }
    com.mikepenz.markdown.compose.Markdown(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        colors = renderContext.colors,
        typography = renderContext.typography,
        padding = renderContext.padding,
        components = renderContext.components,
        annotator = renderContext.annotator,
        imageTransformer = renderContext.imageTransformer,
        animations = markdownAnimations { this },
        success = { successState, components, successModifier ->
            Column(successModifier) {
                successState.node.children.forEach { node ->
                    MarkdownElement(
                        node = node,
                        components = components,
                        content = successState.content,
                        // The normal full-document renderer includes the block spacer for its
                        // first node too. Matching that contract removes live/final padding drift.
                        includeSpacer = true,
                    )
                }
            }
        },
    )
}

/**
 * Applies alpha directly to a bounded glyph suffix. Spatial alpha makes the newest glyphs lighter;
 * `alpha(x,t) = min(1, alphaSpatial(x) + k*t)` independently makes every glyph solid with age.
 * Adjacent glyphs with equal alpha are coalesced, keeping the number of spans bounded.
 */
internal fun streamingTailAnnotatedString(
    text: String,
    color: Color,
    fadeCodePoints: Int = STREAM_TAIL_FADE_CODE_POINTS,
    bands: Int = STREAM_TAIL_ALPHA_BANDS,
    newestAlpha: Float = STREAM_TAIL_NEWEST_ALPHA,
    birthTimesMs: LongArray? = null,
    nowMs: Long = 0L,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): AnnotatedString = streamingTailAnnotatedString(
    text = AnnotatedString(text),
    color = color,
    fadeCodePoints = fadeCodePoints,
    bands = bands,
    newestAlpha = newestAlpha,
    birthTimesMs = birthTimesMs,
    nowMs = nowMs,
    alphaPerSecond = alphaPerSecond,
)

/**
 * Adds only foreground-color spans to an already-rendered Markdown [AnnotatedString]. Existing
 * emphasis, links, inline-code, search highlights, font metrics, and paragraph layout are retained.
 */
internal fun streamingTailAnnotatedString(
    text: AnnotatedString,
    color: Color,
    fadeCodePoints: Int = STREAM_TAIL_FADE_CODE_POINTS,
    bands: Int = STREAM_TAIL_ALPHA_BANDS,
    newestAlpha: Float = STREAM_TAIL_NEWEST_ALPHA,
    birthTimesMs: LongArray? = null,
    nowMs: Long = 0L,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): AnnotatedString {
    if (text.isEmpty() || fadeCodePoints <= 0 || bands <= 0) return text
    val rawText = text.text
    val codePointCount = rawText.codePointCount(0, rawText.length)
    val fadedCount = min(codePointCount, fadeCodePoints)
    if (fadedCount == 0) return text

    val prefixCodePoints = codePointCount - fadedCount
    val actualBands = min(bands, fadedCount)
    val builder = AnnotatedString.Builder().apply { append(text) }
    var rangeStartCodePoint = prefixCodePoints
    var rangeAlpha: Float? = null

    fun flushRange(endCodePoint: Int) {
        val alpha = rangeAlpha ?: return
        if (alpha < 0.999f) {
            builder.addStyle(
                SpanStyle(color = color.copy(alpha = color.alpha * alpha)),
                rawText.offsetByCodePoints(0, rangeStartCodePoint),
                rawText.offsetByCodePoints(0, endCodePoint),
            )
        }
    }

    for (suffixIndex in 0 until fadedCount) {
        val band = suffixIndex * actualBands / fadedCount
        val progress = (band + 1).toFloat() / actualBands.toFloat()
        val spatialAlpha = 1f - progress * (1f - newestAlpha.coerceIn(0f, 1f))
        val bornAt = birthTimesMs
            ?.takeIf { it.size == fadedCount }
            ?.get(suffixIndex)
        val ageSeconds = if (bornAt == null) {
            0f
        } else {
            (nowMs - bornAt).coerceAtLeast(0L) / 1_000f
        }
        val alpha = (spatialAlpha + alphaPerSecond.coerceAtLeast(0f) * ageSeconds)
            .coerceIn(0f, 1f)
        if (rangeAlpha == null) {
            rangeAlpha = alpha
        } else if (kotlin.math.abs(checkNotNull(rangeAlpha) - alpha) > 0.0001f) {
            flushRange(prefixCodePoints + suffixIndex)
            rangeStartCodePoint = prefixCodePoints + suffixIndex
            rangeAlpha = alpha
        }
    }
    flushRange(codePointCount)
    return builder.toAnnotatedString()
}

/**
 * Owns the bounded time component of the glyph fade inside the final Markdown text composable.
 * Only this leaf recomposes while alpha changes; the parser, block column, and LazyColumn do not.
 */
@Composable
internal fun rememberStreamingGlyphFade(
    content: AnnotatedString,
    color: Color,
    enabled: Boolean,
): AnnotatedString {
    if (!enabled || content.isEmpty()) return content

    val fadeTracker = remember { StreamingTailFadeTracker() }
    val fadeSample = remember(content.text, fadeTracker) {
        fadeTracker.update(content.text, SystemClock.uptimeMillis())
    }
    var fadeClockMs by remember(fadeSample) {
        mutableLongStateOf(fadeSample.observedAtMs)
    }
    LaunchedEffect(fadeSample) {
        while (
            streamingTailFadeActive(
                birthTimesMs = fadeSample.birthTimesMs,
                nowMs = fadeClockMs,
            )
        ) {
            delay(STREAM_TAIL_FADE_TICK_MS)
            fadeClockMs = SystemClock.uptimeMillis()
        }
    }
    return remember(content, color, fadeSample, fadeClockMs) {
        streamingTailAnnotatedString(
            text = content,
            color = color,
            birthTimesMs = fadeSample.birthTimesMs,
            nowMs = fadeClockMs,
        )
    }
}

internal fun streamingTailFadeActive(
    birthTimesMs: LongArray,
    nowMs: Long,
    newestAlpha: Float = STREAM_TAIL_NEWEST_ALPHA,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): Boolean {
    if (birthTimesMs.isEmpty() || alphaPerSecond <= 0f) return false
    val newestAgeSeconds = (nowMs - birthTimesMs.last()).coerceAtLeast(0L) / 1_000f
    return newestAlpha.coerceIn(0f, 1f) + alphaPerSecond * newestAgeSeconds < 0.999f
}
