package com.example.signroute

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var result: HandLandmarkerResult? = null

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 10f
        style = Paint.Style.FILL
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    fun setResults(handResult: HandLandmarkerResult) {
        result = handResult
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: return

        for (hand in res.landmarks()) {
            drawHand(canvas, hand)
        }
    }

    private fun drawHand(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        for (lm in landmarks) {
            canvas.drawCircle(
                lm.x() * w,
                lm.y() * h,
                8f,
                pointPaint
            )
        }

        val connections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )

        for ((s, e) in connections) {
            val start = landmarks[s]
            val end = landmarks[e]

            canvas.drawLine(
                start.x() * w,
                start.y() * h,
                end.x() * w,
                end.y() * h,
                linePaint
            )
        }
    }
}
