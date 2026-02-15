package com.example.signroute

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
    private var latestPrediction: String? = null

    /* ================= Flutter ================= */

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        /* -------- Hand Sign ML init -------- */
        handHelper = HandLandmarkerHelper(this)
        try {
            handHelper.setup()
            Log.d("SignRoute", "HandLandmarkerHelper initialized")
        } catch (e: Exception) {
            Log.e("SignRoute", "HandLandmarker setup failed", e)
        }

        /* -------- Vosk channel -------- */
        voskChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            VOSK_CHANNEL
        )
        setupVoskChannel()

        /* -------- Sign prediction channel -------- */
        signChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            SIGN_CHANNEL
        )

        signChannel.setMethodCallHandler { call, result ->
            when (call.method) {

                "processFrame" -> {
                    try {
                        val bytes = call.arguments as? ByteArray
                        if (bytes == null) {
                            result.error("NO_BYTES", "Frame bytes are null", null)
                            return@setMethodCallHandler
                        }

                        val bitmap =
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        if (bitmap == null) {
                            result.error("BITMAP_FAIL", "Bitmap decode failed", null)
                            return@setMethodCallHandler
                        }

                        val prediction = handHelper.processFrame(bitmap)

                        if (!prediction.isNullOrEmpty()) {
                            latestPrediction = prediction
                            Log.d("SignRoute", "NEW PREDICTION: $prediction")
                        }

                        result.success(prediction ?: "")

                    } catch (e: Exception) {
                        Log.e("SignRoute", "processFrame error", e)
                        result.error("PROCESS_ERR", e.message, null)
                    }
                }

                "getPrediction" -> {
                    result.success(latestPrediction ?: "")
                }

                else -> result.notImplemented()
            }
        }

        /* -------- Permissions + Vosk -------- */
        checkAudioPermission()
        loadVoskModel()
    }

    /* ================= Vosk Channel ================= */

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
                val modelDir = File(filesDir, "model")
                if (!modelDir.exists()) {
                    copyAssets("model", modelDir)
                }
                model = Model(modelDir.absolutePath)
                Log.d("VOSK", "Model loaded successfully")
            } catch (e: Exception) {
                Log.e("VOSK", "Model load failed", e)
            }
        }.start()
    }

    private fun copyAssets(assetDir: String, destDir: File) {
        destDir.mkdirs()
        val assetsList = assets.list(assetDir) ?: return

        for (asset in assetsList) {
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
        try {
            handHelper.close()
        } catch (e: Exception) {
            Log.e("SignRoute", "Error closing HandLandmarkerHelper", e)
        }
        stopListening()
    }
}
