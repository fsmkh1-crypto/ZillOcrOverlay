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
import kr.co.zillocr.patcher.patch.UpstreamMetrics
import java.io.FileInputStream
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * PoC 1.2: 15x16 geometry + CP932 encoded-byte ordering probe.
 *
 * 15x16 geometry is already validated by ASCII screenshots. The old CJK ordinal
 * calculation used metrics.toml textual/numeric key order, which is wrong for
 * two-byte CP932 because metric keys are byte-reversed (E4 44 -> 0x44e4).
 * This probe instead reconstructs each key's actual CP932 bytes, sorts by those
 * bytes, then crops the resulting physical slot. It remains strictly read-only.
 */
class MetricSlotActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var resultContainer: LinearLayout
    private var latestReport = ""
    private var latestTargets: List<Target> = emptyList()

    private data class Target(
        val text: String,
        val key: Int,
        val ordinal: Int,
        val bytesHex: String,
    )

    private val targetTexts = listOf("0", "A", "a", "ア", "イ", "テ", "ム", "腑", "躙", "綺")

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "ISO · exact GIM · CP932 물리 순서 분석 중…"
        resultContainer.removeAllViews()
        executor.execute {
            var previews: List<GimFontProbe.Preview> = emptyList()
            var targets: List<Target> = emptyList()
            val report = try {
                val patch = UpstreamFontPatch.download()
                val entries = UpstreamMetrics.downloadEntries()
                val ordered = UpstreamMetrics.sortedByEncodedBytes(entries)
                targets = buildTargets(ordered)
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val exact = ExactGimIsoAnalyzer.analyze(channel, patch.xor)
                        previews = exact.previews
                        exact.report + "\n\n" + orderingReport(previews, targets)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "CP932 슬롯 분석 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestTargets = targets
            latestReport = report
            runOnUiThread {
                statusView.text = report
                renderTargets(previews, targets)
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
            text = "PoC 1.2 · CP932 물리 슬롯 순서 검증\n15×16 geometry는 유지하되, CJK는 byte-reversed metrics 숫자 순서가 아니라 실제 CP932 바이트 순서로 찾습니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · CP932 슬롯 검증"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) {
                    Toast.makeText(this@MetricSlotActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("zillfont CP932-slot diagnostics", latestReport))
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

    private fun buildTargets(ordered: List<UpstreamMetrics.Entry>): List<Target> {
        val byKey = ordered.withIndex().associate { it.value.key to it.index }
        return targetTexts.map { text ->
            val bytes = encodeCp932(text)
            val key = metricKey(bytes)
            val ordinal = byKey[key] ?: error("metrics repertoire does not contain '$text' key=0x${key.toString(16)}")
            Target(text, key, ordinal, bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) })
        }
    }

    private fun orderingReport(previews: List<GimFontProbe.Preview>, targets: List<Target>): String = buildString {
        appendLine("15x16 + CP932 encoded-byte order probe")
        appendLine("geometry: 512x512, slot=15x16, columns=34, rows=32, slots/page=1088, right-edge-unused=2")
        appendLine("repertoire: authenticated upstream metrics.toml, 2637 unique keys")
        val validSections = previews.map { it.sectionIndex }.sorted()
        appendLine("decoded GIM sections: ${validSections.joinToString(",")}")
        val anchorExpected = mapOf("0" to 17, "A" to 33, "a" to 64)
        targets.forEach { t ->
            val page = t.ordinal / SLOTS_PER_PAGE
            val local = t.ordinal % SLOTS_PER_PAGE
            val col = local % COLUMNS
            val row = local / COLUMNS
            val anchor = anchorExpected[t.text]
            append("  '${t.text}': bytes=${t.bytesHex} key=0x${t.key.toString(16).padStart(4, '0')} ordinal=${t.ordinal}")
            if (anchor != null) append(" anchor=${if (t.ordinal == anchor) "PASS" else "FAIL(expected $anchor)"}")
            appendLine(" -> s$page:$local px=(${col * SLOT_W},${row * SLOT_H})")
        }
        append("Read-only. CJK mapping is accepted only if kana ア/イ/テ/ム and surrogate kanji crops visually match their labels.")
    }.trimEnd()

    private fun renderTargets(previews: List<GimFontProbe.Preview>, targets: List<Target>) {
        resultContainer.removeAllViews()
        val pages = previews.associateBy { it.sectionIndex }
        resultContainer.addView(TextView(this).apply {
            text = "CP932 바이트 순서 슬롯"
            textSize = 20f
            setPadding(0, 10, 0, 6)
        })
        resultContainer.addView(TextView(this).apply {
            text = "각 줄은 retail / reconstructed English 순서입니다. 0/A/a뿐 아니라 ア/イ/テ/ム과 腑/躙/綺도 라벨과 실제 모양이 맞아야 CJK 매핑을 확정합니다."
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
                text = "${t.text} · ${t.bytesHex} · ordinal=${t.ordinal} · s$pageIndex:$local · px=($x,$y)"
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

    private fun encodeCp932(text: String): ByteArray {
        val charset = Charset.forName("windows-31j")
        val encoded = text.toByteArray(charset)
        require(encoded.size in 1..2) { "'$text' is not a single CP932 code unit" }
        return encoded
    }

    private fun metricKey(bytes: ByteArray): Int = when (bytes.size) {
        1 -> bytes[0].toInt() and 0xff
        2 -> (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
        else -> error("unsupported CP932 width")
    }

    private companion object {
        const val SLOT_W = 15
        const val SLOT_H = 16
        const val COLUMNS = 34
        const val ROWS = 32
        const val SLOTS_PER_PAGE = COLUMNS * ROWS
    }
}
