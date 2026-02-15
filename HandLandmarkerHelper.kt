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

class HandLandmarkerHelper(private val context: Context) {

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
        try {
            setupMediaPipe()
        } catch (e: Exception) {
            Log.e("SignRoute", "MediaPipe setup failed", e)
        }

        try {
            setupTFLite()
        } catch (e: Exception) {
            Log.e("SignRoute", "TFLite setup failed", e)
        }

        Log.d("SignRoute", "MediaPipe + TFLite initialized")
    }

    private fun setupMediaPipe() {
        Log.d("HandLandmarker", "Initializing MediaPipe hand landmarker")

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(2)
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)

        Log.d("HandLandmarker", "MediaPipe hand landmarker initialized")
    }


    private fun setupTFLite() {
        Log.d("TFLite", "Loading sign_model.tflite from assets")
        val modelBytes = context.assets.open("sign_model.tflite").readBytes()

        val buffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())

        buffer.put(modelBytes)
        buffer.rewind()

        interpreter = Interpreter(buffer)
        Log.d("TFLite", "Interpreter initialized successfully")

        labels = context.assets.open("labels.txt")
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        Log.d("TFLite", "Labels loaded, count = ${labels.size}")
    }


    /* ================= Frame Processing ================= */

    /**
     * Called with a Bitmap frame. Returns predicted label (voted & thresholded) or null.
     */
    fun processFrame(bitmap: Bitmap): String? {
        Log.d("SignRoute", "processFrame() called")
        val landmarker = handLandmarker
        if (landmarker == null) {
            Log.w("SignRoute", "HandLandmarker not initialized")
            return null
        }

        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val result = try {
            landmarker.detect(mpImage)
        } catch (e: Exception) {
            Log.e("SignRoute", "MediaPipe detect failed", e)
            return null
        }

        val hands = result.landmarks()
        if (hands.isEmpty()) {
            Log.d("SignRoute", "No hands detected")
            return null
        }

        Log.d("SignRoute", "Hands detected: ${hands.size}")

        val inputBuffer = build126FloatInput(hands)
        val probabilities = try {
            runModel(inputBuffer)
        } catch (e: Exception) {
            Log.e("SignRoute", "Model run failed", e)
            return null
        }

        if (probabilities.isEmpty() || labels.isEmpty()) {
            Log.w("SignRoute", "Empty probabilities or labels")
            return null
        }

        val maxIdx = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1
        if (maxIdx == -1) {
            Log.w("SignRoute", "Could not find max index")
            return null
        }
        val confidence = probabilities[maxIdx]
        Log.d("SignRoute", "Raw prediction idx=$maxIdx conf=$confidence")

        // Apply voting across recent predictions
        val votedLabel = try {
            val voted = applyVoting(maxIdx)
            if (voted == null) {
                Log.d("SignRoute", "Voting did not reach consensus yet")
                return null
            }
            voted
        } catch (e: Exception) {
            Log.e("SignRoute", "Voting failed", e)
            null
        }

        // Only accept if confidence meets threshold
        if (confidence < confidenceThreshold) {
            Log.d("SignRoute", "Confidence $confidence below threshold $confidenceThreshold")
            return null
        }

        Log.d("SignRoute", "Final predicted label (voted): $votedLabel")
        return votedLabel
    }

    /* ================= Landmark → Model ================= */

    /**
     * Builds a 126-float input:
     * [left_hand(63) + right_hand(63)]
     * Pads missing hand with zeros. Each hand expected 21 landmarks (x,y,z) => 63 floats.
     */
    private fun build126FloatInput(
        hands: List<List<NormalizedLandmark>>
    ): ByteBuffer {

        val floatsPerHand = 63
        val totalFloats = floatsPerHand * 2
        val buffer = ByteBuffer.allocateDirect(totalFloats * 4)
        buffer.order(ByteOrder.nativeOrder())

        val firstHand = hands.getOrNull(0)
        val secondHand = hands.getOrNull(1)

        writeHandOrZeros(buffer, firstHand)
        writeHandOrZeros(buffer, secondHand)

        buffer.rewind()
        Log.d("SignRoute", "126-float buffer ready (size bytes=${buffer.capacity()})")
        return buffer
    }

    /**
     * Writes exactly 63 floats for a hand (21 landmarks x 3 coords).
     * If hand has fewer landmarks, pads remaining floats with zeros.
     */
    private fun writeHandOrZeros(
        buffer: ByteBuffer,
        hand: List<NormalizedLandmark>?
    ) {
        val expectedLandmarks = 21
        if (hand == null) {
            repeat(expectedLandmarks * 3) { buffer.putFloat(0f) }
            return
        }

        // If the hand has fewer or more landmarks, handle safely.
        val count = kotlin.math.min(hand.size, expectedLandmarks)
        for (i in 0 until count) {
            val lm = hand[i]
            buffer.putFloat(lm.x())
            buffer.putFloat(lm.y())
            buffer.putFloat(lm.z())
        }
        // pad remaining
        val written = count * 3
        val toPad = (expectedLandmarks * 3) - written
        repeat(toPad) { buffer.putFloat(0f) }
    }

    /**
     * Runs the model. The interpreter was created from a ByteBuffer model.
     * This fills a [1][labels.size] float output.
     */
    private fun runModel(input: ByteBuffer): FloatArray {
        Log.d("TFLite", "Running inference")

        val lblCount = labels.size
        if (lblCount == 0) {
            Log.w("TFLite", "No labels available")
            return FloatArray(0)
        }

        // Build input as Array shape: [1, 1, 126] if model expects that or [1,126].
        // We'll try [1][126] first; many small TFLite classifiers use [1,126].
        // To be robust, the previous code used Array(1){Array(1){FloatArray(126)}}, we'll keep that to match existing model contract.
        val inputArray = Array(1) { Array(1) { FloatArray(126) } }

        input.rewind()
        for (i in 0 until 126) {
            // safe read: if buffer runs out, fill 0
            val f = try {
                input.float
            } catch (e: Exception) {
                0f
            }
            inputArray[0][0][i] = f
        }

        val output = Array(1) { FloatArray(lblCount) }
        interpreter?.run(inputArray, output)
            ?: throw IllegalStateException("Interpreter not initialized")

        // Log a few probabilities for debugging
        val preview = output[0].take(8).joinToString(", ") { String.format("%.3f", it) }
        Log.d("TFLite", "Output preview (first 8): [$preview]")

        Log.d("TFLite", "Inference done (labels=${lblCount})")
        return output[0]
    }


    /* ================= Voting Logic ================= */

    private fun applyVoting(prediction: Int): String? {
        // guard prediction bounds
        if (labels.isEmpty()) return null
        val idx = if (prediction in labels.indices) prediction else return null

        predictionWindow.addLast(idx)
        if (predictionWindow.size > windowSize) {
            predictionWindow.removeFirst()
        }

        val counts = IntArray(labels.size)
        for (p in predictionWindow) {
            if (p in counts.indices) counts[p]++
        }

        var bestIdx = 0
        var bestCount = 0
        for (i in counts.indices) {
            if (counts[i] > bestCount) {
                bestCount = counts[i]
                bestIdx = i
            }
        }

        // require at least half the window to agree
        return if (bestCount >= (windowSize / 2)) {
            labels[bestIdx]
        } else null
    }

    /* ================= Cleanup ================= */

    fun close() {
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.e("SignRoute", "Error closing MediaPipe handLandmarker", e)
        }

        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            Log.e("SignRoute", "Error closing interpreter", e)
        }
    }
}
