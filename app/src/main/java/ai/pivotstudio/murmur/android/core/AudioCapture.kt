package ai.pivotstudio.murmur.android.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures mic audio at 16kHz mono and hands off fixed-size chunks through a
 * single-consumer [Channel].
 *
 * Mirrors the macOS app's explicit-ordering rule: `AVAudioEngine` there
 * recycles its buffer the instant the tap callback returns, so every chunk
 * must be copied into fresh storage before leaving the callback — same
 * discipline here with `ShortArray.copyOf()`. A `Channel` (not a `Task` per
 * buffer) guarantees a single consumer drains chunks in order; spawning a
 * coroutine per buffer would have no ordering guarantee and could corrupt
 * the transcript, exactly as noted in the macOS `AudioCapture` design notes.
 */
class AudioCapture(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: kotlinx.coroutines.Job? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts capture and returns a channel the caller drains until the
     * utterance ends (e.g. button release). Call [stop] to close the mic
     * and the channel.
     */
    @SuppressLint("MissingPermission") // caller must check hasMicPermission() first
    fun start(scope: CoroutineScope): ReceiveChannel<ShortArray> {
        check(hasMicPermission()) { "RECORD_AUDIO permission not granted" }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBufferSize, CHUNK_SAMPLES * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        audioRecord = record

        val channel = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(CHUNK_SAMPLES)
            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // Copy before handing off — AudioRecord.read() reuses `buffer`
                    // on the next call, same "buffers copied, never borrowed" rule
                    // as the macOS AudioCapture.
                    channel.send(buffer.copyOf(read))
                }
            }
            channel.close()
        }

        return channel
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        /** 20ms chunks at 16kHz. */
        const val CHUNK_SAMPLES = 320
    }
}
