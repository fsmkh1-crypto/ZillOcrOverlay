package kr.co.zillocr.patcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kr.co.zillocr.patcher.patch.GlyphArchiveOriginTrace
import kr.co.zillocr.patcher.patch.Iso9660Reader
import java.io.FileInputStream
import java.util.concurrent.Executors

/** PoC 3.2: trace the source writer for the glyph metadata container. */
class GlyphTableActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private var latestReport = ""

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        statusView.text = "glyph metadata 컨테이너의 원본 writer를 추적 중…"
        executor.execute {
            val report = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val iso = Iso9660Reader(channel)
                        val boot = iso.readEntry(iso.find("PSP_GAME/SYSDIR/BOOT.BIN"))
                        GlyphArchiveOriginTrace.report(boot)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "glyph container-writer trace 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread { statusView.text = report }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()) }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun buildView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        root.addView(TextView(this).apply { text = "질올 한글패치"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "PoC 3.2 · glyph metadata 컨테이너 원본 추적\n3.1에서 index=2가 base+offsetTable[2]인 세 번째 sub-block임이 확인됐습니다. 이번 판은 컨테이너 헤더 파서와 owner+0x380/+0x384 writer를 추적해 실제 ISO 리소스 출처를 찾습니다."
            textSize = 14f; setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · container-writer trace"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) Toast.makeText(this@GlyphTableActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("zill glyph container writer trace v3", latestReport))
                    Toast.makeText(this@GlyphTableActivity, "분석 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusView = TextView(this).apply { text = "아직 분석하지 않았습니다."; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(16), 0, 0) }
        root.addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }
}
