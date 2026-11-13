package com.example.services

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.example.data.model.AbilityConfig
import com.example.data.model.GeoMath
import com.example.data.model.LobbyPlayer
import com.example.data.model.VoicePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real proximity-based voice chat engine using Android AudioRecord + AudioTrack.
 * - Captures 8kHz mono PCM, compresses to 8-bit mu-law Base64 frames when speaking.
 * - Strictly locked down when [isSpectatorLocked] is true so spectators cannot transmit.
 * - Plays incoming frames from active non-spectator players scaled by Haversine distance.
 */
class ProximityVoiceEngine(
    private val scope: CoroutineScope,
    private val onVoiceFrameReady: (audioBase64: String, isSpeaking: Boolean) -> Unit,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) {
    private var captureJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private val playedPacketIds = HashSet<String>()

    @Volatile
    private var isSpectatorLocked = false

    @Volatile
    private var isMicEnabled = false

    fun setSpectatorLocked(locked: Boolean) {
        isSpectatorLocked = locked
        if (locked) {
            isMicEnabled = false
            stopCapture()
            onSpeakingStateChanged(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun setMicActive(enabled: Boolean, hasPermission: Boolean) {
        if (isSpectatorLocked || !hasPermission) {
            isMicEnabled = false
            stopCapture()
            onSpeakingStateChanged(false)
            return
        }
        isMicEnabled = enabled
        if (enabled) {
            startCapture()
        } else {
            stopCapture()
            onSpeakingStateChanged(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 2)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed: ${e.message}")
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@launch
            }

            val pcmBuffer = ShortArray(FRAME_SAMPLES)
            var wasSpeaking = false
            var lastSentAt = 0L

            try {
                recorder.startRecording()
                while (isActive && isMicEnabled && !isSpectatorLocked) {
                    val read = recorder.read(pcmBuffer, 0, FRAME_SAMPLES)
                    if (read <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = pcmBuffer[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read)
                    val speakingNow = rms > VOICE_RMS_THRESHOLD

                    if (speakingNow != wasSpeaking) {
                        wasSpeaking = speakingNow
                        onSpeakingStateChanged(speakingNow)
                    }

                    val now = System.currentTimeMillis()
                    if (speakingNow && now - lastSentAt >= PACKET_INTERVAL_MS) {
                        lastSentAt = now
                        val encoded = encodeMuLaw(pcmBuffer, read)
                        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
                        onVoiceFrameReady(b64, true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Voice capture stopped: ${e.message}")
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
                if (wasSpeaking) {
                    onSpeakingStateChanged(false)
                }
            }
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
    }

    fun processIncomingPackets(
        packets: List<VoicePacket>,
        playersByUid: Map<String, LobbyPlayer>,
        myUid: String,
        listenerLat: Double,
        listenerLng: Double
    ) {
        val now = System.currentTimeMillis()
        for (packet in packets) {
            if (packet.id in playedPacketIds) continue
            if (packet.senderUid == myUid) continue
            // Ignore stale packets older than 4 seconds
            if (now - packet.sentAtMs > 4000L) {
                playedPacketIds.add(packet.id)
                continue
            }
            // Enforce spectator non-interference: never play audio from a spectator
            val sender = playersByUid[packet.senderUid]
            if (sender?.isSpectator == true) {
                playedPacketIds.add(packet.id)
                continue
            }

            playedPacketIds.add(packet.id)
            if (playedPacketIds.size > 400) {
                playedPacketIds.clear()
            }

            val distance = GeoMath.distanceMeters(listenerLat, listenerLng, packet.lat, packet.lng)
            if (distance > AbilityConfig.PROXIMITY_VOICE_RADIUS_METERS) continue

            val gain = (1.0f - (distance / AbilityConfig.PROXIMITY_VOICE_RADIUS_METERS).toFloat())
                .coerceIn(0.05f, 1.0f)

            scope.launch(Dispatchers.IO) {
                playPacket(packet.audioBase64, gain)
            }
        }
    }

    private fun playPacket(audioBase64: String, gain: Float) {
        try {
            val muLawBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
            val pcm = decodeMuLaw(muLawBytes, gain)
            val track = ensureAudioTrack() ?: return
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w(TAG, "Audio playback failed: ${e.message}")
        }
    }

    @Synchronized
    private fun ensureAudioTrack(): AudioTrack? {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2)

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    it.play()
                    audioTrack = it
                }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack init failed: ${e.message}")
            null
        }
    }

    fun release() {
        stopCapture()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    private fun encodeMuLaw(pcm: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count)
        for (i in 0 until count) {
            out[i] = linearToMuLaw(pcm[i].toInt())
        }
        return out
    }

    private fun decodeMuLaw(data: ByteArray, gain: Float): ShortArray {
        val out = ShortArray(data.size)
        for (i in data.indices) {
            val sample = muLawToLinear(data[i])
            out[i] = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun linearToMuLaw(sampleInput: Int): Byte {
        var sample = sampleInput
        val sign = if (sample < 0) 0x80 else 0x00
        if (sample < 0) sample = -sample
        if (sample > 32635) sample = 32635
        sample += 132
        var exponent = 7
        var expMask = 0x4000
        while (exponent > 0 && (sample and expMask) == 0) {
            exponent--
            expMask = expMask shr 1
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        return ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
    }

    private fun muLawToLinear(muByte: Byte): Int {
        val mu = muByte.toInt().inv() and 0xFF
        val sign = mu and 0x80
        val exponent = (mu shr 4) and 0x07
        val mantissa = mu and 0x0F
        var sample = ((mantissa shl 3) + 132) shl exponent
        sample -= 132
        return if (sign != 0) -sample else sample
    }

    private companion object {
        const val TAG = "ProximityVoice"
        const val SAMPLE_RATE = 8000
        const val FRAME_SAMPLES = 3200 // 400ms frame
        const val PACKET_INTERVAL_MS = 450L
        const val VOICE_RMS_THRESHOLD = 450.0
    }
}
