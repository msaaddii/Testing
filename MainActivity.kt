package com.example.signroute

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.FrameLayout
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

    /* ================= Overlay ================= */
    private lateinit var overlayView: HandOverlayView

    /* ================= Sign ML ================= */
    private lateinit var handHelper: HandLandmarkerHelper
    private var latestPrediction: String? = null

    /* ================= Vosk ================= */
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var voskChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 🔥 OVERLAY ATTACH
        overlayView = HandOverlayView(this)
        val root = findViewById<FrameLayout>(
            window.decorView.findViewById(android.R.id.content).id
        )
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 🔥 HAND ML INIT
        handHelper = HandLandmarkerHelper(this, overlayView)
        handHelper.setup()

        // 🔥 SIGN CHANNEL
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            SIGN_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "processFrame" -> {
                    val bytes = call.arguments as? ByteArray
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
                    val pred = handHelper.processFrame(bmp)
                    if (!pred.isNullOrEmpty()) latestPrediction = pred
                    result.success(pred ?: "")
                }
                "getPrediction" -> result.success(latestPrediction ?: "")
                else -> result.notImplemented()
            }
        }

        // 🔥 VOSK
        voskChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            VOSK_CHANNEL
        )
        setupVoskChannel()
        checkAudioPermission()
        loadVoskModel()
    }

    /* ================= Vosk ================= */

    private fun setupVoskChannel() {
        voskChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startListening" -> {
                    startListening(); result.success(null)
                }
                "stopListening" -> {
                    stopListening(); result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

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

    private fun loadVoskModel() {
        Thread {
            val modelDir = File(filesDir, "model")
            if (!modelDir.exists()) copyAssets("model", modelDir)
            model = Model(modelDir.absolutePath)
        }.start()
    }

    private fun copyAssets(assetDir: String, destDir: File) {
        destDir.mkdirs()
        assets.list(assetDir)?.forEach {
            val src = "$assetDir/$it"
            val out = File(destDir, it)
            if (assets.list(src)?.isNotEmpty() == true) {
                copyAssets(src, out)
            } else {
                assets.open(src).use { i ->
                    FileOutputStream(out).use { o -> i.copyTo(o) }
                }
            }
        }
    }

    private fun startListening() {
        if (model == null) return
        recognizer = Recognizer(model, 16000f)
        speechService = SpeechService(recognizer, 16000f)
        speechService?.startListening(this)
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    override fun onPartialResult(hypothesis: String?) {
        voskChannel?.invokeMethod("onPartialResult", hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        voskChannel?.invokeMethod("onFinalResult", hypothesis)
    }

    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    override fun onDestroy() {
        super.onDestroy()
        handHelper.close()
        stopListening()
    }
}
