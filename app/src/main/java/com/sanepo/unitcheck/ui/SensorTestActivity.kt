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
import com.sanepo.unitcheck.databinding.ActivitySensorTestBinding
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

    private val stepPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            registerAllSensors()
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

        proximitySensorPresent = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

        binding.btnBackSensor.text = Lang.t(this, "KEMBALI", "BACK")
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
            DiagnosticStore.updateItem(
                "sensors_biometrics",
                TestStatus.PASS,
                "Sensors=$sensorTotalCount | Prox=${if (proximitySensorPresent) "FAR+NEAR OK" else "N/A"} | AccelDelta=${String.format(Locale.US, "%.2f", accelDelta)} | Bio=${if (biometricVerified) "AUTH OK" else bioState}"
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

    private fun registerAllSensors() {
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
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerAllSensors()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxR = event.sensor.maximumRange
                val near = event.values[0] < maxR && event.values[0] < 5f
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
            Sensor.TYPE_PRESSURE -> pressureHpa = event.values[0]
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
            append("Barometer : ${pressureHpa?.let { String.format(Locale.US, "%.1f hPa", it) } ?: "N/A"} | Steps: ${stepsTotal?.toInt()?.toString() ?: "N/A"}")
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
