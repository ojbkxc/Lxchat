package com.lxseek.chat.speech

import com.lxseek.chat.util.AppLog as Log

/**
 * Adaptive Voice Activity Detector for streaming audio. Extracted from
 * VoiceConversationController.beginStreamingVoskCapture() so the calibration,
 * threshold, and hysteresis logic is reusable and separately testable.
 *
 * Usage: call [feedAmplitude] with each PCM chunk's normalized RMS amplitude.
 * The detector reports speech start / speech end through callbacks.
 *
 * @param onSpeechStart  called once when speech is detected after noise gating
 * @param onSpeechEnd    called once per utterance segment when silence is detected
 */
class StreamingVAD(
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: (segmentMs: Long) -> Unit = {},
) {
    companion object {
        private const val TAG = "StreamingVAD"
        private const val SILENCE_THRESHOLD = 0.05f
        private const val SILENCE_DURATION_MS = 1600L
        private const val MIN_TRIGGER_CHUNKS = 3
        private const val CALIBRATION_MS = 500L
        private const val THRESHOLD_RATIO = 0.3f
        private const val THRESHOLD_BASE_ADD = 0.02f
        private const val NOISE_FLOOR_MULTIPLIER = 3.0f
        private const val ROLLING_WINDOW_SIZE = 30
        private const val HYSTERESIS_RATIO = 0.6f
        private const val MIN_SEGMENT_MS = 150L
    }

    // ── adaptive threshold state ──
    private var dynamicThreshold = SILENCE_THRESHOLD
    private val calibrationAmps = mutableListOf<Float>()
    private val calibrationStartMs: Long = System.currentTimeMillis()
    private var calibrated = false
    private val rollingWindow = ArrayDeque<Float>()
    private var noiseFloor = SILENCE_THRESHOLD

    // ── speech detection state ──
    private var hasSpeech = false
    private var silenceStartMs = 0L
    private var speakStartMs = 0L
    private var triggerCounter = 0

    /** Public for UI; the boosted amplitude (0..1) from the caller. */
    var latestAmplitude: Float = 0f
        private set

    /** Duration of the last detected speech segment, or -1 if none. */
    var lastSegmentMs: Long = -1L
        private set

    /** Reset the detector for a new streaming session. */
    fun reset() {
        hasSpeech = false
        silenceStartMs = 0L
        speakStartMs = 0L
        triggerCounter = 0
        latestAmplitude = 0f
        lastSegmentMs = -1L
        dynamicThreshold = SILENCE_THRESHOLD
        noiseFloor = SILENCE_THRESHOLD
        calibrated = false
        calibrationAmps.clear()
        rollingWindow.clear()
    }

    /**
     * Feed one normalized amplitude sample (0..1) and trigger callbacks when
     * speech boundaries are detected.
     */
    fun feedAmplitude(amp: Float) {
        latestAmplitude = amp

        // ── Calibration phase ──
        if (!calibrated) {
            if (System.currentTimeMillis() - calibrationStartMs < CALIBRATION_MS) {
                calibrationAmps.add(amp)
                return
            }
            if (calibrationAmps.isNotEmpty()) {
                val avgNoise = calibrationAmps.average().toFloat()
                noiseFloor = avgNoise
                dynamicThreshold = (avgNoise * THRESHOLD_RATIO + THRESHOLD_BASE_ADD)
                    .coerceAtLeast(SILENCE_THRESHOLD)
                Log.i(TAG, "VAD calibrated: avgNoise=$avgNoise, dynamicThreshold=$dynamicThreshold")
                calibrationAmps.clear()
            }
            calibrated = true
        }

        // ── Adaptive threshold with sliding window ──
        rollingWindow.addLast(amp)
        if (rollingWindow.size > ROLLING_WINDOW_SIZE) {
            rollingWindow.removeFirst()
        }
        if (rollingWindow.isNotEmpty()) {
            val rollingAvg = rollingWindow.average().toFloat()
            if (rollingAvg < noiseFloor) noiseFloor = rollingAvg
            dynamicThreshold = maxOf(
                rollingAvg * THRESHOLD_RATIO,
                noiseFloor * NOISE_FLOOR_MULTIPLIER,
                SILENCE_THRESHOLD,
            )
        }

        // ── Hysteresis-based speech / silence detection ──
        val silenceFloor = dynamicThreshold * HYSTERESIS_RATIO

        if (amp >= dynamicThreshold) {
            triggerCounter++
        } else {
            triggerCounter = 0
        }
        if (amp >= silenceFloor) {
            silenceStartMs = 0L
        }

        if (triggerCounter >= MIN_TRIGGER_CHUNKS && !hasSpeech) {
            hasSpeech = true
            speakStartMs = System.currentTimeMillis()
            Log.i(TAG, "VAD: speech start (amp=$amp, threshold=$dynamicThreshold)")
            onSpeechStart()
        }

        if (hasSpeech) {
            if (amp < silenceFloor) {
                if (silenceStartMs == 0L) {
                    silenceStartMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - silenceStartMs >= SILENCE_DURATION_MS) {
                    val segmentMs = System.currentTimeMillis() - speakStartMs
                    silenceStartMs = 0L
                    hasSpeech = false
                    triggerCounter = 0
                    if (segmentMs >= MIN_SEGMENT_MS) {
                        lastSegmentMs = segmentMs
                        Log.i(TAG, "VAD: speech end (${segmentMs}ms)")
                        onSpeechEnd(segmentMs)
                    } else {
                        Log.i(TAG, "VAD: discarded short segment (${segmentMs}ms)")
                    }
                }
            }
        }
    }
}