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
import kr.co.zillocr.patcher.patch.FontAtlasProbe
import kr.co.zillocr.patcher.patch.UpstreamFontPatch
import kr.co.zillocr.patcher.patch.ZillFontIsoAnalyzer
import java.io.FileInputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var atlasContainer: LinearLayout
    private var latestReport: String = ""

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        statusView.text = "ISO 및 upstream 영문 폰트 패치 분석 중…"
        atlasContainer.removeAllViews()
        executor.execute {
            var previews: List<FontAtlasProbe.Preview> = emptyList()
            val report = try {
                val patch = UpstreamFontPatch.download()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val result = ZillFontIsoAnalyzer.analyze(channel, patch.xor)
                        previews = result.atlasPreviews
                        result.toReport()
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "폰트 분석 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
            }
            latestReport = report
            runOnUiThread {
                statusView.text = report
                renderAtlasPreviews(previews)
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
            text = "PoC 0.3 · 폰트 인증 + 영문 재구성 + atlas probe\nOCR 번역기와 별개의 독립 패처 앱입니다."
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

    private fun renderAtlasPreviews(previews: List<FontAtlasProbe.Preview>) {
        atlasContainer.removeAllViews()
        val changed = previews.filter { it.changedPixels > 0 }
        if (changed.isEmpty()) return

        atlasContainer.addView(TextView(this).apply {
            text = "Atlas probe (아직 미확정 가설)"
            textSize = 18f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        atlasContainer.addView(TextView(this).apply {
            text = "각 섹션의 마지막 0x20000바이트를 512×512 4bpp PSP-swizzled 텍스처라고 가정해 푼 화면입니다. 글자 격자가 보이는지 확인하기 위한 진단용이며 원본 ISO는 수정하지 않습니다."
            textSize = 13f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        changed.forEach { preview ->
            atlasContainer.addView(TextView(this).apply {
                text = "Section ${preview.sectionIndex} · reconstructed English"
                textSize = 16f
                setPadding(0, 18, 0, 4)
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            atlasContainer.addView(imageView(preview.englishPixels), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            atlasContainer.addView(TextView(this).apply {
                text = "Section ${preview.sectionIndex} · XOR change mask"
                textSize = 15f
                setPadding(0, 12, 0, 4)
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            atlasContainer.addView(imageView(preview.deltaPixels), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun imageView(gray: ByteArray): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(grayBitmap(gray))
    }

    private fun grayBitmap(gray: ByteArray): Bitmap {
        require(gray.size == FontAtlasProbe.WIDTH * FontAtlasProbe.HEIGHT)
        val colors = IntArray(gray.size) { index ->
            val v = gray[index].toInt() and 0xff
            Color.argb(255, v, v, v)
        }
        return Bitmap.createBitmap(colors, FontAtlasProbe.WIDTH, FontAtlasProbe.HEIGHT, Bitmap.Config.ARGB_8888)
    }
}
