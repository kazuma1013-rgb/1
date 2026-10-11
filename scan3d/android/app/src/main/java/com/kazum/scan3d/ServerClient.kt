package com.kazum.scan3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** PCの scan_server.py とやりとりする */
class ServerClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    data class Status(val state: String, val step: String, val progress: Double, val message: String)

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 60000
        }

    private fun HttpURLConnection.readBody(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: $body")
        return body
    }

    /** サーバーに既にあるファイル名→サイズ（再送を省くため） */
    private fun remoteFiles(session: String): Map<String, Long> {
        val json = JSONObject(open("/api/scans/$session/files", "GET").readBody())
        val files = json.getJSONObject("files")
        return files.keys().asSequence().associateWith { files.getLong(it) }
    }

    suspend fun upload(session: String, files: List<File>, onProgress: (Int, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val existing = remoteFiles(session)
            files.forEachIndexed { i, f ->
                if (existing[f.name] != f.length()) {
                    val conn = open("/api/scans/$session/files/${f.name}", "PUT")
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(f.length())
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.outputStream.use { out -> f.inputStream().use { it.copyTo(out) } }
                    conn.readBody()
                }
                withContext(Dispatchers.Main) { onProgress(i + 1, files.size) }
            }
        }

    suspend fun startProcess(session: String, quality: String) = withContext(Dispatchers.IO) {
        open("/api/scans/$session/process?quality=$quality", "POST").apply {
            doOutput = true
            outputStream.close()
        }.readBody()
    }

    suspend fun status(session: String): Status = withContext(Dispatchers.IO) {
        val j = JSONObject(open("/api/scans/$session/status", "GET").readBody())
        Status(j.optString("state"), j.optString("step"), j.optDouble("progress", 0.0), j.optString("message"))
    }

    fun viewerUrl(session: String) = "$base/viewer/$session"
}
