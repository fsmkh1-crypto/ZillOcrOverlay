package kr.co.zillocr.patcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kr.co.zillocr.patcher.patch.ExactGimIsoAnalyzer
import kr.co.zillocr.patcher.patch.GimFontProbe
import kr.co.zillocr.patcher.patch.UpstreamFontPatch
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * PoC 1.1: exact 15x16 slot probe.
 *
 * The earlier 16x16/32-column screenshots drifted from metrics ordinals by an
 * amount that grows with horizontal position. A 15-pixel-wide slot grid explains
 * every recorded ASCII anchor exactly: 34 columns (510 px) x 32 rows per page.
 * This activity remains read-only and does not write to the ISO or font.
 */
class MetricSlotActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var resultContainer: LinearLayout
    private var latestReport = ""

    private data class Target(val label: String, val ordinal: Int)

    private val targets = listOf(
        Target("0", 17),
        Target("A", 33),
        Target("a", 64),
        Target("腑 → 아", 223),
        Target("躙 → 이", 486),
        Target("綺 → 템", 511),
    )

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "ISO · exact GIM · 15×16 슬롯 분석 중…"
        resultContainer.removeAllViews()
        executor.execute {
            var previews: List<GimFontProbe.Preview> = emptyList()
            val report = try {
                val patch = UpstreamFontPatch.download()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val exact = ExactGimIsoAnalyzer.analyze(channel, patch.xor)
                        previews = exact.previews
                        exact.report + "\n\n" + geometryReport(previews)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "15×16 슬롯 분석 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread {
                statusView.text = report
                renderTargets(previews)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(TextView(this).apply { text = "질올 한글패치"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "PoC 1.1 · 15×16 글리프 슬롯 검증\n34열 × 32행 geometry로 0/A/a와 surrogate 후보를 직접 자릅니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · 15×16 슬롯 검증"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) {
                    Toast.makeText(this@MetricSlotActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("zillfont 15x16 diagnostics", latestReport))
                    Toast.makeText(this@MetricSlotActivity, "분석 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusView = TextView(this).apply {
            text = "아직 분석하지 않았습니다."
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(10))
        }
        root.addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }

    private fun geometryReport(previews: List<GimFontProbe.Preview>): String = buildString {
        appendLine("15x16 metric-slot probe")
        appendLine("geometry: 512x512, slot=15x16, columns=34, rows=32, slots/page=1088, right-edge-unused=2")
        appendLine("device screenshot anchors already reproduced mathematically: )=9, 0=16, :=25, A=31, \\=56, a=60")
        val validSections = previews.map { it.sectionIndex }.sorted()
        appendLine("decoded GIM sections: ${validSections.joinToString(",")}")
        targets.forEach { t ->
            val page = t.ordinal / SLOTS_PER_PAGE
            val local = t.ordinal % SLOTS_PER_PAGE
            val col = local % COLUMNS
            val row = local / COLUMNS
            appendLine("  ${t.label}: ordinal=${t.ordinal} -> s$page:$local col=$col row=$row px=(${col * SLOT_W},${row * SLOT_H})")
        }
        append("Read-only. Do not enable font writes until the exact ASCII crops visibly read 0/A/a.")
    }.trimEnd()

    private fun renderTargets(previews: List<GimFontProbe.Preview>) {
        resultContainer.removeAllViews()
        val pages = previews.associateBy { it.sectionIndex }
        resultContainer.addView(TextView(this).apply {
            text = "정확한 15×16 슬롯"
            textSize = 20f
            setPadding(0, 10, 0, 6)
        })
        resultContainer.addView(TextView(this).apply {
            text = "각 줄은 retail / reconstructed English 순서입니다. 첫 세 줄이 각각 0, A, a로 선명하게 보이면 geometry 검증 통과입니다."
            textSize = 13f
            setPadding(0, 0, 0, 8)
        })
        targets.forEach { t ->
            val pageIndex = t.ordinal / SLOTS_PER_PAGE
            val local = t.ordinal % SLOTS_PER_PAGE
            val col = local % COLUMNS
            val row = local / COLUMNS
            val page = pages[pageIndex] ?: return@forEach
            val x = col * SLOT_W
            val y = row * SLOT_H
            resultContainer.addView(TextView(this).apply {
                text = "${t.label} · ordinal=${t.ordinal} · s$pageIndex:$local · px=($x,$y)"
                textSize = 14f
                setPadding(0, 8, 0, 2)
            })
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(slotView(page.retailArgb, x, y))
            line.addView(slotView(page.englishArgb, x, y))
            resultContainer.addView(line, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun slotView(argb: IntArray, x0: Int, y0: Int): ImageView {
        val pixels = IntArray(SLOT_W * SLOT_H)
        var p = 0
        for (y in 0 until SLOT_H) for (x in 0 until SLOT_W) {
            val source = argb[(y0 + y) * 512 + x0 + x]
            val alpha = (source ushr 24) and 0xff
            val v = 255 - alpha
            pixels[p++] = Color.argb(255, v, v, v)
        }
        val raw = Bitmap.createBitmap(pixels, SLOT_W, SLOT_H, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(raw, SLOT_W * 10, SLOT_H * 10, false)
        raw.recycle()
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageBitmap(scaled)
            layoutParams = ViewGroup.LayoutParams(SLOT_W * 10, SLOT_H * 10)
        }
    }

    private companion object {
        const val SLOT_W = 15
        const val SLOT_H = 16
        const val COLUMNS = 34
        const val ROWS = 32
        const val SLOTS_PER_PAGE = COLUMNS * ROWS
    }
}
