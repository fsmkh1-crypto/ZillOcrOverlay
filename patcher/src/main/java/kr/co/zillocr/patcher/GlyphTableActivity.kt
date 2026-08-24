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
import kr.co.zillocr.patcher.patch.BootGlyphTableProbe
import kr.co.zillocr.patcher.patch.GlyphRecordTreeProbe
import kr.co.zillocr.patcher.patch.Iso9660Reader
import kr.co.zillocr.patcher.patch.MipsSjisTrace
import kr.co.zillocr.patcher.patch.UpstreamMetrics
import java.io.FileInputStream
import java.util.concurrent.Executors

/** PoC 2.4: identify the recovered 32-byte keyed glyph records and their geometry fields. */
class GlyphTableActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private var latestReport = ""

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        statusView.text = "32-byte keyed glyph record 및 atlas geometry 필드 탐색 중…"
        executor.execute {
            val report = try {
                val metrics = UpstreamMetrics.downloadEntries()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val iso = Iso9660Reader(channel)
                        val boot = iso.readEntry(iso.find("PSP_GAME/SYSDIR/BOOT.BIN"))
                        val eboot = iso.readEntry(iso.find("PSP_GAME/SYSDIR/EBOOT.BIN"))
                        BootGlyphTableProbe.analyze(boot, eboot, metrics).report() +
                            "\n\n" + MipsSjisTrace.report(boot) +
                            "\n\n" + GlyphRecordTreeProbe.report(boot, eboot)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "renderer/glyph trace 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
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
            text = "PoC 2.4 · 32-byte keyed glyph record 탐색\n2.3에서 문자 key가 32바이트 노드의 +0에 있고 +0x10/+0x14의 자식 인덱스를 따라 검색되는 구조가 확인됐습니다. 이번 판은 0/A/a/ア/イ/テ/ム 및 surrogate 후보를 기준으로 실제 record 후보를 찾고 +2/+3/+4/+6/+8/+A/+C/+E/+18 필드를 뽑아 atlas page/x/y 또는 ordinal과 연결합니다."
            textSize = 14f; setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · glyph record tree scan"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) Toast.makeText(this@GlyphTableActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("zill glyph record tree probe v1", latestReport))
                    Toast.makeText(this@GlyphTableActivity, "분석 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusView = TextView(this).apply { text = "아직 분석하지 않았습니다."; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(16), 0, 0) }
        root.addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }
}
