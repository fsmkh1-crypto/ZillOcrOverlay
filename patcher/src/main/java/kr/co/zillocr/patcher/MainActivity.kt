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
import kr.co.zillocr.patcher.patch.FontGlyphMatcher
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

    private val matchTargets = listOf("0", "A", "a", "ア", "イ", "テ", "ム", "腑", "躙", "綺")

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "ISO · GIM · 글리프 형태 매칭 분석 중…"
        atlasContainer.removeAllViews()
        executor.execute {
            var heuristicPreviews: List<FontAtlasProbe.Preview> = emptyList()
            var exactPreviews: List<GimFontProbe.Preview> = emptyList()
            var matches: Map<String, List<FontGlyphMatcher.Match>> = emptyMap()
            val report = try {
                val patch = UpstreamFontPatch.download()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val result = ZillFontIsoAnalyzer.analyze(channel, patch.xor)
                        heuristicPreviews = result.atlasPreviews
                        channel.position(0)
                        val exact = ExactGimIsoAnalyzer.analyze(channel, patch.xor)
                        exactPreviews = exact.previews
                        matches = FontGlyphMatcher.rank(exactPreviews, matchTargets, topN = 8)
                        result.toReport() + "\n\n" + exact.report + "\n\n" + matcherReport(matches)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "폰트 분석 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread {
                statusView.text = report
                renderPreviews(exactPreviews, heuristicPreviews, matches)
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
            text = "PoC 0.7 · metrics 순서 폐기 · 물리 글리프 형태 검색\n0/A/a로 검색기를 검증한 뒤 일본어와 surrogate 후보를 찾습니다."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · 글리프 형태 검색"
            setOnClickListener { isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) Toast.makeText(this@MainActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                else {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("zillfont diagnostics", latestReport))
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

    private fun renderPreviews(
        exact: List<GimFontProbe.Preview>,
        heuristic: List<FontAtlasProbe.Preview>,
        matches: Map<String, List<FontGlyphMatcher.Match>>,
    ) {
        atlasContainer.removeAllViews()
        if (exact.isNotEmpty()) {
            renderMatcher(matches, exact)
            renderExactGimPreviews(exact)
        } else renderHeuristicPreviews(heuristic)
    }

    private fun renderMatcher(
        matches: Map<String, List<FontGlyphMatcher.Match>>,
        previews: List<GimFontProbe.Preview>,
    ) {
        if (matches.isEmpty()) return
        atlasContainer.addView(TextView(this).apply {
            text = "물리 글리프 형태 검색 · Top 8"
            textSize = 20f
        })
        atlasContainer.addView(TextView(this).apply {
            text = "Android 시스템 글꼴과 atlas 셀의 윤곽을 비교한 보조 검색입니다. 먼저 0/A/a의 1위가 실제 위치(0=16, A=31, a=60)와 맞는지 확인해야 합니다. 그 검증 전에는 일본어 결과를 확정하지 않습니다."
            textSize = 13f
            setPadding(0, 8, 0, 12)
        })
        matchTargets.forEach { target ->
            val ranked = matches[target].orEmpty()
            if (ranked.isEmpty()) return@forEach
            atlasContainer.addView(TextView(this).apply {
                text = "target '$target'"
                textSize = 16f
                setPadding(0, 10, 0, 4)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ranked.forEachIndexed { rank, match ->
                val page = previews.firstOrNull { it.sectionIndex == match.sectionIndex } ?: return@forEachIndexed
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(4, 0, 4, 0)
                }
                box.addView(TextView(this).apply {
                    text = "#${rank + 1} s${match.sectionIndex}:${match.ordinal}\n${"%.3f".format(match.score)}"
                    textSize = 10f
                    gravity = Gravity.CENTER
                })
                box.addView(highContrastCellView(page.retailArgb, match.ordinal))
                row.addView(box)
            }
            atlasContainer.addView(HorizontalScrollView(this).apply { addView(row) }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun highContrastCellView(argb: IntArray, ordinal: Int): ImageView {
        val cellX = ordinal % 32
        val cellY = ordinal / 32
        val pixels = IntArray(16 * 16)
        var p = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            val source = argb[(cellY * 16 + y) * 512 + cellX * 16 + x]
            val alpha = (source ushr 24) and 0xff
            val v = 255 - alpha
            pixels[p++] = Color.argb(255, v, v, v)
        }
        val raw = Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(raw, 112, 112, false)
        raw.recycle()
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageBitmap(scaled)
            layoutParams = ViewGroup.LayoutParams(112, 112)
        }
    }

    private fun matcherReport(matches: Map<String, List<FontGlyphMatcher.Match>>): String = buildString {
        appendLine("visual glyph matcher (heuristic; metrics textual order is NOT used)")
        appendLine("known physical ASCII anchors from PoC 0.6 screenshot: 0=s0:16, A=s0:31, a=s0:60")
        matchTargets.forEach { target ->
            append("  $target:")
            matches[target].orEmpty().take(8).forEach { m ->
                append(" s${m.sectionIndex}:${m.ordinal}(${"%.3f".format(m.score)})")
            }
            appendLine()
        }
        append("Trust Japanese/surrogate rankings only if ASCII anchors rank correctly first.")
    }.trimEnd()

    private fun renderExactGimPreviews(previews: List<GimFontProbe.Preview>) {
        val shown = previews.filter { it.changedLogicalPixels > 0 }.ifEmpty { previews }
        atlasContainer.addView(TextView(this).apply {
            text = "Exact PAR/GIM decode"
            textSize = 18f
            setPadding(0, 20, 0, 0)
        })
        shown.forEach { preview ->
            val w = preview.image.width
            val h = preview.image.height
            atlasContainer.addView(TextView(this).apply {
                text = "Child ${preview.sectionIndex} · ${w}×${h} ${preview.image.bits}bpp · reconstructed English"
                textSize = 16f
            })
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
