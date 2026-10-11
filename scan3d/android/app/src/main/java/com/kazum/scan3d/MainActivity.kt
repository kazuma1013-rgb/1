package com.kazum.scan3d

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrl: EditText
    private lateinit var message: TextView
    private lateinit var sessionList: LinearLayout
    private var pollJob: Job? = null

    private val prefs by lazy { getSharedPreferences("scan3d", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serverUrl = findViewById(R.id.serverUrl)
        message = findViewById(R.id.message)
        sessionList = findViewById(R.id.sessionList)

        serverUrl.setText(prefs.getString("server", ""))
        findViewById<Button>(R.id.newScan).setOnClickListener {
            openCapture(ScanStore.newSessionName())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString("server", serverUrl.text.toString().trim()).apply()
    }

    private fun openCapture(session: String) {
        startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_SESSION, session))
    }

    private fun client(): ServerClient? {
        val url = serverUrl.text.toString().trim()
        if (!url.startsWith("http")) {
            message.text = "PCサーバーのURLを入力してください（例: http://192.168.1.10:8795）"
            return null
        }
        prefs.edit().putString("server", url).apply()
        return ServerClient(url)
    }

    private fun refreshList() {
        sessionList.removeAllViews()
        val sessions = ScanStore.sessions(this)
        if (sessions.isEmpty()) {
            sessionList.addView(TextView(this).apply { text = "まだスキャンはありません" })
            return
        }
        for (dir in sessions) sessionList.addView(sessionRow(dir))
    }

    private fun sessionRow(dir: File): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)
        }
        row.addView(TextView(this).apply {
            text = "${dir.name}（${ScanStore.imageCount(dir)}枚）"
            textSize = 16f
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttons.addView(button("追加撮影") { openCapture(dir.name) })
        buttons.addView(button("PCで3D化") { chooseQuality(dir) })
        buttons.addView(button("見る") { view(dir.name) })
        buttons.addView(button("削除") { confirmDelete(dir) })
        row.addView(buttons)
        return row
    }

    private fun chooseQuality(dir: File) {
        val labels = arrayOf("標準（早い）", "高品質（時間がかかる）")
        val values = arrayOf("normal", "high")
        AlertDialog.Builder(this)
            .setTitle("3D化の品質")
            .setItems(labels) { _, which -> uploadAndProcess(dir, values[which]) }
            .show()
    }

    private fun uploadAndProcess(dir: File, quality: String) {
        val c = client() ?: return
        val session = dir.name
        val files = ScanStore.uploadFiles(dir)
        if (files.count { it.name.endsWith(".jpg") } < 20) {
            message.text = "写真が少なすぎます（20枚以上、できれば80枚以上）"
            return
        }
        lifecycleScope.launch {
            try {
                c.upload(session, files) { done, total -> message.text = "PCへ送信中… $done / $total" }
                c.startProcess(session, quality)
                pollStatus(c, session)
            } catch (e: Exception) {
                message.text = "送信失敗: ${e.message}\nPCでサーバーが動いているか、同じWi-Fiか確認してください"
            }
        }
    }

    private fun pollStatus(c: ServerClient, session: String) {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                val s = try { c.status(session) } catch (e: Exception) {
                    message.text = "状態の取得に失敗: ${e.message}"
                    return@launch
                }
                message.text = when (s.state) {
                    "done" -> "完成しました！「見る」で確認できます（$session）"
                    "error" -> "3D化に失敗: ${s.message}"
                    else -> "PCで3D化中（$session）: ${s.step} ${(s.progress * 100).toInt()}%\n${s.message}"
                }
                if (s.state == "done" || s.state == "error") return@launch
                delay(3000)
            }
        }
    }

    private fun view(session: String) {
        val c = client() ?: return
        // 状態も表示しておく（処理中ならポーリング再開）
        pollStatus(c, session)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.viewerUrl(session))))
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this)
            .setMessage("${dir.name} を端末から削除しますか？（PC側のデータは残ります）")
            .setPositiveButton("削除") { _, _ -> dir.deleteRecursively(); refreshList() }
            .setNegativeButton("やめる", null)
            .show()
    }
}
