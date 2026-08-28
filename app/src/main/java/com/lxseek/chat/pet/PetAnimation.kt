package com.lxseek.chat.pet

/**
 * Spritesheet frame animation ported from cc-haha's `petAnimation.ts`.
 *
 * The atlas is a single WebP image laid out as a fixed-size grid. Each animation
 * state occupies one row; frames advance left-to-right. The playback scheduler
 * builds a flat frame list (with per-frame durations) for each state and resolves
 * the visible frame from elapsed wall-clock time.
 *
 * Atlas layout (v2): 1536x2288, 8 columns x 11 rows, each cell 192x208.
 * Rows 0-8 are motion states; rows 9-10 hold 16-direction look frames.
 */
object PetAnimation {

    /** Atlas geometry constants (PET_ATLAS_V2 in cc-haha). */
    const val SPRITE_VERSION_NUMBER = 2
    const val COLUMNS = 8
    const val ROWS = 11
    const val CELL_WIDTH = 192
    const val CELL_HEIGHT = 208
    const val ATLAS_WIDTH = 1536
    const val ATLAS_HEIGHT = 2288

    /** Loop / timing multipliers controlling the ambient playback sequence. */
    const val ACTIVE_BURST_LOOPS = 3
    const val IDLE_DURATION_MULTIPLIER = 6
    const val AMBIENT_IDLE_LOOPS = 2
    const val AMBIENT_GESTURE_LOOPS = 2

    /** The nine motion states, each mapped to a spritesheet row. */
    enum class State(val rowIndex: Int, val frameDurationsMs: IntArray) {
        IDLE(0, intArrayOf(280, 110, 110, 140, 140, 320)),
        RUNNING_RIGHT(1, intArrayOf(120, 120, 120, 120, 120, 120, 120, 220)),
        RUNNING_LEFT(2, intArrayOf(120, 120, 120, 120, 120, 120, 120, 220)),
        WAVING(3, intArrayOf(140, 140, 140, 280)),
        JUMPING(4, intArrayOf(140, 140, 140, 140, 280)),
        FAILED(5, intArrayOf(140, 140, 140, 140, 140, 140, 140, 240)),
        WAITING(6, intArrayOf(150, 150, 150, 150, 150, 260)),
        RUNNING(7, intArrayOf(120, 120, 120, 120, 120, 220)),
        REVIEW(8, intArrayOf(150, 150, 150, 150, 150, 280));

        /** Total duration of one full loop of this state, in ms. */
        val loopDurationMs: Int = frameDurationsMs.sum()
    }

