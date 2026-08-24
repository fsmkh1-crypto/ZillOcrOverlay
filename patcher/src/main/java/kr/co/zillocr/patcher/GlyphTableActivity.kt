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
import kr.co.zillocr.patcher.patch.Iso9660Reader
import kr.co.zillocr.patcher.patch.MipsSjisTrace
import kr.co.zillocr.patcher.patch.UpstreamMetrics
import java.io.FileInputStream
import java.util.concurrent.Executors

/** PoC 1.7: trace the confirmed Shift-JIS decoder's function boundary and callers. */
class GlyphTableActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private var latestReport = ""

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "Shift-JIS decoder 함수 경계와 호출부 추적 중…"
        executor.execute {
            val report = try {
                val metrics = UpstreamMetrics.downloadEntries()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val iso = Iso9660Reader(channel)
                        val boot = iso.readEntry(iso.find("PSP_GAME/SYSDIR/BOOT.BIN"))
                        val eboot = iso.readEntry(iso.find("PSP_GAME/SYSDIR/EBOOT.BIN"))
                        val base = BootGlyphTableProbe.analyze(boot, eboot, metrics).report()
                        base + "\n\n" + MipsSjisTrace.report(boot)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "glyph mapping probe 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread { statusView.text = report }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(TextView(this).apply { text = "질올 한글패치"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "PoC 1.7 · Shift-JIS decoder 호출 경로 추적\n1.6에서 #1 후보가 실제로 lead/trail byte를 검증하고 (lead<<8)|trail을 16-bit 값으로 저장하는 decoder/validator임을 확인했습니다. 이번 판은 함수 시작점과 직접 호출부를 찾아 그 출력이 어디로 넘어가는지 추적합니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · decoder caller 추적"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) {
                    Toast.makeText(this@GlyphTableActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("zill glyph mapping probe v5", latestReport))
                    Toast.makeText(this@GlyphTableActivity, "분석 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusView = TextView(this).apply {
            text = "아직 분석하지 않았습니다."
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }
}
