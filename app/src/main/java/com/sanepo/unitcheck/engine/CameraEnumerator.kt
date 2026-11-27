package com.sanepo.unitcheck.engine

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.sanepo.unitcheck.model.CameraLensInfo
import kotlin.math.roundToInt

object CameraEnumerator {

    fun enumerate(context: Context): List<CameraLensInfo> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val ids = try {
            cm.cameraIdList
        } catch (_: Throwable) {
            return emptyList()
        }

        val result = ArrayList<CameraLensInfo>()
        for (id in ids) {
            try {
                val ch = cm.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList().orEmpty()
                val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.toList().orEmpty()
                val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val oisSupported = oisModes?.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON } == true
                val flashSupported = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                val hwLevel = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }

                val physicalSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val sensorStr = if (physicalSize != null) {
                    val w = (physicalSize.width * 100f).roundToInt() / 100f
                    val h = (physicalSize.height * 100f).roundToInt() / 100f
                    "${w}x${h}mm"
                } else {
                    "N/A"
                }

                val streamMap = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
                val maxSize = sizes?.maxByOrNull { it.width * it.height }
                val maxResStr = if (maxSize != null) {
                    val mp = ((maxSize.width.toLong() * maxSize.height.toLong()) / 1_000_000.0 * 10.0).roundToInt() / 10.0
                    "${maxSize.width}x${maxSize.height} (${mp}MP)"
                } else {
                    "N/A"
                }

                val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        ch.physicalCameraIds.toList()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val primaryFocal = focalLengths.firstOrNull() ?: 0f
                val role = inferLensRole(facing, primaryFocal, physicalIds.size, maxSize?.width ?: 0)
                var minZoom = 1f
                var maxZoom = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val range = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        if (range != null) {
                            minZoom = range.lower
                            maxZoom = range.upper
                        }
                    } catch (_: Throwable) {
                    }
                } else {
                    maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                }

                result.add(
                    CameraLensInfo(
                        cameraId = id,
                        facingLabel = facing,
                        roleLabel = role,
                        physicalIds = physicalIds,
                        focalLengthsMm = focalLengths,
                        apertures = apertures,
                        oisSupported = oisSupported,
                        flashSupported = flashSupported,
                        maxResolution = maxResStr,
                        sensorSizeMm = sensorStr,
                        hardwareLevel = hwLevel,
                        minZoomRatio = minZoom,
                        maxZoomRatio = maxZoom
                    )
                )
            } catch (_: Throwable) {
                // Restricted auxiliary OEM camera IDs are skipped without failing enumeration.
            }
        }
        return result
    }

    private fun inferLensRole(facing: String, focalMm: Float, physicalCount: Int, maxW: Int): String {
        if (facing == "FRONT") return "Front Selfie"
        if (facing == "EXTERNAL") return "External USB/Aux"
        if (physicalCount > 1) return "Multi-Lens Logical"
        return when {
            focalMm in 0.1f..2.6f -> "Ultrawide / Macro"
            focalMm >= 6.8f -> "Telephoto"
            maxW in 1..2000 && focalMm > 0f -> "Aux / Depth / Macro"
            else -> "Main Wide"
        }
    }

    fun setTorch(context: Context, cameraId: String, enabled: Boolean): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return try {
            cm.setTorchMode(cameraId, enabled)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