    /** A single cell rect inside the atlas. */
    data class AtlasFrame(
        val rowIndex: Int,
        val columnIndex: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /** One animation frame: atlas rect plus its display duration. */
    data class AnimationFrame(
        val frameIndex: Int,
        val rowIndex: Int,
        val columnIndex: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
    )

    /** Whether a playback frame belongs to the ambient idle phase or an action burst. */
    enum class PlaybackPhase { ACTION, IDLE }

    /**
     * A single frame inside the resolved playback sequence, annotated with phase,
     * originating motion state, and whether it is the last frame of its loop.
     */
    data class PlaybackFrame(
        val frameIndex: Int,
        val rowIndex: Int,
        val columnIndex: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        val phase: PlaybackPhase,
        val motionState: State,
        val cycleBoundaryAfter: Boolean,
    )

    /** Result of resolving a playback frame at a given elapsed time. */
    data class PlaybackTick(
        val frame: PlaybackFrame,
        val playbackIndex: Int,
        val remainingDurationMs: Int,
    )

    // ---- atlas helpers ----

    /** Returns the atlas rect for the given row/column. */
    fun atlasFrame(rowIndex: Int, columnIndex: Int): AtlasFrame = AtlasFrame(
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        x = columnIndex * CELL_WIDTH,
        y = rowIndex * CELL_HEIGHT,
        width = CELL_WIDTH,
        height = CELL_HEIGHT,
    )

    /** Returns the ordered frames for one loop of [state]. */
    fun animationFrames(state: State): List<AnimationFrame> =
        state.frameDurationsMs.mapIndexed { index, duration ->
            val cell = atlasFrame(state.rowIndex, index)
            AnimationFrame(
                frameIndex = index,
                rowIndex = cell.rowIndex,
                columnIndex = cell.columnIndex,
                x = cell.x,
                y = cell.y,
                width = cell.width,
                height = cell.height,
                durationMs = duration,
            )
        }

    // ---- playback sequence ----

    /**
     * Builds the flat, repeating playback sequence for [state].
     *
     * - `IDLE`: 2 slow-idle loops, 2 waving loops, 2 slow-idle loops, 2 jumping loops.
     * - non-IDLE: 3 action loops of [state] followed by 1 slow-idle loop.
     *
     * "Slow idle" multiplies every idle frame duration by [IDLE_DURATION_MULTIPLIER].
     * The result is cached per state.
     */
    fun playbackFrames(state: State): List<PlaybackFrame> =
        playbackCache.getOrPut(state) { buildPlaybackFrames(state) }

    private val playbackCache = HashMap<State, List<PlaybackFrame>>()

    private fun buildPlaybackFrames(state: State): List<PlaybackFrame> {
        if (state == State.IDLE) {
            val result = ArrayList<PlaybackFrame>()
            result += repeatedFrames(State.IDLE, AMBIENT_IDLE_LOOPS, PlaybackPhase.IDLE, IDLE_DURATION_MULTIPLIER)
            result += repeatedFrames(State.WAVING, AMBIENT_GESTURE_LOOPS, PlaybackPhase.ACTION, 1)
            result += repeatedFrames(State.IDLE, AMBIENT_IDLE_LOOPS, PlaybackPhase.IDLE, IDLE_DURATION_MULTIPLIER)
            result += repeatedFrames(State.JUMPING, AMBIENT_GESTURE_LOOPS, PlaybackPhase.ACTION, 1)
            return result
        }
        val result = ArrayList<PlaybackFrame>()
        result += repeatedFrames(state, ACTIVE_BURST_LOOPS, PlaybackPhase.ACTION, 1)
        result += repeatedFrames(State.IDLE, 1, PlaybackPhase.IDLE, IDLE_DURATION_MULTIPLIER)
        return result
    }

    private fun repeatedFrames(
        state: State,
        loops: Int,
        phase: PlaybackPhase,
        durationMultiplier: Int,
    ): List<PlaybackFrame> {
        val frames = animationFrames(state)
        val last = frames.lastIndex
        val result = ArrayList<PlaybackFrame>(loops * frames.size)
        repeat(loops) {
            frames.forEach { frame ->
                result += PlaybackFrame(
                    frameIndex = frame.frameIndex,
                    rowIndex = frame.rowIndex,
                    columnIndex = frame.columnIndex,
                    x = frame.x,
                    y = frame.y,
                    width = frame.width,
                    height = frame.height,
                    durationMs = frame.durationMs * durationMultiplier,
                    phase = phase,
                    motionState = state,
                    cycleBoundaryAfter = frame.frameIndex == last,
                )
            }
        }
        return result
    }

    // ---- tick resolution ----

    /**
     * Resolves the visible playback frame after [elapsedMs] of wall-clock time.
     *
     * The sequence loops indefinitely: once [elapsedMs] exceeds the total loop
     * duration the remainder is folded back into the loop window so the animation
     * plays seamlessly forever.
     */
    fun playbackTickAtElapsedMs(state: State, elapsedMs: Long): PlaybackTick {
        if (elapsedMs < 0L) return playbackTickAtIndex(state, 0)

        val playback = playbackFrames(state)
        if (playback.isEmpty()) return playbackTickAtIndex(state, 0)

        val loopDurationMs = playback.sumOf { it.durationMs.toLong() }
        if (loopDurationMs <= 0L) return playbackTickAtIndex(state, 0)

        val effectiveElapsed = elapsedMs % loopDurationMs
        var remaining = effectiveElapsed
        playback.forEachIndexed { index, frame ->
            val dur = frame.durationMs.toLong()
            if (remaining < dur) {
                return PlaybackTick(
                    frame = frame,
                    playbackIndex = index,
                    remainingDurationMs = (dur - remaining).toInt(),
                )
            }
            remaining -= dur
        }
        // Fallback: last frame.
        val lastIndex = playback.lastIndex
        return PlaybackTick(
            frame = playback[lastIndex],
            playbackIndex = lastIndex,
            remainingDurationMs = playback[lastIndex].durationMs,
        )
    }

    private fun playbackTickAtIndex(state: State, index: Int): PlaybackTick {
        val playback = playbackFrames(state)
        if (playback.isEmpty()) {
            // Should never happen, but guard against degenerate state.
            val cell = atlasFrame(0, 0)
            val frame = PlaybackFrame(
                frameIndex = 0,
                rowIndex = 0,
                columnIndex = 0,
                x = cell.x,
                y = cell.y,
                width = cell.width,
                height = cell.height,
                durationMs = 100,
                phase = PlaybackPhase.IDLE,
                motionState = State.IDLE,
                cycleBoundaryAfter = true,
            )
            return PlaybackTick(frame, 0, 100)
        }
        val safeIndex = ((index % playback.size) + playback.size) % playback.size
        val frame = playback[safeIndex]
        return PlaybackTick(frame, safeIndex, frame.durationMs)
    }

    // ---- emotion mapping ----

    /**
     * Maps a [PetEmotion] (LxChat's high-level emotion) to a spritesheet [State].
     *
     * IDLE      -> idle
     * THINKING  -> running
     * HAPPY     -> jumping  (completion burst)
     * SAD       -> failed
     * ERROR     -> failed
     * WAITING   -> waiting  (tool approval / user input)
     */
    fun stateForEmotion(emotion: PetEmotion): State = when (emotion) {
        PetEmotion.IDLE -> State.IDLE
        PetEmotion.THINKING -> State.RUNNING
        PetEmotion.HAPPY -> State.JUMPING
        PetEmotion.SAD -> State.FAILED
        PetEmotion.ERROR -> State.FAILED
        PetEmotion.WAITING -> State.WAITING
    }

    // ---- look direction (rows 9-10) ----

    /** The 16 supported look directions in degrees, 22.5 apart. */
    val LOOK_DIRECTIONS: IntArray = intArrayOf(
        0, 22, 45, 67, 90, 112, 135, 157,
        180, 202, 225, 247, 270, 292, 315, 337,
    )

    /** Neutral look frame (row 0, column 6) used when no look direction is active. */
    val NEUTRAL_LOOK_FRAME: AtlasFrame = atlasFrame(0, 6)

    /**
     * Resolves the look-direction atlas frame for a pointer offset.
     * Returns null (neutral) when the offset is inside the deadzone.
     */
    fun lookFrame(deltaX: Float, deltaY: Float, deadzone: Float = 0f): AtlasFrame {
        val distance = Math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
        if (distance == 0f || distance <= deadzone) return NEUTRAL_LOOK_FRAME

        // Clockwise degrees where 0 is up; quantized to 22.5 steps.
        val radians = Math.atan2(deltaX.toDouble(), (-deltaY).toDouble())
        val clockwiseDegrees = (radians * 180.0 / Math.PI).toFloat()
        val normalizedDegrees = ((clockwiseDegrees + 360f) % 360f)
        val directionIndex = Math.round(normalizedDegrees / 22.5f).toInt() % LOOK_DIRECTIONS.size
        val rowIndex = if (directionIndex < COLUMNS) 9 else 10
        val columnIndex = directionIndex % COLUMNS
        return atlasFrame(rowIndex, columnIndex)
    }
}