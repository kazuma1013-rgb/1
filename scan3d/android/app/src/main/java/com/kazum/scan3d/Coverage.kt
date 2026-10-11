package com.kazum.scan3d

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 対象のまわりを何度ぶん撮れたかを管理する。
 * 方位（ぐるっと一周）を [SECTORS] 分割し、カメラの上下の傾きで3段（低・中・高）に分ける。
 */
class Coverage {
    companion object {
        const val SECTORS = 36
        const val RINGS = 3
        val RING_NAMES = arrayOf("低（しゃがんで水平〜見上げ）", "中（胸の高さで少し見下ろす）", "高（頭上から見下ろす）")
    }

    private val filled = Array(RINGS) { BooleanArray(SECTORS) }
    private var headingOrigin: Float? = null

    /** 起点からの相対方位 0..360（未確定なら現在値をそのまま） */
    fun relativeHeading(heading: Float): Float {
        val origin = headingOrigin ?: heading
        return ((heading - origin) % 360f + 360f) % 360f
    }

    fun sectorOf(heading: Float): Int =
        (relativeHeading(heading) / (360f / SECTORS)).toInt().coerceIn(0, SECTORS - 1)

    fun ringOf(elevationDeg: Float): Int = when {
        elevationDeg > -5f -> 0
        elevationDeg > -30f -> 1
        else -> 2
    }

    fun isFilled(ring: Int, sector: Int) = filled[ring][sector]

    fun mark(heading: Float, elevationDeg: Float) {
        if (headingOrigin == null) headingOrigin = heading
        filled[ringOf(elevationDeg)][sectorOf(heading)] = true
    }

    fun ringPercent(ring: Int): Int = (filled[ring].count { it } * 100f / SECTORS).roundToInt()

    fun snapshot(): Array<BooleanArray> = Array(RINGS) { filled[it].copyOf() }
}

/** 回転行列からカメラ（背面）の向きを求める。heading=方位(度)、elevation=仰角(度, 上が正) */
data class CameraDirection(val heading: Float, val elevation: Float)

fun cameraDirectionFrom(r: FloatArray): CameraDirection {
    // 端末座標の -Z（背面カメラの向き）を世界座標へ。世界座標は X=東, Y=北, Z=上
    val fx = -r[2]
    val fy = -r[5]
    val fz = -r[8]
    val horiz = sqrt(fx * fx + fy * fy)
    val heading = Math.toDegrees(atan2(fx, fy).toDouble()).toFloat()
    val elevation = Math.toDegrees(asin(fz.coerceIn(-1f, 1f).toDouble())).toFloat()
    return CameraDirection(if (horiz < 1e-4f) 0f else heading, elevation)
}
