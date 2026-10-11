package com.kazum.scan3d

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

class CaptureActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_SESSION = "session"
        private const val MIN_INTERVAL_MS = 600L
        private const val STEADY_RAD_PER_SEC = 0.25f
        private const val STEADY_HOLD_MS = 250L
    }

    private lateinit var sessionDir: File
    private lateinit var previewView: PreviewView
    private lateinit var coverageView: CoverageView
    private lateinit var statusText: TextView
    private lateinit var autoSwitch: MaterialSwitch

    private val coverage = Coverage()
    private val sharpness = SharpnessAnalyzer()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    private lateinit var sensorManager: SensorManager
    private val rotation = FloatArray(9)
    private var direction = CameraDirection(0f, 0f)
    private var lastUnsteadyAt = 0L
    private var lastShotAt = 0L
    private var capturing = false
    private var shotCount = 0

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "カメラの許可が必要です", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        val session = intent.getStringExtra(EXTRA_SESSION) ?: run { finish(); return }
        sessionDir = ScanStore.sessionDir(this, session).apply { mkdirs() }
        shotCount = ScanStore.imageCount(sessionDir)

        previewView = findViewById(R.id.preview)
        coverageView = findViewById(R.id.coverage)
        statusText = findViewById(R.id.status)
        autoSwitch = findViewById(R.id.autoSwitch)
        findViewById<Button>(R.id.shutter).setOnClickListener { takePhoto(manual = true) }
        findViewById<Button>(R.id.finish).setOnClickListener { finish() }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // 地磁気を使わないゲーム用回転ベクトル：屋内でも方位が跳ねにくい（数分なら漂流は小さい）
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            // 4000x3000（約1200万画素）付近を狙う。2億画素は処理が重すぎて再構成に向かない
            val captureSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureSelector)
                .setJpegQuality(95)
                .build()
            val analysisSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(analysisExecutor, sharpness) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            imageCapture = capture
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                direction = cameraDirectionFrom(rotation)
                onTick(now)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                if (sqrt(x * x + y * y + z * z) > STEADY_RAD_PER_SEC) lastUnsteadyAt = now
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onTick(now: Long) {
        val ring = coverage.ringOf(direction.elevation)
        val sector = coverage.sectorOf(direction.heading)
        val steady = now - lastUnsteadyAt > STEADY_HOLD_MS
        val sharp = sharpness.isSharp

        if (autoSwitch.isChecked && steady && sharp && !coverage.isFilled(ring, sector)) {
            takePhoto(manual = false)
        }

        coverageView.update(coverage.snapshot(), ring, coverage.relativeHeading(direction.heading))
        statusText.text = buildString {
            append("撮影枚数: ").append(shotCount).append('\n')
            append("今の段: ").append(Coverage.RING_NAMES[ring]).append('\n')
            for (r in 0 until Coverage.RINGS) {
                append(listOf("低", "中", "高")[r]).append(": ").append(coverage.ringPercent(r)).append("%  ")
            }
            append('\n')
            append(
                when {
                    !steady -> "ゆっくり動かしてください"
                    !sharp -> "ピント待ち…"
                    coverage.isFilled(ring, sector) -> "この方向は撮影済み。横へ回り込んでください"
                    else -> "OK"
                }
            )
        }
    }

    private fun takePhoto(manual: Boolean) {
        val capture = imageCapture ?: return
        val now = SystemClock.elapsedRealtime()
        if (capturing || (!manual && now - lastShotAt < MIN_INTERVAL_MS)) return
        capturing = true
        lastShotAt = now

        val shotDirection = direction
        val shotSharpness = sharpness.score
        val index = shotCount + 1
        val file = File(sessionDir, "img_%04d.jpg".format(index))
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    shotCount = index
                    coverage.mark(shotDirection.heading, shotDirection.elevation)
                    ScanStore.appendPose(sessionDir, file.name, shotDirection, shotSharpness)
                }

                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(this@CaptureActivity, "撮影失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
