package com.sanepo.unitcheck.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import com.sanepo.unitcheck.model.ConnectivitySnapshot
import com.sanepo.unitcheck.model.SensorInfoItem
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SensorAndRFEngine {

    fun listHardwareSensors(context: Context): List<SensorInfoItem> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        return sm.getSensorList(Sensor.TYPE_ALL).map { s ->
            SensorInfoItem(
                type = s.type,
                name = s.name.orEmpty(),
                vendor = s.vendor.orEmpty(),
                powerMa = s.power,
                maxRange = s.maximumRange,
                resolution = s.resolution
            )
        }
    }

    fun checkBiometricHardware(context: Context): String {
        val bm = BiometricManager.from(context)
        return when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> "READY (Hardware Enrolled)"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "PRESENT (No Finger/Face Enrolled)"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO BIOMETRIC HARDWARE"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "HARDWARE BUSY / UNAVAILABLE"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "SECURITY UPDATE REQUIRED"
            else -> "UNSUPPORTED"
        }
    }

    @SuppressLint("MissingPermission")
    fun collectConnectivitySnapshot(
        context: Context,
        gnssVisible: Int = 0,
        gnssUsed: Int = 0,
        gnssConstellations: String = "Idle",
        ttffSec: Float? = null,
        existingCellDataCheck: String = ""
    ): ConnectivitySnapshot {
        val baseband = try {
            Build.getRadioVersion().orEmpty().ifBlank { "Unknown / Missing" }
        } catch (_: Throwable) {
            "Restricted"
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasPhonePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

        val slotCount = when {
            tm == null -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> tm.activeModemCount
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                @Suppress("DEPRECATION")
                tm.phoneCount
            }
            else -> 1
        }

        val sim1State = readSimSlotState(tm, 0)
        val sim2State = if (slotCount >= 2) readSimSlotState(tm, 1) else "No Slot 2"

        val perSlot = readPerSlotDetails(context, tm, slotCount, sim1State, sim2State, hasPhonePerm)

        val operatorName = perSlot.primaryOperator.ifBlank {
            try {
                tm?.networkOperatorName.orEmpty().ifBlank { "No Carrier" }
            } catch (_: Throwable) {
                "Unknown"
            }
        }

        val networkType = perSlot.primaryNetworkType.ifBlank {
            if (tm != null && hasPhonePerm) {
                try {
                    @Suppress("DEPRECATION")
                    val typeInt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType else tm.networkType
                    mapNetworkType(typeInt)
                } catch (_: Throwable) {
                    "Restricted"
                }
            } else if (tm == null) {
                "No Modem"
            } else {
                "Perm Required"
            }
        }

        val (defaultSvcLabel, defaultInSvc) = readServiceState(context, tm)
        val serviceStateLabel = perSlot.combinedServiceLabel.ifBlank { defaultSvcLabel }
        val isInService = perSlot.anyInService ?: defaultInSvc
        val signalDbm = perSlot.primaryDbm ?: readSignalDbm(context, tm)

        val pm = context.packageManager
        val wifiSupported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifi5Ghz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && wm != null) {
            try {
                wm.is5GHzBandSupported
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        val wifi6Ghz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            try {
                wm.is6GHzBandSupported
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        val wifiInfoPair = readActiveWifiDetails(context, wm)
        val wifiScanCount = readWifiScanCount(context, wm)

        val btSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val bleSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val btEnabled = try {
            btAdapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }

        val nfcAdapter = try {
            NfcAdapter.getDefaultAdapter(context)
        } catch (_: Throwable) {
            null
        }
        val nfcStatus = when {
            nfcAdapter == null -> "NO HARDWARE"
            nfcAdapter.isEnabled -> "ENABLED"
            else -> "DISABLED"
        }

        return ConnectivitySnapshot(
            basebandVersion = baseband,
            simSlotCount = slotCount,
            sim1State = sim1State,
            sim2State = sim2State,
            operatorName = operatorName,
            networkType = networkType,
            serviceStateLabel = serviceStateLabel,
            isCellularInService = isInService,
            signalDbm = signalDbm,
            wifiSupported = wifiSupported,
            wifi5GhzSupported = wifi5Ghz,
            wifi6GhzSupported = wifi6Ghz,
            wifiConnectedSsid = wifiInfoPair.first,
            wifiLinkSpeedMbps = wifiInfoPair.second,
            wifiScanCount = wifiScanCount,
            bluetoothSupported = btSupported,
            bluetoothEnabled = btEnabled,
            bleSupported = bleSupported,
            nfcStatus = nfcStatus,
            gnssVisibleCount = gnssVisible,
            gnssUsedCount = gnssUsed,
            gnssConstellations = gnssConstellations,
            ttffSeconds = ttffSec,
            sim1Details = perSlot.sim1Text,
            sim2Details = perSlot.sim2Text,
            hasDualSimSplitBlock = perSlot.splitBlockDetected,
            cellularDataCheckSummary = existingCellDataCheck
        )
    }

    private data class PerSlotSummary(
        val sim1Text: String,
        val sim2Text: String,
        val combinedServiceLabel: String,
        val anyInService: Boolean?,
        val splitBlockDetected: Boolean,
        val primaryOperator: String,
        val primaryNetworkType: String,
        val primaryDbm: Int?
    )

    @SuppressLint("MissingPermission")
    private fun readPerSlotDetails(
        context: Context,
        tm: TelephonyManager?,
        slotCount: Int,
        sim1State: String,
        sim2State: String,
        hasPhonePerm: Boolean
    ): PerSlotSummary {
        if (tm == null) {
            return PerSlotSummary("No Modem", "No Modem", "", null, false, "", "", null)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !hasPhonePerm) {
            val s1 = if (sim1State == "SIM READY") "$sim1State | ${tm.networkOperatorName.orEmpty()}" else sim1State
            val s2 = if (slotCount >= 2) sim2State else "No Slot 2"
            return PerSlotSummary(s1, s2, "", null, false, "", "", null)
        }

        val slotInService = HashMap<Int, Boolean>()
        val slotText = HashMap<Int, String>()
        var firstOp = ""
        var firstNet = ""
        var firstDbm: Int? = null

        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subs = sm?.activeSubscriptionInfoList.orEmpty()
            for (sub in subs) {
                val slotIdx = sub.simSlotIndex
                val subTm = tm.createForSubscriptionId(sub.subscriptionId)
                val carrier = sub.carrierName?.toString().orEmpty()
                    .ifBlank { subTm.networkOperatorName.orEmpty() }
                    .ifBlank { "No Carrier" }
                val (svcLabel, inSvc) = readServiceState(context, subTm)
                val netStr = try {
                    mapNetworkType(subTm.dataNetworkType)
                } catch (_: Throwable) {
                    "Unknown"
                }
                val dbm = readSignalDbm(context, subTm)
                slotInService[slotIdx] = inSvc
                slotText[slotIdx] = "$carrier | $svcLabel | $netStr${dbm?.let { " | ${it}dBm" } ?: ""}"

                if (firstOp.isBlank() && carrier != "No Carrier") firstOp = carrier
                if (firstNet.isBlank() && !netStr.contains("Idle")) firstNet = netStr
                if (firstDbm == null && dbm != null) firstDbm = dbm
            }
        } catch (_: Throwable) {
        }

        val s1Final = slotText[0] ?: sim1State
        val s2Final = if (slotCount >= 2) (slotText[1] ?: sim2State) else "No Slot 2"

        val sim1Ready = sim1State == "SIM READY"
        val sim2Ready = slotCount >= 2 && sim2State == "SIM READY"
        val s1Ok = slotInService[0]
        val s2Ok = slotInService[1]

        // Catch single-slot IMEI blocks where one inserted SIM gets service while the other is EMERGENCY_ONLY / OUT_OF_SERVICE.
        val splitBlock = (sim1Ready && s1Ok == false) || (sim2Ready && s2Ok == false)
        val combinedSvc = when {
            s1Ok == true && s2Ok == true -> "SIM1+SIM2 IN_SERVICE"
            splitBlock && (s1Ok == true || s2Ok == true) -> "PER-SLOT BLOCK ALERT"
            s1Ok == true -> "SIM1 IN_SERVICE"
            s2Ok == true -> "SIM2 IN_SERVICE"
            s1Ok == false || s2Ok == false -> "NO SERVICE (SIM Blocked/RF Fault)"
            else -> ""
        }
        val anyOk = when {
            s1Ok == true || s2Ok == true -> true
            s1Ok == false || s2Ok == false -> false
            else -> null
        }
        return PerSlotSummary(
            sim1Text = s1Final,
            sim2Text = s2Final,
            combinedServiceLabel = combinedSvc,
            anyInService = anyOk,
            splitBlockDetected = splitBlock,
            primaryOperator = firstOp,
            primaryNetworkType = firstNet,
            primaryDbm = firstDbm
        )
    }

    /**
     * Executes a 3-second real cellular data reachability probe bound explicitly to TRANSPORT_CELLULAR,
     * bypassing Wi-Fi when both are connected so actual SIM 4G/5G TX/RX modem traffic is tested.
     */
    fun runCellularDataProbe(context: Context): Pair<Boolean, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Pair(false, "ConnectivityManager unavailable")

        // First see if an already-active TRANSPORT_CELLULAR network exists across allNetworks.
        @Suppress("DEPRECATION")
        val existingCellNet = try {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Throwable) {
            null
        }

        if (existingCellNet != null) {
            return testTcpOverNetwork(existingCellNet)
        }

        val latch = CountDownLatch(1)
        val acquiredNet = AtomicReference<Network?>(null)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                acquiredNet.compareAndSet(null, network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }

        return try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cm.requestNetwork(req, callback, 2800)
            } else {
                cm.requestNetwork(req, callback)
            }
            latch.await(3000, TimeUnit.MILLISECONDS)
            val net = acquiredNet.get()
            if (net == null) {
                Pair(false, "Data Seluler tidak aktif / kartu tidak ada akses internet")
            } else {
                testTcpOverNetwork(net)
            }
        } catch (t: Throwable) {
            Pair(false, "Gagal akses jaringan seluler (${t.javaClass.simpleName})")
        } finally {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
            }
        }
    }

    private fun testTcpOverNetwork(network: Network): Pair<Boolean, String> {
        val targets = arrayOf("1.1.1.1" to 443, "8.8.8.8" to 53)
        for ((host, port) in targets) {
            var socket: java.net.Socket? = null
            try {
                val start = System.currentTimeMillis()
                socket = network.socketFactory.createSocket()
                socket.connect(InetSocketAddress(host, port), 1800)
                val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                return Pair(true, "DATA SELULER OK (${rtt}ms via SIM -> $host)")
            } catch (_: Throwable) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
            }
        }
        return Pair(false, "SIM terhubung ke seluler tapi gagal kirim data (Cek kuota / RF PA)")
    }

    private fun readSimSlotState(tm: TelephonyManager?, slot: Int): String {
        if (tm == null) return "No Modem"
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                tm.getSimState(slot)
            } catch (_: Throwable) {
                tm.simState
            }
        } else {
            tm.simState
        }
        return when (state) {
            TelephonyManager.SIM_STATE_READY -> "SIM READY"
            TelephonyManager.SIM_STATE_ABSENT -> "EMPTY"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN LOCKED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK LOCKED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "CARRIER LOCKED"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM DISABLED"
            else -> "UNKNOWN"
        }
    }

    private fun mapNetworkType(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G HSPA/UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi Calling"
            else -> "No Data / Idle"
        }
    }

    @SuppressLint("MissingPermission")
    private fun readServiceState(context: Context, tm: TelephonyManager?): Pair<String, Boolean> {
        if (tm == null) return Pair("No Modem", false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val ready = tm.simState == TelephonyManager.SIM_STATE_READY &&
                tm.networkOperatorName.isNotBlank()
            return Pair(if (ready) "IN_SERVICE (Legacy)" else "UNKNOWN / NO_SERVICE", ready)
        }
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return Pair("Perm Required", false)
        return try {
            val ss = tm.serviceState ?: return Pair("UNKNOWN", false)
            when (ss.state) {
                ServiceState.STATE_IN_SERVICE -> Pair("IN_SERVICE", true)
                ServiceState.STATE_OUT_OF_SERVICE -> Pair("OUT_OF_SERVICE", false)
                ServiceState.STATE_EMERGENCY_ONLY -> Pair("EMERGENCY_ONLY", false)
                ServiceState.STATE_POWER_OFF -> Pair("RADIO_OFF", false)
                else -> Pair("UNKNOWN", false)
            }
        } catch (_: Throwable) {
            Pair("Restricted", false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readSignalDbm(context: Context, tm: TelephonyManager?): Int? {
        if (tm == null) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val cells = tm.allCellInfo ?: return null
            for (c in cells) {
                if (!c.isRegistered) continue
                when (c) {
                    is CellInfoLte -> return c.cellSignalStrength.dbm
                    is CellInfoWcdma -> return c.cellSignalStrength.dbm
                    is CellInfoGsm -> return c.cellSignalStrength.dbm
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c is CellInfoNr) {
                        return c.cellSignalStrength.dbm
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun readActiveWifiDetails(context: Context, wm: WifiManager?): Pair<String, Int?> {
        if (wm == null || !wm.isWifiEnabled) return Pair("Wi-Fi Off", null)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return Pair("Not Connected", null)
            }
        }
        return try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ssid = info?.ssid?.trim('"')?.takeUnless { it.equals("<unknown ssid>", ignoreCase = true) }
                ?: "Connected (SSID Masked)"
            val linkSpeed = info?.linkSpeed?.takeIf { it > 0 }
            Pair(ssid, linkSpeed)
        } catch (_: Throwable) {
            Pair("Connected", null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readWifiScanCount(context: Context, wm: WifiManager?): Int? {
        if (wm == null || !wm.isWifiEnabled) return null
        val canScan = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED)
        if (!canScan) return null
        return try {
            @Suppress("DEPRECATION")
            wm.scanResults?.size ?: 0
        } catch (_: Throwable) {
            null
        }
    }
}
