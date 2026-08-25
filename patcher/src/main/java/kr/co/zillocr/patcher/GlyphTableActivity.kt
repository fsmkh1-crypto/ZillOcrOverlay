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
import kr.co.zillocr.patcher.patch.GlyphArchiveOriginTrace
import kr.co.zillocr.patcher.patch.Iso9660Reader
import java.io.FileInputStream
import java.util.concurrent.Executors

/** PoC 3.7: decode PAA records and resolve exact ARC members. */
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
        statusView.text = "PAA 레코드를 역탐색하고 ARC 멤버 위치를 계산 중…"
        executor.execute {
            val report = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val iso = Iso9660Reader(channel)
                        val files = iso.listFiles()
                        val zill = files.firstOrNull {
                            it.path.replace('\\', '/').lowercase().endsWith("font/zillfont.par")
                        }
                        val jill = files.firstOrNull {
                            it.path.replace('\\', '/').lowercase().endsWith("2d/font/jillbtn.par")
                        }
                        if (zill != null && jill != null) {
                            GlyphArchiveOriginTrace.report(
                                zillPath = zill.path,
                                zill = iso.readEntry(zill.entry),
                                jillPath = jill.path,
                                jill = iso.readEntry(jill.entry),
                            )
                        } else {
                            fun physical(suffix: String) = files.firstOrNull {
                                it.path.replace('\\', '/').lowercase().endsWith(suffix.lowercase())
                            }
                            val pairs = listOfNotNull(
                                physical("PSP_GAME/USRDIR/pa.bin")?.let { index ->
                                    physical("PSP_GAME/USRDIR/pa.arc")?.let { arc ->
                                        GlyphArchiveOriginTrace.ArchivePair(
                                            indexPath = index.path,
                                            index = iso.readEntry(index.entry),
                                            arcPath = arc.path,
                                            arcSize = arc.entry.size,
                                            readArc = { offset, length -> iso.readEntryRange(arc.entry, offset, length) },
                                        )
                                    }
                                },
                                physical("PSP_GAME/USRDIR/pami.bin")?.let { index ->
                                    physical("PSP_GAME/USRDIR/pami.arc")?.let { arc ->
                                        GlyphArchiveOriginTrace.ArchivePair(
                                            indexPath = index.path,
                                            index = iso.readEntry(index.entry),
                                            arcPath = arc.path,
                                            arcSize = arc.entry.size,
                                            readArc = { offset, length -> iso.readEntryRange(arc.entry, offset, length) },
                                        )
                                    }
                                },
                            )
                            if (pairs.isNotEmpty()) {
                                GlyphArchiveOriginTrace.archiveIndexReport(pairs)
                            } else {
                                val boot = iso.readEntry(iso.find("PSP_GAME/SYSDIR/BOOT.BIN"))
                                GlyphArchiveOriginTrace.virtualResourceReport(
                                    boot = boot,
                                    directZillFound = zill != null,
                                    directJillFound = jill != null,
                                    isoFiles = files,
                                )
                            }
                        }
                    }
                } ?: error("ISO 파일을 열 수 없습니다.")
            } catch (t: Throwable) {
                "glyph virtual-resource probe 실패\n${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
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
        root.addView(TextView(this).apply {
            text = "질올 한글패치"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "PoC 3.7 · PAA 레코드/ARC 멤버 해석\n" +
                "pa.bin의 0x10바이트 레코드와 문자열 풀 구조를 확인했습니다. " +
                "이번 판은 font ID를 참조하는 레코드를 직접 찾고, record+4 크기의 누적·정렬값으로 ARC 멤버 위치를 산출한 뒤 컨테이너 parser를 적용합니다. 쓰기는 비활성입니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "원본 ISO 선택 · PAA/ARC 해석"
            setOnClickListener {
                isoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*"))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "분석 결과 복사"
            setOnClickListener {
                if (latestReport.isBlank()) {
                    Toast.makeText(this@GlyphTableActivity, "먼저 ISO 분석을 실행하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("zill glyph PAA record resolver v8", latestReport)
                    )
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
