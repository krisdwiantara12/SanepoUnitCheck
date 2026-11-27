package com.sanepo.unitcheck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.sanepo.unitcheck.databinding.ActivityImeiScannerBinding
import com.sanepo.unitcheck.engine.OemDeviceHelper
import com.sanepo.unitcheck.util.Lang
import java.util.concurrent.atomic.AtomicBoolean

class ImeiScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImeiScannerBinding
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var hasFlash = false
    private var torchOn = false

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private val decodedDone = AtomicBoolean(false)
    private var frameCounter = 0

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.ITF,
                    BarcodeFormat.QR_CODE
                ),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && binding.scannerPreview.isAvailable) {
            openBackCamera()
        } else if (!granted) {
            Toast.makeText(
                this,
                Lang.t(this, "Izin Kamera diperlukan untuk scan barcode IMEI.", "Camera permission needed to scan IMEI barcode."),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImeiScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootImeiScanner) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        binding.btnBackScanner.text = Lang.t(this, "BATAL", "CANCEL")
        binding.tvScannerTitle.text = Lang.t(this, "Scan Barcode IMEI Dus / Layar", "Scan IMEI Box / Screen Barcode")
        binding.btnScannerTorch.text = "FLASH OFF"
        binding.tvScannerStatus.text = Lang.t(
            this,
            "Arahkan kamera ke stiker barcode IMEI di dus atau popup *#06# HP lain.",
            "Point camera at retail box IMEI barcode or *#06# barcode."
        )

        resolveBackCamera()
        binding.btnBackScanner.setOnClickListener { finish() }
        binding.btnScannerTorch.setOnClickListener { toggleTorch() }

        binding.scannerPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun resolveBackCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                val ch = cameraManager.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    break
                }
            }
            if (cameraId == null) {
                cameraId = cameraManager.cameraIdList.firstOrNull()
            }
        } catch (_: Throwable) {
        }
    }

    private fun startIfReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        openBackCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openBackCamera() {
        val id = cameraId ?: return
        closeCamera()
        cameraThread = HandlerThread("SanepoBarcodeThread").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startScanSession(camera, id)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                }
            }, cameraHandler)
        } catch (_: Throwable) {
        }
    }

    private fun startScanSession(camera: CameraDevice, id: String) {
        val st = binding.scannerPreview.surfaceTexture ?: return
        try {
            val ch = cameraManager.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val prevSizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val prevSize = prevSizes?.firstOrNull { it.width <= 1280 && it.height <= 960 } ?: prevSizes?.firstOrNull()
            if (prevSize != null) {
                st.setDefaultBufferSize(prevSize.width, prevSize.height)
                runOnUiThread { configureTransform(prevSize.width, prevSize.height) }
            }

            val yuvSize = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.firstOrNull { it.width in 640..1280 }
                ?: prevSize
            val rw = yuvSize?.width ?: 640
            val rh = yuvSize?.height ?: 480

            imageReader = ImageReader.newInstance(rw, rh, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ r ->
                    val img = try {
                        r.acquireLatestImage()
                    } catch (_: Throwable) {
                        null
                    } ?: return@setOnImageAvailableListener
                    try {
                        frameCounter++
                        if (!decodedDone.get() && frameCounter % 3 == 0) {
                            decodeYPlane(img.planes[0].buffer, img.width, img.height, img.planes[0].rowStride)
                        }
                    } finally {
                        img.close()
                    }
                }, cameraHandler)
            }

            val prevSurface = Surface(st)
            val yuvSurface = imageReader!!.surface
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(prevSurface)
                addTarget(yuvSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            previewBuilder = builder

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(prevSurface, yuvSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                    } catch (_: Throwable) {
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, cameraHandler)
        } catch (_: Throwable) {
        }
    }

    private fun decodeYPlane(buffer: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int) {
        try {
            val yData = ByteArray(w * h)
            if (rowStride == w) {
                buffer.get(yData)
            } else {
                for (row in 0 until h) {
                    buffer.position(row * rowStride)
                    buffer.get(yData, row * w, w)
                }
            }
            val source = PlanarYUVLuminanceSource(yData, w, h, 0, 0, w, h, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            val rawText = result.text?.trim().orEmpty()
            if (rawText.isNotEmpty() && decodedDone.compareAndSet(false, true)) {
                val digits = rawText.filter { it.isDigit() }
                val luhnValid = OemDeviceHelper.isValidImeiLuhn(digits)
                runOnUiThread {
                    val outIntent = Intent().apply {
                        putExtra(EXTRA_RAW_CODE, rawText)
                        putExtra(EXTRA_DIGITS, digits)
                        putExtra(EXTRA_LUHN_VALID, luhnValid)
                    }
                    setResult(Activity.RESULT_OK, outIntent)
                    finish()
                }
            }
        } catch (_: Throwable) {
            reader.reset()
        }
    }

    private fun configureTransform(bufW: Int, bufH: Int) {
        val vw = binding.scannerPreview.width.toFloat()
        val vh = binding.scannerPreview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = maxOf(vw / bufH.toFloat(), vh / bufW.toFloat())
        val m = Matrix()
        m.setScale((bufH * scale) / vw, (bufW * scale) / vh, vw / 2f, vh / 2f)
        binding.scannerPreview.setTransform(m)
    }

    private fun toggleTorch() {
        if (!hasFlash) return
        val builder = previewBuilder ?: return
        val session = captureSession ?: return
        torchOn = !torchOn
        binding.btnScannerTorch.text = if (torchOn) "FLASH ON" else "FLASH OFF"
        try {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Throwable) {
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
        } catch (_: Throwable) {
        }
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        torchOn = false
        closeCamera()
        try {
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        } catch (_: Throwable) {
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

    companion object {
        const val EXTRA_RAW_CODE = "sanepo_scan_raw"
        const val EXTRA_DIGITS = "sanepo_scan_digits"
        const val EXTRA_LUHN_VALID = "sanepo_scan_luhn"
    }
}
