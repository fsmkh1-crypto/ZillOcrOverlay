package kr.co.zillocr.patcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import kr.co.zillocr.patcher.patch.GimFontEditor
import kr.co.zillocr.patcher.patch.GimFontProbe
import kr.co.zillocr.patcher.patch.Iso9660Reader
import kr.co.zillocr.patcher.patch.UpstreamFontPatch
import kr.co.zillocr.patcher.patch.UpstreamSourceFont
import kr.co.zillocr.patcher.patch.ZillFontIsoAnalyzer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var atlasContainer: LinearLayout
    private var latestReport: String = ""

    private val matchTargets = listOf("0", "A", "a", "ア", "イ", "テ", "ム", "腑", "躙", "綺")
    private val knownAsciiAnchors = mapOf("0" to 16, "A" to 31, "a" to 60)
    private val hangulPocCells = listOf("아" to 16, "이" to 31, "템" to 60)

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "ISO · GIM · OpenType · 한글 셀 쓰기 검증 중…"
        atlasContainer.removeAllViews()
        executor.execute {
            var heuristicPreviews: List<FontAtlasProbe.Preview> = emptyList()
            var exactPreviews: List<GimFontProbe.Preview> = emptyList()
            var matches: Map<String, List<FontGlyphMatcher.Match>> = emptyMap()
            var hangulPreview: GimFontProbe.Preview? = null
            val report = try {
                val patch = UpstreamFontPatch.download()
                val sourceFontBytes = UpstreamSourceFont.download()
                val sourceFontFile = File(cacheDir, "fs-tahoma-8px-authenticated.otf")
                sourceFontFile.writeBytes(sourceFontBytes)
                val sourceTypeface = Typeface.createFromFile(sourceFontFile)
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val result = ZillFontIsoAnalyzer.analyze(channel, patch.xor)
                        heuristicPreviews = result.atlasPreviews
                        channel.position(0)
                        val exact = ExactGimIsoAnalyzer.analyze(channel, patch.xor)
                        exactPreviews = exact.previews
                        matches = FontGlyphMatcher.rank(
                            exactPreviews,
                            matchTargets,
                            topN = 3072,
                            typeface = sourceTypeface,
                        )

                        if (result.matchesRetailFont && result.parSections.isNotEmpty()) {
                            val iso = Iso9660Reader(channel)
                            val paArc = iso.find("PSP_GAME/USRDIR/pa.arc")
                            val retailFont = iso.readEntryRange(paArc, result.memberOffset, result.memberSize)
                            val page0 = result.parSections.firstOrNull { it.index == 0 }
                                ?: error("zillfont PAR page 0 missing")
                            var edited = retailFont
                            val paletteFacts = mutableListOf<String>()
                            hangulPocCells.forEach { (text, ordinal) ->
                                val edit = GimFontEditor.replaceBinaryCell(
                                    edited,
                                    page0,
                                    ordinal,
                                    rasterizeGlyphMask(text),
                                )
                                edited = edit.font
                                paletteFacts += "$text=s0:$ordinal(palette ${edit.transparentPaletteIndex}->${edit.opaquePaletteIndex})"
                            }
                            hangulPreview = GimFontProbe.build(retailFont, edited, result.parSections)
                                .firstOrNull { it.sectionIndex == 0 }
                            if (hangulPreview == null) error("failed to decode in-memory Hangul preview")
                            latestHangulFacts = paletteFacts.joinToString(" · ")
                        }

                        result.toReport() + "\n\n" + exact.report + "\n\n" + sourceFontReport() +
                            "\n\n" + FontGlyphMatcher.cmapDiagnosticReport(matchTargets) +
                            "\n\n" + matcherReport(matches) +
                            "\n\n" + hangulWriteReport(hangulPreview)
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "폰트 분석 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread {
                statusView.text = report
                renderPreviews(exactPreviews, heuristicPreviews, matches, hangulPreview)
            }
        }
    }

    @Volatile
    private var latestHangulFacts: String = ""

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
            text = "PoC 1.1 · OpenType 검증 + 한글 셀 쓰기 메모리 프리뷰\n아직 ISO에는 어떤 바이트도 쓰지 않습니다."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · 안전한 메모리 검증"
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
        hangulPreview: GimFontProbe.Preview?,
    ) {
        atlasContainer.removeAllViews()
        if (hangulPreview != null) renderHangulWritePreview(hangulPreview)
        if (exact.isNotEmpty()) {
            renderMatcher(matches, exact)
            renderExactGimPreviews(exact)
        } else renderHeuristicPreviews(heuristic)
    }

    private fun renderHangulWritePreview(preview: GimFontProbe.Preview) {
        atlasContainer.addView(TextView(this).apply {
            text = "한글 셀 쓰기 · 메모리 프리뷰"
            textSize = 20f
        })
        atlasContainer.addView(TextView(this).apply {
            text = "검증된 raw cell 16/31/60을 임시로 아/이/템으로 다시 그린 결과입니다. 원본 ISO는 읽기 전용이며 아직 저장·수정하지 않습니다."
            textSize = 13f
            setPadding(0, 8, 0, 12)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hangulPocCells.forEach { (text, ordinal) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(6, 0, 6, 0)
            }
            box.addView(TextView(this).apply {
                this.text = "$text\ns0:$ordinal"
                textSize = 12f
                gravity = Gravity.CENTER
            })
            box.addView(highContrastCellView(preview.englishArgb, ordinal))
            row.addView(box)
        }
        atlasContainer.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun renderMatcher(
        matches: Map<String, List<FontGlyphMatcher.Match>>,
        previews: List<GimFontProbe.Preview>,
    ) {
        if (matches.isEmpty()) return
        atlasContainer.addView(TextView(this).apply {
            text = "OpenType cmap / 물리 셀 검증"
            textSize = 20f
            setPadding(0, 18, 0, 0)
        })
        atlasContainer.addView(TextView(this).apply {
            text = FontGlyphMatcher.cmapDiagnosticReport(matchTargets)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 12)
        })
        atlasContainer.addView(TextView(this).apply {
            text = asciiValidationSummary(matches)
            textSize = 13f
            setPadding(0, 8, 0, 12)
        })
        matchTargets.forEach { target ->
            val ranked = matches[target].orEmpty().take(8)
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
                // The matcher ranks the reconstructed English atlas, so show the same bytes.
                box.addView(highContrastCellView(page.englishArgb, match.ordinal))
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

    private fun rasterizeGlyphMask(text: String): BooleanArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val x = (16f - bounds.width()) / 2f - bounds.left
        val y = (16f - bounds.height()) / 2f - bounds.top
        canvas.drawText(text, x, y, paint)
        val pixels = IntArray(16 * 16)
        bitmap.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        bitmap.recycle()
        val mask = BooleanArray(16 * 16) { i -> ((pixels[i] ushr 24) and 0xff) > 0 }
        require(mask.any { it }) { "Android system font could not rasterize '$text'" }
        return mask
    }

    private fun asciiValidationSummary(matches: Map<String, List<FontGlyphMatcher.Match>>): String {
        val facts = knownAsciiAnchors.map { (target, ordinal) ->
            val ranked = matches[target].orEmpty()
            val index = ranked.indexOfFirst { it.sectionIndex == 0 && it.ordinal == ordinal }
            val match = if (index >= 0) ranked[index] else null
            Triple(target, index + 1, match?.score)
        }
        val pass = facts.all { it.second == 1 }
        return buildString {
            append(if (pass) "ASCII matcher validation: PASS" else "ASCII matcher validation: FAIL / UNTRUSTED")
            append("\nknown cells: ")
            facts.forEachIndexed { i, (target, rank, score) ->
                if (i > 0) append(" · ")
                append("$target=#${if (rank > 0) rank else "?"}")
                if (score != null) append(" (${"%.3f".format(score)})")
            }
            if (!pass) append("\n일본어·surrogate Top 8은 참고값일 뿐 확정하지 않습니다.")
        }
    }

    private fun sourceFontReport(): String = buildString {
        appendLine("authenticated upstream source font")
        appendLine("name: release/font/fs-tahoma-8px.otf")
        appendLine("size: ${UpstreamSourceFont.EXPECTED_SIZE}")
        append("git blob SHA-1: ${UpstreamSourceFont.EXPECTED_GIT_BLOB_SHA1} (match required before use)")
    }

    private fun matcherReport(matches: Map<String, List<FontGlyphMatcher.Match>>): String = buildString {
        appendLine("visual glyph matcher (authenticated upstream source typeface; metrics order is NOT physical cell order)")
        appendLine("known physical ASCII anchors: 0=s0:16, A=s0:31, a=s0:60")
        appendLine(asciiValidationSummary(matches).replace("\n", " | "))
        matchTargets.forEach { target ->
            append("  $target:")
            matches[target].orEmpty().take(8).forEach { m ->
                append(" s${m.sectionIndex}:${m.ordinal}(${"%.3f".format(m.score)})")
            }
            appendLine()
        }
        append("Japanese/surrogate rankings are trustworthy only after deterministic cmap validation or explicit ASCII validation passes.")
    }.trimEnd()

    private fun hangulWriteReport(preview: GimFontProbe.Preview?): String = buildString {
        appendLine("in-memory Hangul cell-write PoC")
        appendLine("source ISO write operations: NONE")
        appendLine("temporary surrogate cells: 0x30('0')->s0:16, 0x41('A')->s0:31, 0x61('a')->s0:60")
        appendLine("preview target: 0Aa visually becomes 아이템 after those three cells are replaced")
        appendLine("palette selection: ${if (latestHangulFacts.isBlank()) "unavailable" else latestHangulFacts}")
        append("memory preview decode: ${if (preview != null) "PASS" else "FAIL"}")
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
