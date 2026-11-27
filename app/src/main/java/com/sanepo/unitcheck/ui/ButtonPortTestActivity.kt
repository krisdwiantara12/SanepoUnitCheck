package com.sanepo.unitcheck.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.databinding.ActivityButtonPortTestBinding
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang

class ButtonPortTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityButtonPortTestBinding
    private var volUpHit = false
    private var volDownHit = false
    private var backKeyHit = false
    private var powerCycleHit = false
    private var homeRecentsHit = false
    private val extraKeys = LinkedHashSet<String>()

    private var headsetPlugged = false
    private var headsetHasMic = false
    private var usbPowerConnected = false
    private var usbOtgDevicesCount = 0

    private val hardwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> {
                    powerCycleHit = true
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    headsetPlugged = intent.getIntExtra("state", 0) == 1
                    headsetHasMic = intent.getIntExtra("microphone", 0) == 1
                }
                Intent.ACTION_POWER_CONNECTED -> usbPowerConnected = true
                Intent.ACTION_POWER_DISCONNECTED -> usbPowerConnected = false
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshUsbDevices()
                }
            }
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityButtonPortTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootButtonPortTest) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        binding.btnBackKeys.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvKeysTitle.text = Lang.t(this, "Tombol Fisik, Trigger & Port", "Buttons, Triggers & Ports")
        binding.tvKeysHint.text = Lang.t(
            this,
            "Tekan Volume Atas/Bawah, Back (1x untuk tes), matikan/nyalakan layar untuk Power, atau colok charger / USB OTG / Headset 3.5mm.",
            "Press Volume +/-, Back (1st press tests Back key), lock/unlock screen for Power key, or plug Charger / USB OTG / 3.5mm Headset."
        )
        binding.tvKeysHeader.text = Lang.t(this, "STATUS TOMBOL FISIK", "PHYSICAL KEYS MATRIX")
        binding.tvPortsHeader.text = Lang.t(this, "STATUS PORT USB, OTG & JACK 3.5MM", "USB, OTG & 3.5MM AUDIO JACK")
        binding.btnKeysPass.text = Lang.t(this, "TOMBOL & PORT PASS", "KEYS & PORTS PASS")
        binding.btnKeysFail.text = Lang.t(this, "ADA TOMBOL/PORT RUSAK", "KEY / PORT FAIL")

        binding.btnBackKeys.setOnClickListener { finish() }
        binding.btnKeysPass.setOnClickListener {
            DiagnosticStore.updateItem(
                "buttons_ports",
                TestStatus.PASS,
                "Vol+=$volUpHit Vol-=$volDownHit Power=$powerCycleHit Back=$backKeyHit OTG=$usbOtgDevicesCount"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
        binding.btnKeysFail.setOnClickListener {
            DiagnosticStore.updateItem(
                "buttons_ports",
                TestStatus.FAIL,
                "FAIL flagged (Vol+=$volUpHit Vol-=$volDownHit Power=$powerCycleHit)"
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        refreshInitialPorts()
        registerHardwareReceivers()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backKeyHit = true
                renderStatus()
                isEnabled = false
            }
        })
        renderStatus()
    }

    private fun refreshInitialPorts() {
        val batIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        usbPowerConnected = plugged != 0
        refreshUsbDevices()
    }

    private fun refreshUsbDevices() {
        val um = getSystemService(Context.USB_SERVICE) as? UsbManager
        usbOtgDevicesCount = um?.deviceList?.size ?: 0
    }

    private fun registerHardwareReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hardwareReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(hardwareReceiver, filter)
        }
    }

    override fun onUserLeaveHint() {
        homeRecentsHit = true
        renderStatus()
        super.onUserLeaveHint()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    volUpHit = true
                    renderStatus()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    volDownHit = true
                    renderStatus()
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    finish()
                    return true
                }
                else -> {
                    val codeName = KeyEvent.keyCodeToString(event.keyCode)
                    if (codeName != "KEYCODE_BACK") {
                        extraKeys.add(codeName)
                        renderStatus()
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun renderStatus() {
        fun mark(ok: Boolean) = if (ok) "[PASS]" else "[WAIT]"
        binding.tvKeyChecklist.text = buildString {
            appendLine("${mark(volUpHit)} Volume Up")
            appendLine("${mark(volDownHit)} Volume Down")
            appendLine("${mark(powerCycleHit)} Power / Sleep-Wake")
            appendLine("${mark(backKeyHit)} Back Key (Tap system Back once)")
            appendLine("${mark(homeRecentsHit)} Home / Recents Switch")
            append("Extra/Triggers: ${if (extraKeys.isEmpty()) "None pressed" else extraKeys.joinToString(", ")}")
        }

        binding.tvPortStatus.text = buildString {
            appendLine("USB Charging Cable : ${if (usbPowerConnected) "CONNECTED" else "Not Plugged"}")
            appendLine("USB OTG Host Device: $usbOtgDevicesCount device(s) attached")
            append("3.5mm Audio Jack   : ${if (headsetPlugged) "PLUGGED (Mic=$headsetHasMic)" else "Not Plugged"}")
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(hardwareReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
