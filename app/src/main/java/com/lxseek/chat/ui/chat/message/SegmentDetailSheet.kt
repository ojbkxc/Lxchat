package com.lxseek.chat.ui.chat.message

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lxseek.chat.ui.theme.LxDesign
import androidx.compose.foundation.verticalScroll
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.lxseek.chat.ui.components.DialogWindowEdgeToEdge
import com.lxseek.chat.ui.motion.LocalLxChatMotionPolicy
import com.lxseek.chat.R
import com.lxseek.chat.model.ChatMessage

import com.lxseek.chat.util.noOpBringIntoView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Segment detail bottom sheet (custom implementation).
//
// A self-contained draggable bottom sheet with its own finite-state machine
// (Collapsed / Half / Full) driving an Animatable fraction; the whole gesture +
// snap + dim subsystem lives here. The host (MessageItem) only decides WHICH
// segment(s) to show and toggles visibility via [onDismiss].
@Composable
internal fun SegmentDetailSheet(
    message: ChatMessage,
    selectedSegmentIndex: Int,
    selectedSegmentIndices: List<Int>,
    isStreaming: Boolean,
    markdownRenderContext: ChatMarkdownRenderContext,
    onMediaClick: (List<String>, Int) -> Unit,
    titleOverride: String? = null,
    detailFooter: (@Composable () -> Unit)? = null,
    handleBackInternally: Boolean = false,
    onDismiss: () -> Unit
) {
    val liveSegs = remember(message.segments) {
        mergeAdjacentSegments(message.segments.orEmpty()).filter { it.type != "answer" }
    }
    val selectedSegs = remember(liveSegs, selectedSegmentIndices, selectedSegmentIndex) {
        selectedSegmentIndices.mapNotNull { liveSegs.getOrNull(it) }
            .ifEmpty { liveSegs.getOrNull(selectedSegmentIndex)?.let { listOf(it) }.orEmpty() }
    }
    val seg = selectedSegs.firstOrNull()
    if (seg == null) {
        onDismiss()
    } else {
        val motionPolicy = LocalLxChatMotionPolicy.current
        val density = LocalDensity.current
        val screenHeightPx =
            LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val lazyDetailListState = rememberLazyListState()
        val usesVirtualizedSingleMarkdown =
            selectedSegs.size == 1 &&
                seg.type != "tool" &&
                !(seg.type == "transcription" && seg.content.isBlank()) &&
                !isStreaming &&
                detailFooter == null

        val PARTIAL = 0.45f
        val FULL = 0.94f

        // ── Finite state machine ──
        // Collapsed = 0, Half = PARTIAL, Full = FULL
        // Full is only entered when animateTo(FULL) completes naturally.
        val PHASE_COLLAPSED = 0; val PHASE_HALF = 1; val PHASE_FULL = 2
        var phase by remember { mutableIntStateOf(PHASE_HALF) }

        var rawFraction by remember { mutableFloatStateOf(0f) }
        val visualFraction = remember { Animatable(0f) }
        var snapJob by remember { mutableStateOf<Job?>(null) }
        var dismissing by remember { mutableStateOf(false) }

        val snapSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 350f, visibilityThreshold = 0.001f)

        // ── Snap target: midline (0.5) × velocity direction ──
        // velSign > 0 = upward (expanding), velSign < 0 = downward (collapsing)
        fun snapTarget(pos: Float, velSign: Float): Float {
            val goingUp = velSign >= 0f
            return when {
                pos > 0.5f && goingUp -> FULL      // upper half + up → full
                pos > 0.5f && !goingUp -> PARTIAL  // upper half + down → half
                pos <= 0.5f && goingUp -> PARTIAL  // lower half + up → half
                else -> 0f                          // lower half + down → collapsed
            }
        }

        // ── Single animation entry point. Sets phase after animation completes. ──
        fun animateTo(target: Float) {
            snapJob?.cancel()
            snapJob = coroutineScope.launch {
                if (motionPolicy.allowSpatialTransitions) {
                    visualFraction.animateTo(target, snapSpring)
                } else {
                    visualFraction.snapTo(target)
                }
                rawFraction = visualFraction.value
                phase = when (target) {
                    FULL -> PHASE_FULL
                    PARTIAL -> PHASE_HALF
                    else -> PHASE_COLLAPSED
                }
                if (target == 0f) onDismiss()
            }
        }

        fun dismiss() { dismissing = true; animateTo(0f) }

        BackHandler(enabled = handleBackInternally && !dismissing) { dismiss() }

        // ── Grab: interrupt animation, sync raw to current visual position ──
        fun grabSheet() {
            if (dismissing) return
            if (snapJob?.isActive == true) {
                snapJob?.cancel()
                rawFraction = visualFraction.value
            }
        }

        // ── Initial appearance ──
        LaunchedEffect(Unit) {
            animateTo(PARTIAL)
            snapJob?.join()
            rawFraction = PARTIAL
        }

        // ── Safety-net snap: if drag ends without fling (velocity ≈ 0) ──
        LaunchedEffect(rawFraction) {
            if (dismissing || snapJob?.isActive == true) return@LaunchedEffect
            val pos = rawFraction
            delay(80)
            if (dismissing || pos != rawFraction || snapJob?.isActive == true) return@LaunchedEffect
            val target = snapTarget(pos, 0f)
            if (abs(target - pos) > 0.01f) animateTo(target)
        }

        // ── Dim: update the native window only while the sheet is actually moving. ──
        //
        // An unconditional frame loop kept both the UI thread and RenderThread awake after the
        // spring had settled. Animatable is snapshot-backed, so this collector sleeps at rest and
        // still emits every visual change during drag/snap animations.
        val dialogWindowRef = remember { mutableStateOf<android.view.Window?>(null) }

        LaunchedEffect(dialogWindowRef.value) {
            val window = dialogWindowRef.value ?: return@LaunchedEffect
            snapshotFlow { visualFraction.value }
                .map { fraction -> (0.32f * fraction).coerceIn(0f, 1f) }
                .distinctUntilChanged()
                .collect { dimAmount ->
                    val attributes = window.attributes
                    if (attributes.dimAmount != dimAmount) {
                        attributes.dimAmount = dimAmount
                        window.attributes = attributes
                    }
                }
        }

        // ── NestedScrollConnection ──
        // Half: content does NOT scroll — all delta goes to sheet expansion.
        // Full: content scrolls normally. Exit Full ONLY when content at top
        //       and finger still dragging down (source == Drag).
        val sheetScrollConnection = remember(usesVirtualizedSingleMarkdown) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!dismissing && phase != PHASE_FULL) {
                        grabSheet()
                        val delta = -available.y / screenHeightPx
                        rawFraction = (rawFraction + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        if (rawFraction >= FULL && available.y < 0f) phase = PHASE_FULL
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero // Full: let content scroll
                }

                override fun onPostScroll(
                    consumed: Offset, available: Offset, source: NestedScrollSource
                ): Offset {
                    if (dismissing) return Offset.Zero
                    // Exit Full → Half: content at top + finger dragging down
                    if (phase == PHASE_FULL
                        && available.y > 0f
                        && if (usesVirtualizedSingleMarkdown) {
                            lazyDetailListState.firstVisibleItemIndex == 0 &&
                                lazyDetailListState.firstVisibleItemScrollOffset == 0
                        } else {
                            scrollState.value == 0
                        }
                        && source == NestedScrollSource.UserInput
                    ) {
                        phase = PHASE_HALF
                        val delta = -available.y / screenHeightPx
                        rawFraction = (FULL + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (phase != PHASE_FULL && available.y != 0f) {
                        val velSign = if (available.y < 0f) 1f else -1f
                        animateTo(snapTarget(rawFraction, velSign))
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Dialog(
            onDismissRequest = { dismiss() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !handleBackInternally,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false
            )
        ) {
            DialogWindowEdgeToEdge()
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindowRef.value = dialogWindow }

            Box(modifier = Modifier.fillMaxSize()) {
                // Transparent click-catcher — dim is handled by native Window.dimAmount.
                // Uses pointerInput to avoid reading visualFraction in composition.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (visualFraction.value > 0.02f) dismiss()
                                }
                            )
                        }
                )

                // Sheet height via Modifier.layout (layout phase) to avoid
                // recomposition on every spring animation frame.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val h = (screenHeightPx * visualFraction.value).roundToInt().coerceAtLeast(0)
                            val placeable = measurable.measure(
                                constraints.copy(minHeight = h, maxHeight = h)
                            )
                            layout(placeable.width, h) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // 顶部圆角 24dp，更柔和的视觉过渡
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    shadowElevation = 0.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Draggable header: drag handle + title + divider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    var velEma = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            velEma = 0f
                                            grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dismissing) return@detectVerticalDragGestures
                                            change.consume()
                                            velEma = velEma * 0.5f + (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            rawFraction = (rawFraction - dragAmount / screenHeightPx)
                                                .coerceIn(0f, FULL)
                                            coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                                            if (rawFraction >= FULL && dragAmount < 0f) phase = PHASE_FULL
                                        },
                                        onDragEnd = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            animateTo(snapTarget(rawFraction, velEma))
                                        }
                                    )
                                }
                        ) {
                            // 拖拽手柄：居中小条，32x4dp，outline 色，圆角 2dp
                            Box(
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(32.dp).height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.outline)
                                )
                            }

                            // 固定标题：titleSmall + Medium，更精致的视觉层级
                            Text(
                                text = titleOverride ?: if (selectedSegs.size > 1) compactSegmentTitle(selectedSegs, message, useLiveStatus = false)
                                    else if (seg.type == "tool") toolDisplayName(seg)
                                    else if (seg.type == "transcription") transcriptionLabel(liveSegs, selectedSegmentIndex)
                                    else stringResource(R.string.thinking_label_done),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                // 用 outlineVariant 色更柔和
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }

                        if (usesVirtualizedSingleMarkdown) {
                            DetailContentReveal(
                                revealKey = "${message.id}:$selectedSegmentIndex",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(sheetScrollConnection)
                                    .noOpBringIntoView()
                                    .navigationBarsPadding(),
                            ) { revealModifier, onReady ->
                                LazyMarkdownTextContent(
                                    text = seg.content,
                                    renderContext = markdownRenderContext,
                                    listState = lazyDetailListState,
                                    modifier = revealModifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        start = 24.dp,
                                        top = 4.dp,
                                        end = 24.dp,
                                        bottom = 32.dp,
                                    ),
                                    onReady = onReady,
                                )
                            }
                        } else {
                            // Tool and grouped details use one conventional scroll owner. An
                            // actively streaming Markdown document must retain its incremental
                            // renderer when it becomes terminal, so it remains in this branch.
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(sheetScrollConnection)
                                    .verticalScroll(scrollState)
                                    .noOpBringIntoView()
                                    .padding(horizontal = 24.dp)
                                    .padding(top = if (seg.type == "tool") 6.dp else 4.dp)
                                    .navigationBarsPadding()
                                    .padding(bottom = 32.dp)
                            ) {
                                if (selectedSegs.size > 1) {
                                    selectedSegs.forEachIndexed { index, detailSeg ->
                                        val detailIndex = selectedSegmentIndices.getOrNull(index)
                                            ?: liveSegs.indexOf(detailSeg).coerceAtLeast(0)
                                        // 分段卡片：Surface 圆角 16dp，内边距 16dp
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    top = if (index == 0) 0.dp else 10.dp,
                                                ),
                                            shape = RoundedCornerShape(LxDesign.cornerM),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            tonalElevation = 0.dp,
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp)
                                            ) {
                                                // 标题：titleSmall + Medium
                                                Text(
                                                    segmentDetailTitle(detailSeg, liveSegs, detailIndex),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(bottom = 8.dp),
                                                )
                                                if (detailSeg.type == "tool") {
                                                    ToolDetailContent(
                                                        segment = detailSeg,
                                                        onMediaClick = onMediaClick,
                                                    )
                                                } else if (
                                                    detailSeg.type == "transcription" &&
                                                    detailSeg.content.isBlank()
                                                ) {
                                                    // 内容：bodyMedium + onSurfaceVariant
                                                    Text(
                                                        text = "Image transcription is empty.",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            .copy(alpha = 0.4f),
                                                    )
                                                } else {
                                                    val detailIsStreaming =
                                                        isStreaming && index == selectedSegs.lastIndex
                                                    StreamingDetailMarkdownReveal(
                                                        revealKey = "${message.id}:$detailIndex",
                                                        content = detailSeg.content,
                                                        isStreaming = detailIsStreaming,
                                                        renderContext = markdownRenderContext,
                                                    )
                                                }
                                            }
                                        }
                                        if (index < selectedSegs.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(top = 10.dp),
                                                // 用 outlineVariant 色更柔和
                                                color = MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.4f),
                                            )
                                        }
                                    }
                                } else if (seg.type == "tool") {
                                    ToolDetailContent(
                                        segment = seg,
                                        onMediaClick = onMediaClick,
                                    )
                                } else if (
                                    seg.type == "transcription" &&
                                    seg.content.isBlank()
                                ) {
                                    // 内容：bodyMedium + onSurfaceVariant
                                    Text(
                                        text = "Image transcription is empty.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.4f),
                                    )
                                } else {
                                    StreamingDetailMarkdownReveal(
                                        revealKey = "${message.id}:$selectedSegmentIndex",
                                        content = seg.content,
                                        isStreaming = isStreaming,
                                        renderContext = markdownRenderContext,
                                    )
                                }
                                detailFooter?.invoke()
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun StreamingDetailMarkdownReveal(
    revealKey: String,
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
) {
    DetailContentReveal(
        revealKey = revealKey,
        modifier = Modifier.fillMaxWidth(),
    ) { revealModifier, onReady ->
        StreamingMarkdownDocument(
            content = content,
            isStreaming = isStreaming,
            renderContext = renderContext,
            modifier = revealModifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0 || content.isEmpty()) onReady()
                },
            selectionEnabled = !isStreaming,
        )
    }
}

@Composable
private fun DetailContentReveal(
    revealKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (revealModifier: Modifier, onReady: () -> Unit) -> Unit,
) {
    var ready by remember(revealKey) { mutableStateOf(false) }
    var showLoading by remember(revealKey) { mutableStateOf(false) }
    val revealAlpha by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "detailContentReveal:$revealKey",
    )

    LaunchedEffect(revealKey) {
        delay(120)
        if (!ready) showLoading = true
    }
    LaunchedEffect(ready) {
        if (ready) showLoading = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        content(
            Modifier.graphicsLayer { alpha = revealAlpha },
        ) {
            if (!ready) ready = true
        }
        AnimatedVisibility(
            visible = showLoading && !ready,
            enter = fadeIn(tween(160, easing = LinearEasing)),
            exit = fadeOut(tween(140, easing = LinearEasing)),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
