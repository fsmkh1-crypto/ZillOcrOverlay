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

    private data class MappingCell(
        val label: String,
        val expected: String,
        val metricKey: String,
        val ordinal: Int,
    )

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
                        buildString {
                            append(result.toReport())
                            append("\n\n")
                            append(exact.report)
                            val mapping = metricOrderMappingReport(exactPreviews)
                            if (mapping.isNotBlank()) {
                                append("\n\n")
                                append(mapping)
                            }
                        }
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

        root.addView(TextView(this).apply {
            text = "질올 한글패치"
            textSize = 24f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "PoC 0.5 · 정확한 PAR/GIM + metrics→cell 매핑 검증\nOCR 번역기와 별개의 독립 패처 앱입니다."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(16))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · 폰트 심층 분석"
            setOnClickListener {
                isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*"))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) {
                    Toast.makeText(this@MainActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                } else {
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

        atlasContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(atlasContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return ScrollView(this).apply { addView(root) }
    }

    private fun renderPreviews(
        exact: List<GimFontProbe.Preview>,
        heuristic: List<FontAtlasProbe.Preview>,
    ) {
        atlasContainer.removeAllViews()
        if (exact.isNotEmpty()) {
            renderMetricOrderCandidateCrops(exact)
            renderExactGimPreviews(exact)
        } else {
            renderHeuristicPreviews(heuristic)
        }
    }

    private fun renderMetricOrderCandidateCrops(previews: List<GimFontProbe.Preview>) {
        val page0 = previews.firstOrNull { it.sectionIndex == 0 && it.image.width == 512 && it.image.height == 512 }
            ?: return

        atlasContainer.addView(TextView(this).apply {
            text = "metrics.toml → 16×16 cell 검증"
            textSize = 19f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        atlasContainer.addView(TextView(this).apply {
            text = "metrics.toml의 텍스트 순서가 32×32 row-major 글리프 셀 순서라는 가설을 직접 확인합니다. 먼저 0/A/a가 맞는지, 이어서 후보 셀이 腑/躙/綺로 보이는지 확인하세요."
            textSize = 13f
            setPadding(0, 8, 0, 10)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        mappingCells.forEach { cell ->
            val cellX = cell.ordinal % 32
            val cellY = cell.ordinal / 32
            atlasContainer.addView(TextView(this).apply {
                text = "${cell.label} · expected '${cell.expected}' · ${cell.metricKey} · ordinal=${cell.ordinal} · cell=($cellX,$cellY)"
                textSize = 14f
                setPadding(0, 12, 0, 4)
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            atlasContainer.addView(
                argbCropImageView(page0.retailArgb, 512, 512, cellX * 16, cellY * 16, 16, 16),
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun metricOrderMappingReport(previews: List<GimFontProbe.Preview>): String {
        val page0 = previews.firstOrNull { it.sectionIndex == 0 && it.image.width == 512 && it.image.height == 512 }
            ?: return ""
        return buildString {
            appendLine("metrics.toml textual-order -> 16x16 cell hypothesis")
            appendLine("page 0: 512x512 => 32x32 cells, 1024 cells/page")
            appendLine("visual proof targets (retail child 0 crops shown in app):")
            mappingCells.forEach { cell ->
                val cellX = cell.ordinal % 32
                val cellY = cell.ordinal / 32
                val nonTransparent = cropNonTransparentPixels(page0.retailArgb, 512, cellX * 16, cellY * 16, 16, 16)
                appendLine(
                    "  ${cell.metricKey} expected=${cell.expected} ordinal=${cell.ordinal} " +
                        "cell=($cellX,$cellY) nonTransparentPixels=$nonTransparent"
                )
            }
            append("NOTE: ordering is confirmed only if the displayed retail crops visually match the expected characters.")
        }
    }

    private fun cropNonTransparentPixels(
        argb: IntArray,
        width: Int,
        x0: Int,
        y0: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): Int {
        var count = 0
        for (y in y0 until y0 + cropHeight) {
            for (x in x0 until x0 + cropWidth) {
                if ((argb[y * width + x] ushr 24) and 0xff != 0) count++
            }
        }
        return count
    }

    private fun renderExactGimPreviews(previews: List<GimFontProbe.Preview>) {
        val changed = previews.filter { it.changedLogicalPixels > 0 }
        val shown = if (changed.isNotEmpty()) changed else previews
        atlasContainer.addView(TextView(this).apply {
            text = "Exact PAR/GIM decode"
            textSize = 18f
            setPadding(0, 20, 0, 0)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        atlasContainer.addView(TextView(this).apply {
            text = "upstream zill 도구의 실제 GIM descriptor·palette·PSP swizzle 규칙과 같은 방식으로 해석한 결과입니다."
            textSize = 13f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        shown.forEach { preview ->
            val w = preview.image.width
            val h = preview.image.height
            atlasContainer.addView(TextView(this).apply {
                text = "Child ${preview.sectionIndex} · ${w}×${h} ${preview.image.bits}bpp · reconstructed English"
                textSize = 16f
                setPadding(0, 18, 0, 4)
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            atlasContainer.addView(argbImageView(preview.englishArgb, w, h), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            atlasContainer.addView(TextView(this).apply {
                text = "Child ${preview.sectionIndex} · logical change mask (${preview.changedLogicalPixels} pixels)"
                textSize = 15f
                setPadding(0, 12, 0, 4)
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            atlasContainer.addView(grayImageView(preview.deltaMask, w, h), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderHeuristicPreviews(previews: List<FontAtlasProbe.Preview>) {
        val changed = previews.filter { it.changedPixels > 0 }
        if (changed.isEmpty()) return

        atlasContainer.addView(TextView(this).apply {
            text = "Fallback atlas probe (미확정 가설)"
            textSize = 18f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        changed.forEach { preview ->
            atlasContainer.addView(TextView(this).apply {
                text = "Section ${preview.sectionIndex} · reconstructed English"
                textSize = 16f
                setPadding(0, 18, 0, 4)
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            atlasContainer.addView(grayImageView(preview.englishPixels, FontAtlasProbe.WIDTH, FontAtlasProbe.HEIGHT), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun argbImageView(argb: IntArray, width: Int, height: Int): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888))
    }

    private fun argbCropImageView(
        argb: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val source = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(source, x, y, cropWidth, cropHeight)
        val scaled = Bitmap.createScaledBitmap(crop, cropWidth * 12, cropHeight * 12, false)
        setImageBitmap(scaled)
        source.recycle()
        crop.recycle()
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
