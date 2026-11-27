package com.sanepo.unitcheck.ui

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.SystemClock
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTestActivity : AppCompatActivity() {

    private enum class OutputChannel {
        EARPIECE, LOUDSPEAKER_BOTH, STEREO_LEFT, STEREO_RIGHT
    }

    private enum class MicRole {
        PRIMARY_BOTTOM, SECONDARY_TOP, EXPRESS_AUTO_LOOPBACK
    }

    private lateinit var binding: ActivityAudioTestBinding
    private lateinit var audioManager: AudioManager
    private var workerThread: Thread? = null
    private val workerGenerationId = AtomicLong(0L)
    @Volatile
    private var isWorking = false
    @Volatile
    private var recordedPcm: ShortArray? = null
    @Volatile
    private var recordedSampleRate = 44100
    @Volatile
    private var recordedPeak = 0
    private var pendingMicRole: MicRole? = null
    private var lastActionLabel = "Ready"

    private var speakerTested = false
    private var micTested = false
    private var hapticTested = false
    private var savedMediaVolume: Int? = null

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val role = pendingMicRole
        pendingMicRole = null
        if (granted && role != null) {
            startMicRecording(role)
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

        binding.btnBackAudio.contentDescription = Lang.t(this, "Kembali", "Back")
        binding.tvAudioTitle.text = Lang.t(this, "Audio Stereo, Dual-Mic & Haptics", "Stereo Audio, Dual-Mic & Haptics")
        binding.tvAudioStatus.text = Lang.t(
            this,
            "Siap: klik TES KILAT LOOPBACK (rekam -> langsung bunyi keras + getar) atau uji satu per satu.",
            "Ready: tap QUICK LOOPBACK (record -> auto loud playback + haptic) or test individually."
        )
        binding.btnQuickLoopback.text = Lang.t(
            this,
            "TES KILAT COD: REKAM 2 DETIK -> OTOMATIS BUNYI + GETAR",
            "QUICK COD LOOPBACK: 2s RECORD -> AUTO PLAYBACK + HAPTIC"
        )
        binding.tvSecSpeakers.text = Lang.t(this, "1. EARPIECE & LOUDSPEAKER STEREO (200Hz -> 1kHz)", "1. EARPIECE & STEREO SPEAKERS (200Hz -> 1kHz)")
        binding.btnEarpieceTone.text = Lang.t(this, "EARPIECE ATAS", "TOP EARPIECE")
        binding.btnSpeakerTone.text = Lang.t(this, "SPEAKER FULL", "FULL SPEAKER")
        binding.btnSpeakerLeft.text = Lang.t(this, "STEREO KIRI (L)", "STEREO LEFT (L)")
        binding.btnSpeakerRight.text = Lang.t(this, "STEREO KANAN (R)", "STEREO RIGHT (R)")

        binding.tvSecMics.text = Lang.t(this, "2. REKAM DUAL-MIC (AUTO-GAIN LAPANGAN)", "2. DUAL-MIC RECORDING (AUTO-GAIN BOOST)")
        binding.btnRecPrimaryMic.text = Lang.t(this, "MIC UTAMA (BAWAH 3s)", "PRIMARY MIC (3s)")
        binding.btnRecSecondaryMic.text = Lang.t(this, "MIC SEKUNDER (ATAS 3s)", "SECONDARY MIC (3s)")
        binding.btnPlaybackMic.text = Lang.t(this, "PUTAR ULANG HASIL REKAMAN (LOUD)", "PLAYBACK RECORDED AUDIO (BOOSTED)")

        binding.tvSecHaptics.text = Lang.t(this, "3. MOTOR GETAR / HAPTICS", "3. VIBRATION / HAPTIC MOTOR")
        binding.btnVibeShort.text = Lang.t(this, "GETAR PENDEK", "SHORT PULSE")
        binding.btnVibePattern.text = Lang.t(this, "GETAR POLA KUAT", "WAVEFORM BUZZ")

        binding.btnAudioPass.text = Lang.t(this, "AUDIO & GETAR PASS", "AUDIO & HAPTICS PASS")
        binding.btnAudioFail.text = Lang.t(this, "ADA MINUS / FAIL", "AUDIO / MIC FAIL")

        binding.btnBackAudio.setOnClickListener { finish() }
        binding.btnQuickLoopback.setOnClickListener { requestMicAndRecord(MicRole.EXPRESS_AUTO_LOOPBACK) }
        binding.btnEarpieceTone.setOnClickListener { playTestSweep(OutputChannel.EARPIECE) }
        binding.btnSpeakerTone.setOnClickListener { playTestSweep(OutputChannel.LOUDSPEAKER_BOTH) }
        binding.btnSpeakerLeft.setOnClickListener { playTestSweep(OutputChannel.STEREO_LEFT) }
        binding.btnSpeakerRight.setOnClickListener { playTestSweep(OutputChannel.STEREO_RIGHT) }
        binding.btnRecPrimaryMic.setOnClickListener { requestMicAndRecord(MicRole.PRIMARY_BOTTOM) }
        binding.btnRecSecondaryMic.setOnClickListener { requestMicAndRecord(MicRole.SECONDARY_TOP) }
        binding.btnPlaybackMic.setOnClickListener { playbackRecordedPcm() }
        binding.btnVibeShort.setOnClickListener { triggerVibration(pattern = false) }
        binding.btnVibePattern.setOnClickListener { triggerVibration(pattern = true) }

        binding.btnAudioPass.setOnClickListener {
            val hasVibe = resolveVibrator()?.hasVibrator() == true
            val missing = ArrayList<String>()
            if (!speakerTested) missing.add("Speaker/Earpiece")
            if (!micTested) missing.add("Mic Nyata (Peak>=150)")
            if (hasVibe && !hapticTested) missing.add("Haptics")
            if (missing.isNotEmpty()) {
                val msg = Lang.t(
                    this,
                    "Uji dulu hingga suara/mic terbaca sebelum PASS: ${missing.joinToString(", ")}",
                    "Verify active output before PASS: ${missing.joinToString(", ")}"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvAudioStatus.text = msg
                return@setOnClickListener
            }
            DiagnosticStore.updateItem(
                "audio_haptics",
                TestStatus.PASS,
                "Audio, Dual-Mic (Peak=$recordedPeak) & Haptics verified ($lastActionLabel)"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
        binding.btnAudioFail.setOnClickListener {
            DiagnosticStore.updateItem(
                "audio_haptics",
                TestStatus.FAIL,
                "FAIL: Audio / Mic / Earpiece / Vibrator defect ($lastActionLabel, Peak=$recordedPeak)"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
    }

    private fun stopCurrentWorker(): Long {
        val nextGen = workerGenerationId.incrementAndGet()
        isWorking = false
        try {
            workerThread?.join(450)
        } catch (_: Throwable) {
        }
        workerThread = null
        if (nextGen == workerGenerationId.get()) {
            restoreAudioRouting()
        }
        return nextGen
    }

    private fun ensureAudibleVolume(streamType: Int): String {
        return try {
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(streamType)
            if (streamType == AudioManager.STREAM_MUSIC && savedMediaVolume == null) {
                savedMediaVolume = curVol
            }
            if (curVol < (maxVol * 0.6f).roundToInt()) {
                val target = (maxVol * 0.85f).roundToInt().coerceAtLeast(1)
                audioManager.setStreamVolume(streamType, target, 0)
                val now = audioManager.getStreamVolume(streamType)
                "Vol ${(now * 100) / maxVol}%"
            } else {
                "Vol ${(curVol * 100) / maxVol}%"
            }
        } catch (_: Throwable) {
            "Vol Restricted"
        }
    }

    private fun restoreAudioRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) {
        }
    }

    private fun applyEarpieceRouting(track: AudioTrack? = null) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && track != null) {
                val earpiece = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (earpiece != null) track.preferredDevice = earpiece
            }
        }
    }

    private fun applySpeakerRouting(track: AudioTrack? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && track != null) {
            val builtinSpeaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (builtinSpeaker != null) {
                track.preferredDevice = builtinSpeaker
            }
        }
    }

    private fun playStaticPcmAndDrain(
        track: AudioTrack,
        pcm: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        myGen: Long
    ) {
        val written = track.write(pcm, 0, pcm.size)
        val totalFrames = (if (written > 0) written else pcm.size) / channelCount
        val durationMs = ((totalFrames * 1000L) / sampleRate).coerceAtLeast(300L)
        track.play()

        val startMs = SystemClock.elapsedRealtime()
        val deadlineMs = startMs + durationMs + 500L
        while (isWorking && myGen == workerGenerationId.get()) {
            val head = try {
                track.playbackHeadPosition
            } catch (_: Throwable) {
                totalFrames
            }
            if (head >= totalFrames - 256 || SystemClock.elapsedRealtime() >= deadlineMs) {
                break
            }
            try {
                Thread.sleep(35L)
            } catch (_: InterruptedException) {
                break
            }
        }
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
        val streamType = if (useEarpiece) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val volInfo = ensureAudibleVolume(streamType)
        binding.tvAudioStatus.text = when (channel) {
            OutputChannel.EARPIECE -> Lang.t(this, "Memutar nada 200Hz->1kHz di EARPIECE ATAS ($volInfo)...", "Playing sweep via TOP EARPIECE ($volInfo)...")
            OutputChannel.LOUDSPEAKER_BOTH -> Lang.t(this, "Memutar nada 200Hz->1kHz di LOUDSPEAKER ($volInfo)...", "Playing sweep via LOUDSPEAKER ($volInfo)...")
            OutputChannel.STEREO_LEFT -> Lang.t(this, "Memutar di SPEAKER KIRI / ATAS (L) ($volInfo)...", "Playing ONLY Left channel (L) ($volInfo)...")
            OutputChannel.STEREO_RIGHT -> Lang.t(this, "Memutar di SPEAKER KANAN / BAWAH (R) ($volInfo)...", "Playing ONLY Right channel (R) ($volInfo)...")
        }

        workerThread = Thread {
            var track: AudioTrack? = null
            try {
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
                    val sample = (sin(phase) * env * 27000.0).toInt().toShort()
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
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                if (useEarpiece) applyEarpieceRouting(track) else applySpeakerRouting(track)
                playStaticPcmAndDrain(track, pcm, sampleRate, if (isStereo) 2 else 1, myGen)

                if (myGen == workerGenerationId.get()) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            binding.tvAudioStatus.text = Lang.t(
                                this,
                                "Selesai putar $lastActionLabel ($volInfo). Pastikan jernih tanpa sember.",
                                "Finished $lastActionLabel ($volInfo). Verify clarity and no rattle."
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
                    track?.pause()
                    track?.flush()
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

    private fun requestMicAndRecord(role: MicRole) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingMicRole = role
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startMicRecording(role)
    }

    @SuppressLint("MissingPermission")
    private fun createRecorderForRole(role: MicRole, sampleRate: Int): Pair<AudioRecord?, String> {
        val sources = when (role) {
            MicRole.PRIMARY_BOTTOM, MicRole.EXPRESS_AUTO_LOOPBACK ->
                intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            MicRole.SECONDARY_TOP ->
                intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        }

        var preferredInput: AudioDeviceInfo? = null
        var micNote = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (role == MicRole.SECONDARY_TOP) {
                preferredInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    inputs.firstOrNull { dev ->
                        val addr = dev.address.lowercase()
                        addr.contains("back") || addr.contains("top") || addr.contains("sec") || addr.contains("rear")
                    }
                } else {
                    null
                } ?: if (inputs.size >= 2) inputs[1] else inputs.firstOrNull()
                if (inputs.size <= 1) {
                    micNote = " [1 Built-In Mic Reported by HAL]"
                }
            } else {
                preferredInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    inputs.firstOrNull { dev ->
                        dev.address.lowercase().contains("bottom")
                    }
                } else {
                    null
                } ?: inputs.firstOrNull()
            }
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        for (src in sources) {
            try {
                val rec = AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredInput != null) {
                        rec.preferredDevice = preferredInput
                    }
                    return Pair(rec, micNote)
                }
                rec.release()
            } catch (_: Throwable) {
            }
        }
        return Pair(null, micNote)
    }

    private fun startMicRecording(role: MicRole) {
        stopCurrentWorker()
        val myGen = workerGenerationId.incrementAndGet()
        isWorking = true
        binding.audioMeterView.resetPeak()

        val durationSec = if (role == MicRole.EXPRESS_AUTO_LOOPBACK) 2 else 3
        val label = when (role) {
            MicRole.PRIMARY_BOTTOM -> "Mic Utama (Bawah)"
            MicRole.SECONDARY_TOP -> "Mic Sekunder (Atas)"
            MicRole.EXPRESS_AUTO_LOOPBACK -> "Loopback Kilat COD"
        }
        lastActionLabel = label
        binding.tvAudioStatus.text = Lang.t(
            this,
            "Merekam ${durationSec}s dari $label... Bicara dekat lubang mic sekarang!",
            "Recording ${durationSec}s via $label... Speak clearly into the mic now!"
        )

        workerThread = Thread {
            var recorder: AudioRecord? = null
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    runOnUiThread { binding.tvAudioStatus.text = "ERROR: RECORD_AUDIO permission required." }
                    return@Thread
                }

                var chosenRate = 44100
                var micHardwareNote = ""
                for (candidateRate in intArrayOf(44100, 48000, 16000)) {
                    val created = createRecorderForRole(role, candidateRate)
                    if (created.first != null) {
                        recorder = created.first
                        micHardwareNote = created.second
                        chosenRate = candidateRate
                        break
                    }
                }
                if (recorder == null) {
                    runOnUiThread {
                        binding.tvAudioStatus.text = "ERROR: $label AudioRecord failed to initialize on this ROM."
                    }
                    return@Thread
                }

                val maxSamples = chosenRate * durationSec
                val outBuf = ShortArray(maxSamples)
                val readBuf = ShortArray(2048)
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
                    val levelFrac = (rms / 7500.0).toFloat().coerceIn(0f, 1f)
                    if (myGen == workerGenerationId.get()) {
                        runOnUiThread {
                            if (!isDestroyed) binding.audioMeterView.setLevel(levelFrac)
                        }
                    }
                }

                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Throwable) {
                }
                recorder = null

                if (myGen == workerGenerationId.get()) {
                    val captured = outBuf.copyOf(totalRead)
                    recordedPcm = captured
                    recordedSampleRate = chosenRate
                    recordedPeak = maxPeak

                    if (maxPeak < 150 || totalRead < chosenRate / 3) {
                        micTested = false
                        runOnUiThread {
                            if (!isDestroyed) {
                                binding.tvAudioStatus.text = Lang.t(
                                    this,
                                    "GAGAL $label: Tidak ada suara masuk (Peak=$maxPeak < 150)! Cek mic tersumbat/mati atau bicara lebih dekat.$micHardwareNote",
                                    "FAILED $label: Zero/dead signal (Peak=$maxPeak < 150)! Mic blocked/dead or speak louder.$micHardwareNote"
                                )
                            }
                        }
                        return@Thread
                    }

                    micTested = true
                    if (role == MicRole.EXPRESS_AUTO_LOOPBACK) {
                        runOnUiThread {
                            if (!isDestroyed) {
                                binding.tvAudioStatus.text = Lang.t(
                                    this,
                                    "Suara tertangkap (Peak=$maxPeak)! Memutar ulang ke speaker + tes getar...",
                                    "Captured (Peak=$maxPeak)! Playing back loudly + vibration..."
                                )
                            }
                        }
                        playAmplifiedPcmBlocking(captured, chosenRate, maxPeak, myGen)
                        if (myGen == workerGenerationId.get()) {
                            speakerTested = true
                            runOnUiThread {
                                if (!isDestroyed) {
                                    triggerVibration(pattern = false)
                                    binding.tvAudioStatus.text = Lang.t(
                                        this,
                                        "TES KILAT SELESAI: Mic Peak=$maxPeak -> Speaker + Getar aktif!",
                                        "QUICK LOOPBACK DONE: Mic Peak=$maxPeak -> Speaker + Haptic fired!"
                                    )
                                }
                            }
                        }
                    } else {
                        runOnUiThread {
                            if (!isDestroyed) {
                                binding.tvAudioStatus.text = Lang.t(
                                    this,
                                    "Rekaman $label OK (Peak=$maxPeak @ ${chosenRate}Hz)$micHardwareNote. Klik PUTAR ULANG untuk dengar keras.",
                                    "$label OK (Peak=$maxPeak @ ${chosenRate}Hz)$micHardwareNote. Tap PLAYBACK to inspect."
                                )
                            }
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
                    restoreAudioRouting()
                    isWorking = false
                }
            }
        }.also { it.start() }
    }

    private fun buildNormalizedLoudPcm(raw: ShortArray, peak: Int): ShortArray {
        val safePeak = peak.coerceAtLeast(1)
        // Boost quiet outdoor COD speech toward 25,000 amplitude (capped at 12x to avoid harsh static floor hiss).
        val gain = (25000f / safePeak.toFloat()).coerceIn(1.0f, 12.0f)
        val out = ShortArray(raw.size)
        for (i in raw.indices) {
            val boosted = (raw[i] * gain).roundToInt().coerceIn(-32767, 32767)
            out[i] = boosted.toShort()
        }
        return out
    }

    private fun playAmplifiedPcmBlocking(raw: ShortArray, sampleRate: Int, peak: Int, myGen: Long) {
        if (raw.isEmpty()) return
        val amplified = buildNormalizedLoudPcm(raw, peak)
        val volInfo = ensureAudibleVolume(AudioManager.STREAM_MUSIC)
        var track: AudioTrack? = null
        try {
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
                .setBufferSizeInBytes(amplified.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            applySpeakerRouting(track)
            speakerTested = true
            playStaticPcmAndDrain(track, amplified, sampleRate, 1, myGen)
            if (myGen == workerGenerationId.get()) {
                runOnUiThread {
                    if (!isDestroyed) {
                        binding.tvAudioStatus.text = Lang.t(
                            this,
                            "Putar ulang selesai ($volInfo, Peak=$peak). Pastikan suara jernih di speaker.",
                            "Playback finished ($volInfo, Peak=$peak). Verify speaker voice clarity."
                        )
                    }
                }
            }
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.stop()
                track?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun playbackRecordedPcm() {
        val pcm = recordedPcm
        if (pcm == null || pcm.isEmpty()) {
            binding.tvAudioStatus.text = Lang.t(
                this,
                "Belum ada rekaman. Tekan MIC UTAMA / SEKUNDER atau TES KILAT dulu.",
                "No recording yet. Record Mic or Quick Loopback first."
            )
            return
        }
        if (recordedPeak < 150) {
            binding.tvAudioStatus.text = Lang.t(
                this,
                "Rekaman sebelumnya kosong/bisu (Peak=$recordedPeak). Rekam ulang dengan bicara lebih dekat!",
                "Previous recording was silent (Peak=$recordedPeak). Re-record closer to mic!"
            )
            return
        }
        stopCurrentWorker()
        val myGen = workerGenerationId.incrementAndGet()
        isWorking = true
        val rate = recordedSampleRate
        val peak = recordedPeak
        binding.tvAudioStatus.text = Lang.t(
            this,
            "Memutar rekaman mic (Auto-Gain Boost, Peak=$peak @ ${rate}Hz)...",
            "Playing back mic recording (Auto-Gain Boost, Peak=$peak @ ${rate}Hz)..."
        )

        workerThread = Thread {
            try {
                playAmplifiedPcmBlocking(pcm, rate, peak, myGen)
            } catch (t: Throwable) {
                if (myGen == workerGenerationId.get()) {
                    runOnUiThread { binding.tvAudioStatus.text = "Playback error: ${t.message}" }
                }
            } finally {
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
        savedMediaVolume?.let { orig ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0)
            } catch (_: Throwable) {
            }
            savedMediaVolume = null
        }
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
