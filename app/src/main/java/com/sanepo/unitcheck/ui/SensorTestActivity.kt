package com.sanepo.unitcheck.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivitySensorTestBinding
import com.sanepo.unitcheck.engine.OemDeviceHelper
import com.sanepo.unitcheck.engine.SensorAndRFEngine
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.Locale
import kotlin.math.sqrt

class SensorTestActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivitySensorTestBinding
    private lateinit var sensorManager: SensorManager
    private var proximitySensorPresent = false
    private var pressureSensorPresent = false
    private var proximityNear = false
    private var sawProximityFar = false
    private var sawProximityNear = false
    private val proximityTested: Boolean
        get() = sawProximityFar && sawProximityNear

    private var accelEventCount = 0
    private var minAccelMag = Float.MAX_VALUE
    private var maxAccelMag = 0f

    private var luxValue = -1f
    private var gyroValues = floatArrayOf(0f, 0f, 0f)
    private var accelValues = floatArrayOf(0f, 0f, 0f)
    private var magValues = floatArrayOf(0f, 0f, 0f)
    private var pressureHpa: Float? = null
    private var stepsTotal: Float? = null
    private var biometricVerified = false
    private var sensorTotalCount = 0
    private var stepPermRequested = false

    // Barometer IP68 seal check state (only active when TYPE_PRESSURE hardware exists).
    private var baroSealTesting = false
    private var baroSealPhasePress = false
    private val baroBaselineSamples = ArrayList<Float>()
    private var baroBaselineAvg = 0f
    private var baroPeakDelta = 0f
    private var baroSealTimer: CountDownTimer? = null

    private val stepPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            registerAllSensors(fastPressure = baroSealTesting)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySensorTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootSensorTest) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        val proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensorPresent = proxSensor != null
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        pressureSensorPresent = pressureSensor != null

        binding.btnBackSensor.contentDescription = Lang.t(this, "Kembali", "Back")
        binding.tvSensorTitle.text = Lang.t(this, "Sensor & Biometrik", "Sensors & Biometrics")
        binding.tvBiometricHeader.text = Lang.t(this, "KEAMANAN BIOMETRIK (SIDIK JARI / FACE)", "BIOMETRICS (FINGERPRINT / FACE)")
        binding.btnTestBiometric.text = Lang.t(this, "UJI AUTENTIKASI BIOMETRIK", "RUN BIOMETRIC PROMPT")
        binding.tvLevelHeader.text = Lang.t(this, "ACCELEROMETER & GYRO WATERPASS", "ACCELEROMETER & GYRO SPIRIT LEVEL")
        binding.tvLiveSensorsHeader.text = Lang.t(
            this,
            "NILAI SENSOR REAL-TIME (TUTUP & BUKA SENSOR ATAS 2-ARAH)",
            "LIVE TELEMETRY (COVER & UNCOVER TOP PROXIMITY SENSOR)"
        )
        binding.tvAllSensorsHeader.text = Lang.t(this, "DAFTAR SEMUA SENSOR HARDWARE", "DETECTED HARDWARE SENSORS")
        binding.btnSensorPass.text = Lang.t(this, "SENSOR PASS", "SENSORS PASS")
        binding.btnSensorFail.text = Lang.t(this, "ADA SENSOR RUSAK", "SENSOR FAIL")

        setupProximityGuidance(proxSensor)
        setupBarometerSealSection()

        val bioState = SensorAndRFEngine.checkBiometricHardware(this)
        binding.tvBiometricState.text = bioState

        val allSensors = SensorAndRFEngine.listHardwareSensors(this)
        sensorTotalCount = allSensors.size
        binding.tvAllSensorsList.text = if (allSensors.isEmpty()) {
            Lang.t(this, "Tidak ada sensor terdeteksi.", "No sensors reported by SensorManager.")
        } else {
            allSensors.joinToString("\n") { s ->
                "${s.name} [${s.vendor}] | ${s.powerMa}mA | max=${s.maxRange}"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED &&
            !stepPermRequested
        ) {
            stepPermRequested = true
            stepPermLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        binding.btnBackSensor.setOnClickListener { finish() }
        binding.btnTestBiometric.setOnClickListener { launchBiometricPrompt() }

        binding.btnSensorPass.setOnClickListener {
            if (proximitySensorPresent && !proximityTested) {
                val msg = Lang.t(
                    this,
                    "Uji Proximity 2-arah dulu (FAR + NEAR: tutup sensor atas lalu buka lagi) sebelum PASS!",
                    "Test bidirectional Proximity (FAR + NEAR: cover and uncover top sensor) before PASS!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvLiveSensorValues.text = "$msg\n\n${binding.tvLiveSensorValues.text}"
                return@setOnClickListener
            }
            val accelDelta = if (accelEventCount > 0) (maxAccelMag - minAccelMag).coerceAtLeast(0f) else 0f
            if (!proximitySensorPresent && accelEventCount == 0) {
                val msg = Lang.t(
                    this,
                    "Belum ada data sensor Accelerometer/Gravity terbaca!",
                    "No Accelerometer/sensor stream received yet!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val baroNote = if (pressureSensorPresent && DiagnosticStore.barometerSealResult.isNotBlank()) {
                " | ${DiagnosticStore.barometerSealResult}"
            } else {
                ""
            }
            DiagnosticStore.updateItem(
                "sensors_biometrics",
                TestStatus.PASS,
                "Sensors=$sensorTotalCount | Prox=${if (proximitySensorPresent) "FAR+NEAR OK" else "N/A"} | AccelDelta=${String.format(Locale.US, "%.2f", accelDelta)} | Bio=${if (biometricVerified) "AUTH OK" else bioState}$baroNote"
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.btnSensorFail.setOnClickListener {
            DiagnosticStore.updateItem(
                "sensors_biometrics",
                TestStatus.FAIL,
                "Sensor/Biometric failure flagged (ProxBidirectional=$proximityTested | Bio=$biometricVerified)"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
        renderLiveText()
    }

    private fun setupProximityGuidance(proxSensor: Sensor?) {
        if (proxSensor == null) {
            binding.tvProximityHint.visibility = View.GONE
            return
        }
        val combined = "${proxSensor.name} ${proxSensor.vendor}".lowercase(Locale.US)
        val oemProfile = OemDeviceHelper.buildProfile()
        val isVirtualOrUltrasonic = combined.contains("elliptic") ||
            combined.contains("virtual") ||
            combined.contains("fusion") ||
            combined.contains("stk_virt") ||
            combined.contains("goodix_virt") ||
            (oemProfile.proximityIsVirtualLikely && !combined.contains("ams") && !combined.contains("liteon") && !combined.contains("vishay") && !combined.contains("capella"))

        binding.tvProximityHint.visibility = View.VISIBLE
        if (isVirtualOrUltrasonic) {
            binding.tvProximityHint.setBackgroundResource(R.drawable.bg_badge_warn)
            binding.tvProximityHint.setTextColor(ContextCompat.getColor(this, R.color.status_warn_fg))
            binding.tvProximityHint.text = Lang.t(
                this,
                "PROXIMITY VIRTUAL/ULTRASONIC (${proxSensor.name}): Jangan tes tiduran di meja! Angkat HP tegak seperti menelepon & tempelkan ke telinga/tangan untuk memicu NEAR.",
                "VIRTUAL/ULTRASONIC PROXIMITY (${proxSensor.name}): Do NOT test flat on a desk! Hold upright like a phone call & bring near ear/palm to trigger NEAR."
            )
        } else {
            binding.tvProximityHint.setBackgroundResource(R.drawable.bg_card_sharp)
            binding.tvProximityHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvProximityHint.text = Lang.t(
                this,
                "PROXIMITY INFRAMERAH (${proxSensor.name}): Tutup bagian earpiece atas layar (NEAR) lalu buka (FAR).",
                "IR PROXIMITY (${proxSensor.name}): Cover top earpiece bezel (NEAR) then uncover (FAR)."
            )
        }
    }

    private fun setupBarometerSealSection() {
        // Strictly show ONLY when hardware Barometer (TYPE_PRESSURE) exists on this phone.
        if (!pressureSensorPresent) {
            binding.tvBaroSealHeader.visibility = View.GONE
            binding.cardBaroSeal.visibility = View.GONE
            return
        }
        binding.tvBaroSealHeader.visibility = View.VISIBLE
        binding.cardBaroSeal.visibility = View.VISIBLE
        binding.tvBaroSealHeader.text = Lang.t(
            this,
            "UJI SEGEL KEDAP UDARA / BEKAS BONGKAR (BAROMETER IP68)",
            "AIRTIGHTNESS / WATER-SEAL INTEGRITY TEST (BAROMETER)"
        )
        binding.btnStartBaroSealTest.text = Lang.t(
            this,
            "MULAI TES TEKANAN SEGEL (5 DETIK)",
            "START 5s AIRTIGHTNESS SEAL TEST"
        )
        binding.tvBaroSealStatus.text = if (DiagnosticStore.barometerSealResult.isNotBlank()) {
            DiagnosticStore.barometerSealResult
        } else {
            Lang.t(
                this,
                "Unit ini memiliki Barometer! Tekan tombol di bawah lalu tekan layar tengah agak mantap 2 jempol: jika segel pabrik masih rapat, nilai hPa melonjak (+0.25 hPa ke atas). Jika 0.00 datar -> indikasi pernah dibongkar/lem renggang.",
                "Barometer detected! Start test then press center screen firmly with thumbs: intact factory seals spike +0.25 hPa or more; flat 0.00 hPa suggests re-opened/unsealed housing."
            )
        }
        binding.btnStartBaroSealTest.setOnClickListener {
            if (!baroSealTesting) startBarometerSealTest()
        }
    }

    private fun startBarometerSealTest() {
        baroSealTimer?.cancel()
        baroSealTesting = true
        baroSealPhasePress = false
        baroBaselineSamples.clear()
        baroBaselineAvg = 0f
        baroPeakDelta = 0f
        binding.btnStartBaroSealTest.isEnabled = false
        registerAllSensors(fastPressure = true)

        baroSealTimer = object : CountDownTimer(5000L, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = 5000L - millisUntilFinished
                if (elapsed < 1200L) {
                    binding.tvBaroSealStatus.setTextColor(ContextCompat.getColor(this@SensorTestActivity, R.color.text_secondary))
                    binding.tvBaroSealStatus.text = Lang.t(
                        this@SensorTestActivity,
                        "1/2 Kalibrasi tekanan awal... JANGAN SENTUH LAYAR (${(1200L - elapsed) / 1000f}s)",
                        "1/2 Calibrating baseline pressure... HANDS OFF"
                    )
                } else {
                    if (!baroSealPhasePress) {
                        baroSealPhasePress = true
                        baroBaselineAvg = if (baroBaselineSamples.isNotEmpty()) {
                            baroBaselineSamples.average().toFloat()
                        } else {
                            pressureHpa ?: 1013f
                        }
                    }
                    binding.tvBaroSealStatus.setTextColor(ContextCompat.getColor(this@SensorTestActivity, R.color.accent_amber_text))
                    binding.tvBaroSealStatus.text = String.format(
                        Locale.US,
                        Lang.t(
                            this@SensorTestActivity,
                            "2/2 TEKAN LAYAR DGN 2 JEMPOL SEKARANG! (%ds)\nBaseline: %.2f hPa | Lonjakan Maks: +%.2f hPa",
                            "2/2 PRESS SCREEN FIRMLY NOW! (%ds)\nBaseline: %.2f hPa | Peak Delta: +%.2f hPa"
                        ),
                        ((millisUntilFinished + 999L) / 1000L).toInt(),
                        baroBaselineAvg,
                        baroPeakDelta
                    )
                }
            }

            override fun onFinish() {
                baroSealTesting = false
                baroSealPhasePress = false
                binding.btnStartBaroSealTest.isEnabled = true
                registerAllSensors(fastPressure = false)

                val verdict: String
                val colorRes: Int
                when {
                    baroPeakDelta >= 0.25f -> {
                        colorRes = R.color.status_pass_fg
                        verdict = String.format(
                            Locale.US,
                            Lang.t(
                                this@SensorTestActivity,
                                "SEGEL RAPAT (+%.2f hPa): Indikasi kuat seal IP68 utuh / belum bongkaran.",
                                "SEAL INTACT (+%.2f hPa): Airtight pressure spike verified."
                            ),
                            baroPeakDelta
                        )
                    }
                    baroPeakDelta >= 0.08f -> {
                        colorRes = R.color.status_warn_fg
                        verdict = String.format(
                            Locale.US,
                            Lang.t(
                                this@SensorTestActivity,
                                "PERUBAHAN TIPIS (+%.2f hPa): Cek lem backdoor & karet seal slot SIM.",
                                "WEAK SEAL (+%.2f hPa): Minor pressure rise; inspect adhesive/SIM seal."
                            ),
                            baroPeakDelta
                        )
                    }
                    else -> {
                        colorRes = R.color.status_fail_fg
                        verdict = String.format(
                            Locale.US,
                            Lang.t(
                                this@SensorTestActivity,
                                "TEKANAN DATAR (+%.2f hPa): Tidak kedap udara - waspada bekas bongkaran / lem renggang!",
                                "NO SEAL / FLAT (+%.2f hPa): Air leaks immediately - possible re-opened unit!"
                            ),
                            baroPeakDelta
                        )
                    }
                }
                DiagnosticStore.barometerSealResult = String.format(Locale.US, "BaroSeal=+%.2fhPa", baroPeakDelta)
                binding.tvBaroSealStatus.setTextColor(ContextCompat.getColor(this@SensorTestActivity, colorRes))
                binding.tvBaroSealStatus.text = verdict
            }
        }.start()
    }

    private fun launchBiometricPrompt() {
        val bm = BiometricManager.from(this)
        val canAuth = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            binding.tvBiometricState.text = SensorAndRFEngine.checkBiometricHardware(this)
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                biometricVerified = true
                binding.tvBiometricState.text = Lang.t(
                    this@SensorTestActivity,
                    "PASS: Autentikasi biometrik hardware berhasil!",
                    "PASS: Biometric hardware authentication succeeded!"
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                binding.tvBiometricState.text = "Biometric error ($errorCode): $errString"
            }

            override fun onAuthenticationFailed() {
                binding.tvBiometricState.text = Lang.t(
                    this@SensorTestActivity,
                    "Sidik jari/wajah tidak cocok, coba ulang.",
                    "Biometric not recognized, try again."
                )
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(Lang.t(this, "Verifikasi Biometrik Unit", "Unit Biometric Verification"))
            .setSubtitle(Lang.t(this, "Tempelkan jari atau hadapkan wajah ke sensor", "Touch fingerprint sensor or look at camera"))
            .setNegativeButtonText(Lang.t(this, "Batal", "Cancel"))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()
        prompt.authenticate(info)
    }

    private fun registerAllSensors(fastPressure: Boolean) {
        sensorManager.unregisterListener(this)
        val types = intArrayOf(
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_STEP_COUNTER
        )
        for (t in types) {
            sensorManager.getDefaultSensor(t)?.let {
                val delay = if (t == Sensor.TYPE_PRESSURE && fastPressure) {
                    SensorManager.SENSOR_DELAY_GAME
                } else {
                    SensorManager.SENSOR_DELAY_UI
                }
                sensorManager.registerListener(this, it, delay)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerAllSensors(fastPressure = baroSealTesting)
    }

    override fun onPause() {
        baroSealTimer?.cancel()
        baroSealTesting = false
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxR = event.sensor.maximumRange
                val near = if (maxR > 0f) {
                    event.values[0] < maxR && event.values[0] < 5f
                } else {
                    event.values[0] == 0f
                }
                if (near) sawProximityNear = true else sawProximityFar = true
                proximityNear = near
            }
            Sensor.TYPE_LIGHT -> luxValue = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.copyOf(3)
                accelEventCount++
                val mag = sqrt(
                    accelValues[0] * accelValues[0] + accelValues[1] * accelValues[1] + accelValues[2] * accelValues[2]
                )
                if (mag < minAccelMag) minAccelMag = mag
                if (mag > maxAccelMag) maxAccelMag = mag
                binding.spiritLevelView.updateAcceleration(accelValues[0], accelValues[1])
            }
            Sensor.TYPE_GYROSCOPE -> gyroValues = event.values.copyOf(3)
            Sensor.TYPE_MAGNETIC_FIELD -> magValues = event.values.copyOf(3)
            Sensor.TYPE_PRESSURE -> {
                val p = event.values[0]
                pressureHpa = p
                if (baroSealTesting) {
                    if (!baroSealPhasePress) {
                        baroBaselineSamples.add(p)
                    } else if (baroBaselineAvg > 0f) {
                        val delta = p - baroBaselineAvg
                        if (delta > baroPeakDelta) baroPeakDelta = delta
                    }
                }
            }
            Sensor.TYPE_STEP_COUNTER -> stepsTotal = event.values[0]
        }
        renderLiveText()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun renderLiveText() {
        val magStrength = sqrt(
            magValues[0] * magValues[0] + magValues[1] * magValues[1] + magValues[2] * magValues[2]
        )
        val magWarn = if (magStrength > 120f) " [HIGH INTERFERENCE]" else ""
        val proxLine = if (!proximitySensorPresent) {
            "Proximity : No Hardware Sensor"
        } else {
            "Proximity : ${if (proximityNear) "NEAR (Covered)" else "FAR (Open)"} | FAR=$sawProximityFar NEAR=$sawProximityNear"
        }
        binding.tvLiveSensorValues.text = buildString {
            appendLine(proxLine)
            appendLine("Light Lux : ${if (luxValue >= 0f) String.format(Locale.US, "%.1f lx", luxValue) else "N/A"}")
            appendLine("Accel     : X=${String.format(Locale.US, "%.2f", accelValues[0])} Y=${String.format(Locale.US, "%.2f", accelValues[1])} Z=${String.format(Locale.US, "%.2f", accelValues[2])}")
            appendLine("Gyro      : X=${String.format(Locale.US, "%.2f", gyroValues[0])} Y=${String.format(Locale.US, "%.2f", gyroValues[1])} Z=${String.format(Locale.US, "%.2f", gyroValues[2])}")
            appendLine("Compass   : ${String.format(Locale.US, "%.1f uT", magStrength)}$magWarn")
            append("Barometer : ${pressureHpa?.let { String.format(Locale.US, "%.2f hPa", it) } ?: "N/A"} | Steps: ${stepsTotal?.toInt()?.toString() ?: "N/A"}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
