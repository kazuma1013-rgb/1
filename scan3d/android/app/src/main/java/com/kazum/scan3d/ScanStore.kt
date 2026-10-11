package com.kazum.scan3d

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 端末内のスキャン保存先（アプリ専用フォルダ /Android/data/com.kazum.scan3d/files/scans） */
object ScanStore {
    const val POSES_FILE = "poses.jsonl"

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "scans").apply { mkdirs() }

    fun sessionDir(context: Context, session: String) = File(root(context), session)

    fun newSessionName(): String =
        "scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun sessions(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun images(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun imageCount(dir: File) = images(dir).size

    /** PCへ送るファイル（画像と撮影向きの記録） */
    fun uploadFiles(dir: File): List<File> =
        images(dir) + listOfNotNull(File(dir, POSES_FILE).takeIf { it.exists() })

    fun appendPose(dir: File, name: String, d: CameraDirection, sharpness: Double) {
        File(dir, POSES_FILE).appendText(
            "{\"file\":\"$name\",\"heading\":%.2f,\"elevation\":%.2f,\"sharpness\":%.1f,\"time\":%d}\n"
                .format(Locale.US, d.heading, d.elevation, sharpness, System.currentTimeMillis())
        )
    }
}
