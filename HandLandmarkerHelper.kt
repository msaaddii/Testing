package com.example.signroute

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class HandLandmarkerHelper(
    private val context: Context,
    private val overlayView: HandOverlayView
) {

    /* ================= MediaPipe ================= */

    private var handLandmarker: HandLandmarker? = null

    /* ================= TFLite ================= */

    private var interpreter: Interpreter? = null
    private var labels: List<String> = listOf()

    /* ================= Voting ================= */

    private val windowSize = 7
    private val confidenceThreshold = 0.70f
    private val predictionWindow = ArrayDeque<Int>()

    /* ================= Init ================= */

    fun setup() {
        setupMediaPipe()
        setupTFLite()
        Log.d("SignRoute", "MediaPipe + TFLite initialized")
    }

    private fun setupMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(2)
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    private fun setupTFLite() {
        val modelBytes = context.assets.open("sign_model.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
        buffer.put(modelBytes)
        buffer.rewind()

        interpreter = Interpreter(buffer)

        labels = context.assets.open("labels.txt")
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /* ================= Frame Processing ================= */

    fun processFrame(bitmap: Bitmap): String? {
        val landmarker = handLandmarker ?: return null

        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)

        val hands = result.landmarks()
        if (hands.isEmpty()) {
            overlayView.post { overlayView.setLandmarks(emptyList()) }
            return null
        }

        // 🔥 DRAW LANDMARKS
        overlayView.post {
            overlayView.setLandmarks(hands)
        }

        val inputBuffer = build126FloatInput(hands)
        val probs = runModel(inputBuffer)
        if (probs.isEmpty()) return null

        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: return null
        val confidence = probs[maxIdx]

        if (confidence < confidenceThreshold) return null

        val voted = applyVoting(maxIdx) ?: return null
        return voted
    }

    /* ================= Landmark → Model ================= */

    private fun build126FloatInput(
        hands: List<List<NormalizedLandmark>>
    ): ByteBuffer {

        val buffer = ByteBuffer.allocateDirect(126 * 4)
        buffer.order(ByteOrder.nativeOrder())

        writeHand(buffer, hands.getOrNull(0))
        writeHand(buffer, hands.getOrNull(1))

        buffer.rewind()
        return buffer
    }

    private fun writeHand(
        buffer: ByteBuffer,
        hand: List<NormalizedLandmark>?
    ) {
        if (hand == null) {
            repeat(63) { buffer.putFloat(0f) }
            return
        }

        for (i in 0 until 21) {
            if (i < hand.size) {
                val lm = hand[i]
                buffer.putFloat(lm.x())
                buffer.putFloat(lm.y())
                buffer.putFloat(lm.z())
            } else {
                buffer.putFloat(0f)
                buffer.putFloat(0f)
                buffer.putFloat(0f)
            }
        }
    }

    private fun runModel(input: ByteBuffer): FloatArray {
        val inputArray = Array(1) { Array(1) { FloatArray(126) } }

        input.rewind()
        for (i in 0 until 126) {
            inputArray[0][0][i] = input.float
        }

        val output = Array(1) { FloatArray(labels.size) }
        interpreter?.run(inputArray, output)
        return output[0]
    }

    /* ================= Voting ================= */

    private fun applyVoting(idx: Int): String? {
        predictionWindow.addLast(idx)
        if (predictionWindow.size > windowSize) {
            predictionWindow.removeFirst()
        }

        val counts = IntArray(labels.size)
        for (p in predictionWindow) counts[p]++

        val best = counts.indices.maxByOrNull { counts[it] } ?: return null
        return if (counts[best] >= windowSize / 2) labels[best] else null
    }

    fun close() {
        handLandmarker?.close()
        interpreter?.close()
    }
}
