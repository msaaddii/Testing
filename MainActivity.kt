package com.example.signroute

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream

private const val VOSK_CHANNEL = "vosk_channel"
private const val SIGN_CHANNEL = "signroute/prediction"

class MainActivity : FlutterActivity(), RecognitionListener {

    /* ================= Vosk ================= */

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var voskChannel: MethodChannel? = null

    /* ================= Sign ML ================= */

    private lateinit var signChannel: MethodChannel
    private lateinit var handHelper: HandLandmarkerHelper
    private var lastPrediction: String? = null

    // 🔒 Timing controls
    private var lastInferenceTime = 0L
    private var lastFrameLogTime = 0L

    /* ================= Camera ================= */

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var imageReader: ImageReader
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    /* ================= Flutter ================= */

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // ---- Channels ----
        voskChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            VOSK_CHANNEL
        )

        signChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            SIGN_CHANNEL
        )

        signChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getPrediction" -> result.success(lastPrediction)
                else -> result.notImplemented()
            }
        }

        // ---- ML Init ----
        handHelper = HandLandmarkerHelper(this)
        handHelper.setup()

        setupVoskChannel()
        checkAudioPermission()
        loadVoskModel()

        startCameraThread()
        openCamera()
    }

    /* ================= Channels ================= */

    private fun setupVoskChannel() {
        voskChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startListening" -> {
                    startListening()
                    result.success(null)
                }
                "stopListening" -> {
                    stopListening()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    /* ================= Camera ================= */

    private fun startCameraThread() {
        cameraThread = HandlerThread("CameraThread")
        cameraThread.start()
        cameraHandler = Handler(cameraThread.looper)
    }

    private fun openCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                2
            )
            return
        }

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {

                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d("CAMERA", "Camera opened")

                    imageReader = ImageReader.newInstance(
                        640, 480,
                        ImageFormat.YUV_420_888,
                        5
                    )

                    imageReader.setOnImageAvailableListener({ reader ->

                        val image =
                            reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                        val now = System.currentTimeMillis()

                        // ⏱️ ML throttle (10 FPS)
                        if (now - lastInferenceTime < 100) {
                            image.close()
                            return@setOnImageAvailableListener
                        }
                        lastInferenceTime = now

                        // 🧹 log spam control
                        if (now - lastFrameLogTime > 1000) {
                            Log.d("FRAME", "Frame flowing")
                            lastFrameLogTime = now
                        }

                        val bitmap = yuvToBitmap(image)
                        image.close()

                        val prediction = handHelper.processFrame(bitmap)
                        if (prediction != null) {
                            lastPrediction = prediction
                            Log.d("PREDICTION", prediction)
                        }

                    }, cameraHandler)

                    val surfaces = listOf(imageReader.surface)

                    camera.createCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session

                                val request = camera.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {
                                    addTarget(imageReader.surface)
                                }

                                captureSession.setRepeatingRequest(
                                    request.build(),
                                    null,
                                    cameraHandler
                                )
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("CAMERA", "Capture session failed")
                            }
                        },
                        cameraHandler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            },
            cameraHandler
        )
    }

    /* ================= Permissions ================= */

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        }
    }

    /* ================= Vosk ================= */

    private fun loadVoskModel() {
        Thread {
            try {
                val modelPath = File(filesDir, "model")
                if (!modelPath.exists()) {
                    copyAssets("model", modelPath)
                }
                model = Model(modelPath.absolutePath)
                Log.d("VOSK", "Model loaded")
            } catch (e: Exception) {
                Log.e("VOSK", "Model load failed", e)
            }
        }.start()
    }

    private fun copyAssets(assetDir: String, destDir: File) {
        destDir.mkdirs()
        val assetList = assets.list(assetDir) ?: return

        for (asset in assetList) {
            val assetPath = "$assetDir/$asset"
            val outFile = File(destDir, asset)
            val subAssets = assets.list(assetPath)

            if (!subAssets.isNullOrEmpty()) {
                copyAssets(assetPath, outFile)
            } else {
                assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun startListening() {
        if (model == null) return
        recognizer = Recognizer(model, 16000.0f)
        speechService = SpeechService(recognizer, 16000.0f)
        speechService?.startListening(this)
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    /* ================= Vosk Callbacks ================= */

    override fun onPartialResult(hypothesis: String?) {
        voskChannel?.invokeMethod("onPartialResult", hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        voskChannel?.invokeMethod("onFinalResult", hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        voskChannel?.invokeMethod("onFinalResult", hypothesis)
    }

    override fun onError(e: Exception?) {
        e?.printStackTrace()
    }

    override fun onTimeout() {}

    /* ================= Cleanup ================= */

    override fun onDestroy() {
        super.onDestroy()
        handHelper.close()
        stopListening()
        cameraThread.quitSafely()
    }

    /* ================= Utils ================= */

    private fun yuvToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height),
            80,
            out
        )

        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
