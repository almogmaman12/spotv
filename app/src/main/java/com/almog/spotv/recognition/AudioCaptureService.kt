package com.almog.spotv.recognition

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.almog.spotv.BuildConfig
import com.almog.spotv.MainActivity
import com.almog.spotv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * User-started, short-sample capture. Internal playback is attempted first; silent/blocked capture
 * falls back to the microphone. Audio is sampled in 10-second windows, never continuously uploaded.
 */
class AudioCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var usingInternalAudio = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (captureJob?.isActive == true) return START_NOT_STICKY
        if (BuildConfig.MUSIC_RECOGNITION_URL.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val hasProjection = resultCode == Activity.RESULT_OK && resultData != null

        try {
            val foregroundTypes = when {
                Build.VERSION.SDK_INT < 30 -> if (hasProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
                hasProjection -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, buildNotification("Starting music recognition"), foregroundTypes)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (hasProjection) {
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(resultCode, resultData!!)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        scope.launch { switchToMicrophone() }
                    }
                }, null)
                recorder = createInternalRecorder(projection!!)
                usingInternalAudio = recorder != null
            } catch (_: Exception) {
                projection = null
            }
        }
        if (recorder == null) {
            recorder = createMicrophoneRecorder()
            usingInternalAudio = false
        }
        if (recorder == null) {
            stopCapture()
            return START_NOT_STICKY
        }

        captureJob = scope.launch {
            recognitionLoop()
        }
        return START_NOT_STICKY
    }

    private suspend fun recognitionLoop() {
        val recognizer = ShazamIoClient()
        var lastRecognizedKey = ""
        var lastRecognizedAt = 0L
        try {
            while (true) {
                val activeRecorder = recorder ?: break
                val pcm = readSample(activeRecorder)
                if (pcm == null) {
                    if (usingInternalAudio) {
                        switchToMicrophone()
                        continue
                    }
                    delay(2_000L)
                    continue
                }

                if (usingInternalAudio && isSilent(pcm)) {
                    switchToMicrophone()
                    continue
                }

                val match = try {
                    recognizer.recognize(toWav(pcm))
                } catch (_: Exception) {
                    null
                }
                if (match != null) {
                    val key = "${match.artist.lowercase()}:${match.title.lowercase()}"
                    val now = System.currentTimeMillis()
                    if (key != lastRecognizedKey || now - lastRecognizedAt > SAME_TRACK_REFRESH_MS) {
                        RecognizedTrackSource.publish(match)
                        lastRecognizedKey = key
                        lastRecognizedAt = now
                    }
                    updateNotification("Recognized: ${match.artist} - ${match.title}")
                    delay(CHANGE_CHECK_INTERVAL_MS)
                } else {
                    updateNotification(if (usingInternalAudio) "Listening to TV audio" else "Listening through microphone")
                    delay(NO_MATCH_INTERVAL_MS)
                }
            }
        } finally {
            stopCapture()
        }
    }

    private fun readSample(audioRecord: AudioRecord): ByteArray? {
        val sample = ByteArray(SAMPLE_BYTES)
        var offset = 0
        while (offset < sample.size) {
            val count = try {
                audioRecord.read(sample, offset, sample.size - offset, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                return null
            }
            if (count <= 0) return null
            offset += count
        }
        return sample
    }

    private suspend fun switchToMicrophone() {
        if (!usingInternalAudio) return
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release()
        recorder = createMicrophoneRecorder()
        usingInternalAudio = false
        if (recorder == null) stopCapture()
    }

    private fun createInternalRecorder(mediaProjection: MediaProjection): AudioRecord? {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val format = audioFormat()
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(SAMPLE_RATE)
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
                .also { if (it.state == AudioRecord.STATE_INITIALIZED) it.startRecording() else it.release() }
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING }
        } catch (_: Exception) {
            null
        }
    }

    private fun createMicrophoneRecorder(): AudioRecord? {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(SAMPLE_RATE)
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat())
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
                .also { if (it.state == AudioRecord.STATE_INITIALIZED) it.startRecording() else it.release() }
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING }
        } catch (_: Exception) {
            null
        }
    }

    private fun audioFormat() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun isSilent(pcm: ByteArray): Boolean {
        var sum = 0.0
        var count = 0
        for (i in pcm.indices step 2) {
            val value = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += value.toDouble() * value
            count++
        }
        val rms = if (count == 0) 0.0 else sqrt(sum / count)
        return rms < SILENCE_RMS_THRESHOLD
    }

    private fun toWav(pcm: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(pcm.size + 44)
        val data = DataOutputStream(output)
        fun writeIntLE(value: Int) {
            data.writeByte(value and 0xff)
            data.writeByte((value shr 8) and 0xff)
            data.writeByte((value shr 16) and 0xff)
            data.writeByte((value shr 24) and 0xff)
        }
        fun writeShortLE(value: Int) {
            data.writeByte(value and 0xff)
            data.writeByte((value shr 8) and 0xff)
        }
        data.writeBytes("RIFF")
        writeIntLE(36 + pcm.size)
        data.writeBytes("WAVE")
        data.writeBytes("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(SAMPLE_RATE)
        writeIntLE(SAMPLE_RATE * 2)
        writeShortLE(2)
        writeShortLE(16)
        data.writeBytes("data")
        writeIntLE(pcm.size)
        data.write(pcm)
        data.flush()
        return output.toByteArray()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Music recognition", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("SpotTV music recognition")
                .setContentText(text)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("SpotTV music recognition")
                .setContentText(text)
                .setOngoing(true)
                .addAction(0, "Stop", stopPending)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        projection?.stop()
        projection = null
        RecognizedTrackSource.publish(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.almog.spotv.recognition.START"
        const val ACTION_STOP = "com.almog.spotv.recognition.STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "spottv_music_recognition"
        private const val NOTIFICATION_ID = 4210
        private const val SAMPLE_RATE = 44_100
        private const val SAMPLE_SECONDS = 12
        private const val SAMPLE_BYTES = SAMPLE_RATE * SAMPLE_SECONDS * 2
        private const val SILENCE_RMS_THRESHOLD = 110.0
        private const val NO_MATCH_INTERVAL_MS = 5_000L
        private const val CHANGE_CHECK_INTERVAL_MS = 12_000L
        private const val SAME_TRACK_REFRESH_MS = 180_000L
    }
}

object RecognizedTrackSource {
    private val _track = kotlinx.coroutines.flow.MutableStateFlow<RecognizedTrack?>(null)
    val track: kotlinx.coroutines.flow.StateFlow<RecognizedTrack?> = _track
    internal fun publish(track: RecognizedTrack?) { _track.value = track }
}
