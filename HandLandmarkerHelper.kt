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

    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>

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
        val modelBytes = context.assets.open("sign_model.tflite").readBytes()

        val buffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())

        buffer.put(modelBytes)
        buffer.rewind()
        Log.d("TFLite", "Loading sign_model.tflite")
        interpreter = Interpreter(buffer)
        Log.d("TFLite", "Interpreter initialized successfully")


        labels = context.assets.open("labels.txt")
            .bufferedReader()
            .readLines()
    }


    /* ================= Frame Processing ================= */

    fun processFrame(bitmap: Bitmap): String? {
        Log.d("SignRoute", "processFrame() called")
        val landmarker = handLandmarker ?: return null

        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)

        if (result.landmarks().isEmpty()) return null
        Log.d("SignRoute", "Hands detected: ${result.landmarks().size}")
        val inputBuffer = build126FloatInput(result.landmarks())
        val probabilities = runModel(inputBuffer)

        val maxIdx = probabilities.indices.maxBy { probabilities[it] }
        val confidence = probabilities[maxIdx]

        Log.d("DEBUG","Predicted idx=$maxIdx conf=$confidence")
        return labels[maxIdx]
    }

    /* ================= Landmark → Model ================= */

    /**
     * Builds a 126-float input:
     * [left_hand(63) + right_hand(63)]
     * Pads missing hand with zeros.
     */
    private fun build126FloatInput(
        hands: List<List<NormalizedLandmark>>
    ): ByteBuffer {

        val buffer = ByteBuffer.allocateDirect(126 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val firstHand = hands.getOrNull(0)
        val secondHand = hands.getOrNull(1)

        writeHandOrZeros(buffer, firstHand)
        writeHandOrZeros(buffer, secondHand)

        buffer.rewind()
        Log.d("SignRoute", "126-float buffer ready")
        return buffer
    }

    private fun writeHandOrZeros(
        buffer: ByteBuffer,
        hand: List<NormalizedLandmark>?
    ) {
        if (hand == null) {
            repeat(63) { buffer.putFloat(0f) }
            return
        }

        for (lm in hand) {
            buffer.putFloat(lm.x())
            buffer.putFloat(lm.y())
            buffer.putFloat(lm.z())
        }
    }

    private fun runModel(input: ByteBuffer): FloatArray {
        Log.d("TFLite", "Running inference")

        val inputArray = Array(1) { Array(1) { FloatArray(126) } }

        input.rewind()
        for (i in 0 until 126) {
            inputArray[0][0][i] = input.float
        }

        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(inputArray, output)
        Log.d("DEBUG", "Output: ${output[0].take(5)}")
        Log.d("TFLite", "Inference done")
        return output[0]
    }


    /* ================= Voting Logic ================= */

    private fun applyVoting(prediction: Int): String? {
        predictionWindow.addLast(prediction)
        if (predictionWindow.size > windowSize) {
            predictionWindow.removeFirst()
        }

        val counts = IntArray(labels.size)
        for (p in predictionWindow) counts[p]++

        var bestIdx = 0
        var bestCount = 0
        for (i in counts.indices) {
            if (counts[i] > bestCount) {
                bestCount = counts[i]
                bestIdx = i
            }
        }

        return if (bestCount >= windowSize / 2) {
            labels[bestIdx]
        } else null
    }

    /* ================= Cleanup ================= */

    fun close() {
        handLandmarker?.close()
        interpreter.close()
    }
}
