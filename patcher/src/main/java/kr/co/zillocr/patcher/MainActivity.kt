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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kr.co.zillocr.patcher.patch.ExactGimIsoAnalyzer
import kr.co.zillocr.patcher.patch.FontAtlasProbe
import kr.co.zillocr.patcher.patch.GimFontProbe
import kr.co.zillocr.patcher.patch.UpstreamFontPatch
import kr.co.zillocr.patcher.patch.ZillFontIsoAnalyzer
import java.io.FileInputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var atlasContainer: LinearLayout
    private var latestReport: String = ""

    private data class MappingCell(val label: String, val expected: String, val metricKey: String, val ordinal: Int)

    private val mappingCells = listOf(
        MappingCell("ASCII sanity", "0", "0x0030", 17),
        MappingCell("ASCII sanity", "A", "0x0041", 33),
        MappingCell("ASCII sanity", "a", "0x0061", 64),
        MappingCell("surrogate 아", "腑", "0x44e4", 223),
        MappingCell("surrogate 이", "躙", "0x57e7", 486),
        MappingCell("surrogate 템", "綺", "0x59e3", 511),
    )

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "ISO 및 upstream 영문 폰트 패치 분석 중…"
        atlasContainer.removeAllViews()
        executor.execute {
            var heuristicPreviews: List<FontAtlasProbe.Preview> = emptyList()
            var exactPreviews: List<GimFontProbe.Preview> = emptyList()
            val report = try {
                val patch = UpstreamFontPatch.download()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val result = ZillFontIsoAnalyzer.analyze(channel, patch.xor)
                        heuristicPreviews = result.atlasPreviews
                        channel.position(0)
                        val exact = ExactGimIsoAnalyzer.analyze(channel, patch.xor)
                        exactPreviews = exact.previews
                        result.toReport() + "\n\n" + exact.report + "\n\n" + metricOrderMappingReport(exactPreviews)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "폰트 분석 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread {
                statusView.text = report
                renderPreviews(exactPreviews, heuristicPreviews)
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
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(TextView(this).apply { text = "질올 한글패치"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "PoC 0.6 · 물리 글리프 매핑 재검증\n고대비 이웃 셀로 metrics 순서 가설을 검증합니다."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · 글리프 매핑 분석"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) Toast.makeText(this@MainActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("zillfont diagnostics", latestReport))
                    Toast.makeText(this@MainActivity, "분석 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusView = TextView(this).apply {
            text = "아직 분석하지 않았습니다."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(18), 0, dp(12))
        }
        root.addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        atlasContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(atlasContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderPreviews(exact: List<GimFontProbe.Preview>, heuristic: List<FontAtlasProbe.Preview>) {
        atlasContainer.removeAllViews()
        if (exact.isNotEmpty()) {
            renderNeighborMappingProbe(exact)
            renderExactGimPreviews(exact)
        } else renderHeuristicPreviews(heuristic)
    }

    private fun renderNeighborMappingProbe(previews: List<GimFontProbe.Preview>) {
        val page0 = previews.firstOrNull { it.sectionIndex == 0 && it.image.width == 512 && it.image.height == 512 } ?: return
        atlasContainer.addView(TextView(this).apply {
            text = "물리 셀 순서 검증 · 고대비"
            textSize = 20f
        })
        atlasContainer.addView(TextView(this).apply {
            text = "각 예상 위치의 앞뒤 8셀을 함께 표시합니다. 검은 글자가 선명하게 보입니다. 중앙 후보가 틀리면 실제 0/A/a가 어느 ordinal에 있는지 이 줄에서 바로 찾을 수 있습니다."
            textSize = 13f
            setPadding(0, 8, 0, 12)
        })
        mappingCells.take(3).forEach { target -> addNeighborStrip(page0, target) }
        atlasContainer.addView(TextView(this).apply {
            text = "ASCII 3개가 동일한 규칙으로 맞는 것이 확인되기 전에는 surrogate 위치를 확정하지 않습니다."
            textSize = 13f
            setPadding(0, 14, 0, 8)
        })
    }

    private fun addNeighborStrip(page: GimFontProbe.Preview, target: MappingCell) {
        atlasContainer.addView(TextView(this).apply {
            text = "expected '${target.expected}' · metrics ordinal=${target.ordinal} · ±8 cells"
            textSize = 15f
            setPadding(0, 10, 0, 4)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = maxOf(0, target.ordinal - 8)
        val end = minOf(1023, target.ordinal + 8)
        for (ordinal in start..end) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4, 0, 4, 0)
            }
            cell.addView(TextView(this).apply {
                text = if (ordinal == target.ordinal) "[$ordinal]" else ordinal.toString()
                textSize = 11f
            })
            cell.addView(highContrastCellView(page.retailArgb, ordinal))
            row.addView(cell)
        }
        atlasContainer.addView(HorizontalScrollView(this).apply { addView(row) }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun highContrastCellView(argb: IntArray, ordinal: Int): ImageView {
        val cellX = ordinal % 32
        val cellY = ordinal / 32
        val pixels = IntArray(16 * 16)
        var p = 0
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val source = argb[(cellY * 16 + y) * 512 + (cellX * 16 + x)]
                val alpha = (source ushr 24) and 0xff
                val v = 255 - alpha
                pixels[p++] = Color.argb(255, v, v, v)
            }
        }
        val raw = Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(raw, 128, 128, false)
        raw.recycle()
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageBitmap(scaled)
            layoutParams = ViewGroup.LayoutParams(128, 128)
        }
    }

    private fun metricOrderMappingReport(previews: List<GimFontProbe.Preview>): String {
        val page0 = previews.firstOrNull { it.sectionIndex == 0 && it.image.width == 512 && it.image.height == 512 } ?: return ""
        return buildString {
            appendLine("metrics.toml textual-order mapping remains UNCONFIRMED")
            appendLine("PoC 0.6 displays high-contrast ordinal neighborhoods for ASCII anchors 0/A/a.")
            mappingCells.take(3).forEach { target ->
                val x = target.ordinal % 32
                val y = target.ordinal / 32
                appendLine("  expected=${target.expected} metric=${target.metricKey} ordinal=${target.ordinal} cell=($x,$y) nonTransparent=${cropNonTransparentPixels(page0.retailArgb, target.ordinal)}")
            }
            append("Do not derive surrogate physical cells until all ASCII anchors establish one consistent mapping rule.")
        }
    }

    private fun cropNonTransparentPixels(argb: IntArray, ordinal: Int): Int {
        val cellX = ordinal % 32
        val cellY = ordinal / 32
        var count = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            if (((argb[(cellY * 16 + y) * 512 + (cellX * 16 + x)] ushr 24) and 0xff) != 0) count++
        }
        return count
    }

    private fun renderExactGimPreviews(previews: List<GimFontProbe.Preview>) {
        val shown = previews.filter { it.changedLogicalPixels > 0 }.ifEmpty { previews }
        atlasContainer.addView(TextView(this).apply { text = "Exact PAR/GIM decode"; textSize = 18f; setPadding(0, 20, 0, 0) })
        shown.forEach { preview ->
            val w = preview.image.width
            val h = preview.image.height
            atlasContainer.addView(TextView(this).apply { text = "Child ${preview.sectionIndex} · ${w}×${h} ${preview.image.bits}bpp · reconstructed English"; textSize = 16f })
            atlasContainer.addView(argbImageView(preview.englishArgb, w, h), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderHeuristicPreviews(previews: List<FontAtlasProbe.Preview>) {
        val changed = previews.filter { it.changedPixels > 0 }
        if (changed.isEmpty()) return
        atlasContainer.addView(TextView(this).apply { text = "Fallback atlas probe"; textSize = 18f })
        changed.forEach { preview -> atlasContainer.addView(grayImageView(preview.englishPixels, FontAtlasProbe.WIDTH, FontAtlasProbe.HEIGHT)) }
    }

    private fun argbImageView(argb: IntArray, width: Int, height: Int): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888))
    }

    private fun grayImageView(gray: ByteArray, width: Int, height: Int): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        val colors = IntArray(gray.size) { index ->
            val v = gray[index].toInt() and 0xff
            Color.argb(255, v, v, v)
        }
        setImageBitmap(Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888))
    }
}
