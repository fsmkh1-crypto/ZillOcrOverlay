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
import kr.co.zillocr.patcher.patch.GlyphRuntimeBaseTrace
import kr.co.zillocr.patcher.patch.Iso9660Reader
import kr.co.zillocr.patcher.patch.MipsSjisTrace
import kr.co.zillocr.patcher.patch.UpstreamMetrics
import java.io.FileInputStream
import java.util.concurrent.Executors

/** PoC 2.5: trace the runtime glyph-node base pointer back to its constructor/loader. */
class GlyphTableActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private var latestReport = ""

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        statusView.text = "runtime glyph-node base 및 constructor/loader 역추적 중…"
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
                            "\n\n" + GlyphRuntimeBaseTrace.report(boot)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "runtime glyph-node trace 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
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
            text = "PoC 2.5 · runtime glyph-node base 역추적\n2.4의 BOOT 전체 32바이트 record 점수화는 ASCII/문자열 데이터를 잘못 잡는 방식임이 확인됐습니다. 이번 판은 실제 lookup 계약만 따라갑니다: nodeBase=object+0x14, root/count 구조=object+0x10, node stride=0x20. 이 포인터를 생성·저장하는 constructor/loader 경로를 찾아 실제 glyph record 원본 위치로 연결합니다."
            textSize = 14f; setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · runtime glyph base trace"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) Toast.makeText(this@GlyphTableActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("zill runtime glyph base trace v1", latestReport))
                    Toast.makeText(this@GlyphTableActivity, "분석 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusView = TextView(this).apply { text = "아직 분석하지 않았습니다."; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(16), 0, 0) }
        root.addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }
}
