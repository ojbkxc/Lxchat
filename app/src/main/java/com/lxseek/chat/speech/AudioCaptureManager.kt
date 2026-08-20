package com.lxseek.chat.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lxseek.chat.util.AppLog as Log
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        private const val AMPLITUDE_EMA_ALPHA = 0.3f
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var outputFile: File? = null
    private var pcmOutputStream: FileOutputStream? = null

    /** Normalised mic amplitude (0.0–1.0) driven by RMS of each PCM chunk with EMA smoothing. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()
    private var smoothedAmplitude = 0f

    private val bufferSize: Int by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        minBufferSize * BUFFER_SIZE_FACTOR
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<ByteArray> = callbackFlow {
        if (!hasRecordPermission()) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        if (isRecording) {
            Log.w(TAG, "Stale recording detected, forcing cleanup before restart")
            stopRecordingInternal()
        }

        try {
            outputFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.pcm")
            pcmOutputStream = FileOutputStream(outputFile)
            Log.i(TAG, "Starting AudioRecord: rate=$SAMPLE_RATE, mono, PCM16, bufferSize=$bufferSize, perm=${hasRecordPermission()}")

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            val audioState = audioRecord?.state
            if (audioState != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize (state=$audioState); mic may be in use or permission denied")
                close(IllegalStateException("AudioRecord failed to initialize (state=$audioState)"))
                return@callbackFlow
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Audio capture started (pcm=${outputFile?.absolutePath})")

            val buffer = ByteArray(bufferSize)

            while (isActive && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    pcmOutputStream?.write(chunk)
                    trySend(chunk)
                    _amplitude.value = computeRmsAmplitude(chunk)
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "Invalid operation during audio read")
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Bad value during audio read")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio capture", e)
            close(e)
        }

        awaitClose {
            stopRecordingInternal()
        }
    }.flowOn(Dispatchers.IO)

    fun stopCapture(): File {
        stopRecordingInternal()

        val pcmFile = outputFile ?: throw IllegalStateException("No recording in progress")
        val wavFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
        Log.i(TAG, "stopCapture: pcm=${pcmFile.length()} bytes -> ${wavFile.name}")

        try {
            convertPcmToWav(pcmFile, wavFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert PCM to WAV", e)
            return wavFile
        }

        pcmFile.delete()
        Log.i(TAG, "WAV written: ${wavFile.absolutePath} (${wavFile.length()} bytes)")
        return wavFile
    }

    fun cancelCapture() {
        stopRecordingInternal()
        outputFile?.delete()
        outputFile = null
    }

    fun release() {
        stopRecordingInternal()
        audioRecord?.release()
        audioRecord = null
    }

    fun isCapturing(): Boolean = isRecording

    private fun stopRecordingInternal() {
        isRecording = false
        smoothedAmplitude = 0f
        _amplitude.value = 0f

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped by a concurrent path (captureJob.cancel -> awaitClose -> here,
            // racing stopCapture() -> here). Harmless; the recording is already finalized.
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        try {
            pcmOutputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PCM stream", e)
        }

        pcmOutputStream = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        Log.i(TAG, "Audio capture stopped")
    }

    /**
     * Compute normalised RMS amplitude from a PCM 16-bit little-endian mono chunk.
     * RMS = sqrt(sum(sample^2)/n) / Short.MAX_VALUE, then EMA-smoothed to avoid jitter.
     */
    private fun computeRmsAmplitude(chunk: ByteArray): Float {
        if (chunk.size < 2) return smoothedAmplitude
        var sumSq = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val low = chunk[i].toInt() and 0xFF
            val high = chunk[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSq += sample.toDouble() * sample.toDouble()
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return smoothedAmplitude
        val rms = sqrt(sumSq / sampleCount) / Short.MAX_VALUE
        smoothedAmplitude = AMPLITUDE_EMA_ALPHA * rms.toFloat() +
            (1f - AMPLITUDE_EMA_ALPHA) * smoothedAmplitude
        return smoothedAmplitude.coerceIn(0f, 1f)
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        if (pcmData.isEmpty()) {
            Log.w(TAG, "convertPcmToWav: PCM is EMPTY (0 bytes) — nothing was captured; mic may be silent/blocked")
        }
        Log.i(TAG, "convertPcmToWav: ${pcmData.size} bytes PCM (${pcmData.size / (SAMPLE_RATE * 2)}s of 16kHz mono audio)")
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2L

        FileOutputStream(wavFile).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalDataLen.toInt()))
            out.write("WAVE".toByteArray())

            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))
            out.write(shortToByteArray(channels.toShort()))
            out.write(intToByteArray(SAMPLE_RATE))
            out.write(intToByteArray(byteRate.toInt()))
            out.write(shortToByteArray((channels * 2).toShort()))
            out.write(shortToByteArray(16))

            out.write("data".toByteArray())
            out.write(intToByteArray(totalAudioLen.toInt()))
            out.write(pcmData)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
}
