package com.example.signroute

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
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

    /* ================= SignRoute ML ================= */

    private lateinit var signChannel: MethodChannel
    private lateinit var handHelper: HandLandmarkerHelper

    /* ================= Camera ================= */

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    /* ================= Flutter ================= */

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Vosk channel (speech)
        voskChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            VOSK_CHANNEL
        )

        // Sign prediction channel (sign language)
        signChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            SIGN_CHANNEL
        )

        // Initialize sign ML
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
        cameraThread!!.start()
        cameraHandler = Handler(cameraThread!!.looper)
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

            if (subAssets != null && subAssets.isNotEmpty()) {
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
        if (model == null) {
            voskChannel?.invokeMethod(
                "onPartialResult",
                """{"text":"[ERROR] Model not ready"}"""
            )
            return
        }

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
        cameraThread?.quitSafely()
    }
}
