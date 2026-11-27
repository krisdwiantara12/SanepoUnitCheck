package com.sanepo.unitcheck.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.databinding.ActivityAudioTestBinding
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTestActivity : AppCompatActivity() {

    private enum class OutputChannel {
        EARPIECE, LOUDSPEAKER_BOTH, STEREO_LEFT, STEREO_RIGHT
    }

    private lateinit var binding: ActivityAudioTestBinding
    private lateinit var audioManager: AudioManager
    private var workerThread: Thread? = null
    private val workerGenerationId = AtomicLong(0L)
    @Volatile
    private var isWorking = false
    @Volatile
    private var recordedPcm: ShortArray? = null
    private var pendingMicSource: Int? = null
    private var lastActionLabel = "Ready"

    private var speakerTested = false
    private var micTested = false
    private var hapticTested = false

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val src = pendingMicSource
        pendingMicSource = null
        if (granted && src != null) {
            startMicRecording(src)
        } else {
            binding.tvAudioStatus.text = Lang.t(
                this,
                "ERROR: Izin Mikrofon ditolak. Aktifkan izin Mikrofon di Pengaturan untuk tes rekam suara.",
                "ERROR: Microphone permission denied. Grant RECORD_AUDIO in Settings to run mic test."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootAudioTest) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        binding.btnBackAudio.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvAudioTitle.text = Lang.t(this, "Audio Stereo, Dual-Mic & Haptics", "Stereo Audio, Dual-Mic & Haptics")
        binding.tvAudioStatus.text = Lang.t(
            this,
            "Siap: uji Earpiece, Speaker Kiri/Kanan, rekam Dual-Mic, dan Getar.",
            "Ready: test Earpiece, Stereo L/R speakers, Dual-Mic loopback & Haptics."
        )
        binding.tvSecSpeakers.text = Lang.t(this, "1. EARPIECE & LOUDSPEAKER STEREO (200Hz -> 1kHz)", "1. EARPIECE & STEREO SPEAKERS (200Hz -> 1kHz)")
        binding.btnEarpieceTone.text = Lang.t(this, "EARPIECE ATAS", "TOP EARPIECE")
        binding.btnSpeakerTone.text = Lang.t(this, "SPEAKER FULL", "FULL SPEAKER")
        binding.btnSpeakerLeft.text = Lang.t(this, "STEREO KIRI (L)", "STEREO LEFT (L)")
        binding.btnSpeakerRight.text = Lang.t(this, "STEREO KANAN (R)", "STEREO RIGHT (R)")

        binding.tvSecMics.text = Lang.t(this, "2. REKAM DUAL-MIC (3 DETIK)", "2. DUAL-MIC LOOPBACK RECORD (3 SEC)")
        binding.btnRecPrimaryMic.text = Lang.t(this, "MIC UTAMA (BAWAH)", "PRIMARY MIC")
        binding.btnRecSecondaryMic.text = Lang.t(this, "MIC SEKUNDER (ATAS)", "SECONDARY MIC")
        binding.btnPlaybackMic.text = Lang.t(this, "PUTAR ULANG HASIL REKAMAN", "PLAYBACK RECORDED AUDIO")

        binding.tvSecHaptics.text = Lang.t(this, "3. MOTOR GETAR / HAPTICS", "3. VIBRATION / HAPTIC MOTOR")
        binding.btnVibeShort.text = Lang.t(this, "GETAR PENDEK", "SHORT PULSE")
        binding.btnVibePattern.text = Lang.t(this, "GETAR POLA KUAT", "WAVEFORM BUZZ")

        binding.btnAudioPass.text = Lang.t(this, "AUDIO & GETAR PASS", "AUDIO & HAPTICS PASS")
        binding.btnAudioFail.text = Lang.t(this, "ADA MINUS / FAIL", "AUDIO / MIC FAIL")

        binding.btnBackAudio.setOnClickListener { finish() }
        binding.btnEarpieceTone.setOnClickListener { playTestSweep(OutputChannel.EARPIECE) }
        binding.btnSpeakerTone.setOnClickListener { playTestSweep(OutputChannel.LOUDSPEAKER_BOTH) }
        binding.btnSpeakerLeft.setOnClickListener { playTestSweep(OutputChannel.STEREO_LEFT) }
        binding.btnSpeakerRight.setOnClickListener { playTestSweep(OutputChannel.STEREO_RIGHT) }
        binding.btnRecPrimaryMic.setOnClickListener { requestMicAndRecord(MediaRecorder.AudioSource.MIC) }
        binding.btnRecSecondaryMic.setOnClickListener { requestMicAndRecord(MediaRecorder.AudioSource.CAMCORDER) }
        binding.btnPlaybackMic.setOnClickListener { playbackRecordedPcm() }
        binding.btnVibeShort.setOnClickListener { triggerVibration(pattern = false) }
        binding.btnVibePattern.setOnClickListener { triggerVibration(pattern = true) }

        binding.btnAudioPass.setOnClickListener {
            val hasVibe = resolveVibrator()?.hasVibrator() == true
            val missing = ArrayList<String>()
            if (!speakerTested) missing.add("Speaker/Earpiece")
            if (!micTested) missing.add("Mic Record")
            if (hasVibe && !hapticTested) missing.add("Haptics")
            if (missing.isNotEmpty()) {
                val msg = Lang.t(
                    this,
                    "Uji dulu sebelum PASS: ${missing.joinToString(", ")}",
                    "Run before PASS: ${missing.joinToString(", ")}"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvAudioStatus.text = msg
                return@setOnClickListener
            }
            DiagnosticStore.updateItem(
                "audio_haptics",
                TestStatus.PASS,
                "Earpiece/Stereo L-R, Dual-Mic & Haptics verified ($lastActionLabel)"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
        binding.btnAudioFail.setOnClickListener {
            DiagnosticStore.updateItem(
                "audio_haptics",
                TestStatus.FAIL,
                "FAIL flagged by technician ($lastActionLabel)"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
    }

    private fun stopCurrentWorker(): Long {
        val nextGen = workerGenerationId.incrementAndGet()
        isWorking = false
        try {
            workerThread?.join(350)
        } catch (_: Throwable) {
        }
        workerThread = null
        if (nextGen == workerGenerationId.get()) {
            restoreAudioRouting()
        }
        return nextGen
    }

    private fun restoreAudioRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) {
        }
    }

    private fun applyEarpieceRouting() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val earpiece = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (earpiece != null) {
                audioManager.setCommunicationDevice(earpiece)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun applySpeakerRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
    }

    private fun playTestSweep(channel: OutputChannel) {
        stopCurrentWorker()
        val myGen = workerGenerationId.incrementAndGet()
        isWorking = true
        speakerTested = true
        val useEarpiece = channel == OutputChannel.EARPIECE
        lastActionLabel = when (channel) {
            OutputChannel.EARPIECE -> "Earpiece Sweep"
            OutputChannel.LOUDSPEAKER_BOTH -> "Loudspeaker Sweep"
            OutputChannel.STEREO_LEFT -> "Stereo Left (L)"
            OutputChannel.STEREO_RIGHT -> "Stereo Right (R)"
        }
        binding.tvAudioStatus.text = when (channel) {
            OutputChannel.EARPIECE -> Lang.t(this, "Memutar nada 200Hz->1kHz di EARPIECE ATAS (tempel ke telinga)...", "Playing phase-smooth sweep via TOP EARPIECE...")
            OutputChannel.LOUDSPEAKER_BOTH -> Lang.t(this, "Memutar nada 200Hz->1kHz di LOUDSPEAKER...", "Playing phase-smooth sweep via LOUDSPEAKER...")
            OutputChannel.STEREO_LEFT -> Lang.t(this, "Memutar di SPEAKER KIRI / ATAS (Channel L)...", "Playing ONLY Left stereo channel (L)...")
            OutputChannel.STEREO_RIGHT -> Lang.t(this, "Memutar di SPEAKER KANAN / BAWAH (Channel R)...", "Playing ONLY Right stereo channel (R)...")
        }

        workerThread = Thread {
            var track: AudioTrack? = null
            try {
                if (useEarpiece) applyEarpieceRouting() else applySpeakerRouting()

                val sampleRate = 44100
                val frames = sampleRate * 2
                val fadeSamples = (sampleRate * 0.020).toInt().coerceAtLeast(1)
                val isStereo = channel == OutputChannel.STEREO_LEFT || channel == OutputChannel.STEREO_RIGHT
                val pcm = ShortArray(if (isStereo) frames * 2 else frames)

                var phase = 0.0
                for (i in 0 until frames) {
                    val freq = if (i < sampleRate) 200.0 else 1000.0
                    phase += (2.0 * PI * freq) / sampleRate
                    val env = when {
                        i < fadeSamples -> 0.5 * (1.0 - cos(PI * i / fadeSamples))
                        i >= frames - fadeSamples -> 0.5 * (1.0 - cos(PI * (frames - 1 - i) / fadeSamples))
                        else -> 1.0
                    }
                    val sample = (sin(phase) * env * 25000.0).toInt().toShort()
                    if (!isStereo) {
                        pcm[i] = sample
                    } else if (channel == OutputChannel.STEREO_LEFT) {
                        pcm[i * 2] = sample
                        pcm[i * 2 + 1] = 0
                    } else {
                        pcm[i * 2] = 0
                        pcm[i * 2 + 1] = sample
                    }
                }

                val channelMask = if (isStereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val bufBytes = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val usage = if (useEarpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
                val contentType = if (useEarpiece) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                var offset = 0
                val chunk = 2048
                while (isWorking && myGen == workerGenerationId.get() && offset < pcm.size) {
                    val toWrite = minOf(chunk, pcm.size - offset)
                    track.write(pcm, offset, toWrite)
                    offset += toWrite
                }
                if (myGen == workerGenerationId.get()) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            binding.tvAudioStatus.text = Lang.t(
                                this,
                                "Selesai putar $lastActionLabel. Pastikan jernih tanpa sember.",
                                "Finished $lastActionLabel sweep. Verify clarity and no rattle."
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (myGen == workerGenerationId.get()) {
                    runOnUiThread { binding.tvAudioStatus.text = "AudioTrack error: ${t.message}" }
                }
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Throwable) {
                }
                if (myGen == workerGenerationId.get()) {
                    restoreAudioRouting()
                    isWorking = false
                }
            }
        }.also { it.start() }
    }

    private fun requestMicAndRecord(source: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingMicSource = source
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startMicRecording(source)
    }

    private fun startMicRecording(source: Int) {
        stopCurrentWorker()
        val myGen = workerGenerationId.incrementAndGet()
        isWorking = true
        binding.audioMeterView.resetPeak()
        val label = if (source == MediaRecorder.AudioSource.MIC) "Primary Mic" else "Secondary/Camcorder Mic"
        lastActionLabel = label
        binding.tvAudioStatus.text = Lang.t(
            this,
            "Merekam 3 detik dari $label... Bicara dekat lubang mic.",
            "Recording 3.0s from $label... Speak near the mic hole."
        )

        workerThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    runOnUiThread { binding.tvAudioStatus.text = "ERROR: RECORD_AUDIO permission required." }
                    return@Thread
                }
                recorder = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    runOnUiThread {
                        binding.tvAudioStatus.text = "ERROR: $label AudioRecord failed to initialize on this ROM."
                    }
                    return@Thread
                }

                val maxSamples = sampleRate * 3
                val outBuf = ShortArray(maxSamples)
                val readBuf = ShortArray(1024)
                var totalRead = 0
                var maxPeak = 0

                recorder.startRecording()
                while (isWorking && myGen == workerGenerationId.get() && totalRead < maxSamples) {
                    val n = recorder.read(readBuf, 0, minOf(readBuf.size, maxSamples - totalRead))
                    if (n <= 0) break
                    System.arraycopy(readBuf, 0, outBuf, totalRead, n)
                    totalRead += n

                    var sumSq = 0.0
                    for (i in 0 until n) {
                        val s = abs(readBuf[i].toInt())
                        if (s > maxPeak) maxPeak = s
                        sumSq += readBuf[i].toDouble() * readBuf[i].toDouble()
                    }
                    val rms = sqrt(sumSq / n)
                    val levelFrac = (rms / 12000.0).toFloat().coerceIn(0f, 1f)
                    if (myGen == workerGenerationId.get()) {
                        runOnUiThread {
                            if (!isDestroyed) binding.audioMeterView.setLevel(levelFrac)
                        }
                    }
                }
                if (myGen == workerGenerationId.get()) {
                    recordedPcm = outBuf.copyOf(totalRead)
                    micTested = true
                    runOnUiThread {
                        if (!isDestroyed) {
                            binding.tvAudioStatus.text = Lang.t(
                                this,
                                "Rekaman $label selesai (Peak=$maxPeak). Klik PUTAR ULANG untuk dengarkan.",
                                "$label recorded (Peak=$maxPeak). Tap PLAYBACK to inspect clarity."
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (myGen == workerGenerationId.get()) {
                    runOnUiThread { binding.tvAudioStatus.text = "Mic error: ${t.message}" }
                }
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Throwable) {
                }
                if (myGen == workerGenerationId.get()) {
                    isWorking = false
                }
            }
        }.also { it.start() }
    }

    private fun playbackRecordedPcm() {
        val pcm = recordedPcm
        if (pcm == null || pcm.isEmpty()) {
            binding.tvAudioStatus.text = Lang.t(
                this,
                "Belum ada rekaman. Tekan MIC UTAMA atau MIC SEKUNDER dulu.",
                "No recording yet. Record Primary or Secondary Mic first."
            )
            return
        }
        stopCurrentWorker()
        val myGen = workerGenerationId.incrementAndGet()
        isWorking = true
        binding.tvAudioStatus.text = Lang.t(this, "Memutar ulang rekaman mic via loudspeaker...", "Playing back recorded mic audio...")

        workerThread = Thread {
            var track: AudioTrack? = null
            try {
                applySpeakerRouting()
                val sampleRate = 16000
                val bufBytes = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                var offset = 0
                while (isWorking && myGen == workerGenerationId.get() && offset < pcm.size) {
                    val n = minOf(1024, pcm.size - offset)
                    track.write(pcm, offset, n)
                    offset += n
                }
            } catch (t: Throwable) {
                if (myGen == workerGenerationId.get()) {
                    runOnUiThread { binding.tvAudioStatus.text = "Playback error: ${t.message}" }
                }
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Throwable) {
                }
                if (myGen == workerGenerationId.get()) {
                    restoreAudioRouting()
                    isWorking = false
                }
            }
        }.also { it.start() }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun triggerVibration(pattern: Boolean) {
        val vibrator = resolveVibrator()
        if (vibrator == null || !vibrator.hasVibrator()) {
            binding.tvAudioStatus.text = Lang.t(this, "Tidak ditemukan hardware vibrator.", "No vibrator hardware found on this device.")
            return
        }
        hapticTested = true
        lastActionLabel = "Haptics Verified"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (pattern) {
                VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 280, 90, 420), -1)
            } else {
                VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            if (pattern) {
                vibrator.vibrate(longArrayOf(0, 180, 90, 280, 90, 420), -1)
            } else {
                vibrator.vibrate(180L)
            }
        }
        binding.tvAudioStatus.text = Lang.t(this, "Getaran dikirim ke motor haptic.", "Vibration pulse dispatched to haptic motor.")
    }

    override fun onPause() {
        stopCurrentWorker()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
