package com.kazum.scan3d

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * プレビューの輝度からピントの鋭さ（ラプラシアン分散）を推定する。
 * 直近の最大値に対する比率で「ブレていないか」を判定する。
 */
class SharpnessAnalyzer : ImageAnalysis.Analyzer {
    @Volatile var score = 0.0
        private set
    @Volatile var recentMax = 0.0
        private set

    val isSharp: Boolean
        get() = recentMax > 0 && score >= recentMax * 0.55

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = image.width
            val h = image.height
            // 中央60%の範囲を4画素おきに見る
            val step = 4
            val x0 = (w * 0.2).toInt()
            val x1 = (w * 0.8).toInt() - step
            val y0 = (h * 0.2).toInt()
            val y1 = (h * 0.8).toInt() - step

            fun px(x: Int, y: Int): Int = buf.get(y * rowStride + x * pixStride).toInt() and 0xFF

            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var y = y0 + step
            while (y < y1) {
                var x = x0 + step
                while (x < x1) {
                    val lap = 4 * px(x, y) - px(x - step, y) - px(x + step, y) - px(x, y - step) - px(x, y + step)
                    sum += lap
                    sumSq += lap.toDouble() * lap
                    n++
                    x += step
                }
                y += step
            }
            if (n > 0) {
                val mean = sum / n
                val variance = sumSq / n - mean * mean
                score = variance
                recentMax = max(variance, recentMax * 0.985)
            }
        } finally {
            image.close()
        }
    }
}
