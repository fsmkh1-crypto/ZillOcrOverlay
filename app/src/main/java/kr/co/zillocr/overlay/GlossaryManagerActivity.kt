package kr.co.zillocr.overlay

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.GlossaryEntity
import kr.co.zillocr.overlay.translation.OpenAiTranslationProvider
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class GlossaryManagerActivity : ComponentActivity() {

    private val database by lazy { AppDatabase.get(this) }
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private lateinit var listView: TextView

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importGlossaryFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        refreshList()
    }

    override fun onDestroy() {
        dbExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "질올 용어집 관리"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "한 건 입력, 여러 줄 붙여넣기, TXT/DOCX/CSV/TSV 파일 가져오기를 지원합니다."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "한 건 추가 / 수정"
            setOnClickListener { showSingleEditDialog() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "여러 줄 일괄 붙여넣기"
            setOnClickListener { showBulkPasteDialog() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "TXT / DOCX / CSV / TSV 파일 가져오기"
            setOnClickListener {
                filePicker.launch(
                    arrayOf(
                        "text/plain",
                        "text/csv",
                        "text/tab-separated-values",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword",
                        "application/octet-stream"
                    )
                )
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "용어 삭제"
            setOnClickListener { showDeleteDialog() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "입력 형식 예시\nロストール=로스토르\nソウル → 소울\nインフィニティア\t인피니티아\n\nDOCX는 표의 1열=일본어, 2열=한국어 형식도 읽습니다. 구형 .doc 파일은 Word에서 .docx 또는 .txt로 저장한 뒤 가져오세요."
            textSize = 13f
            setPadding(0, dp(12), 0, dp(14))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        listView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(listView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshList() {
        dbExecutor.execute {
            val entries = database.glossaryDao().all()
            val body = if (entries.isEmpty()) {
                "등록된 용어가 없습니다."
            } else {
                buildString {
                    append("등록된 용어: ${entries.size}개\n\n")
                    entries.take(300).forEach { append("${it.sourceTerm} → ${it.targetTerm}\n") }
                    if (entries.size > 300) append("\n… 외 ${entries.size - 300}개")
                }
            }
            runOnUiThread { listView.text = body }
        }
    }

    private fun showSingleEditDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val source = EditText(this).apply { hint = "일본어 원문" }
        val target = EditText(this).apply { hint = "한국어 표기" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(source)
            addView(target)
        }

        AlertDialog.Builder(this)
            .setTitle("용어 추가 / 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val pair = source.text.toString().trim() to target.text.toString().trim()
                if (pair.first.isBlank() || pair.second.isBlank()) {
                    Toast.makeText(this, "원문과 번역어를 모두 입력하세요", Toast.LENGTH_SHORT).show()
                } else {
                    savePairs(listOf(pair), skipped = 0)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showBulkPasteDialog() {
        val input = EditText(this).apply {
            hint = "ロストール=로스토르\nソウル=소울\nインフィニティア=인피니티아"
            minLines = 10
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("여러 줄 용어 일괄 추가")
            .setMessage("한 줄에 일본어와 한국어 한 쌍을 입력하세요. =, →, 탭, 쉼표를 구분자로 사용할 수 있습니다.")
            .setView(input)
            .setPositiveButton("가져오기") { _, _ ->
                val parsed = parseGlossaryText(input.text.toString())
                if (parsed.pairs.isEmpty()) {
                    Toast.makeText(this, "가져올 수 있는 용어가 없습니다", Toast.LENGTH_LONG).show()
                } else {
                    savePairs(parsed.pairs, parsed.skipped)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDeleteDialog() {
        val source = EditText(this).apply { hint = "삭제할 일본어 원문" }
        AlertDialog.Builder(this)
            .setTitle("용어 삭제")
            .setView(source)
            .setPositiveButton("삭제") { _, _ ->
                val sourceText = source.text.toString().trim()
                if (sourceText.isBlank()) return@setPositiveButton
                dbExecutor.execute {
                    database.glossaryDao().delete(sourceText)
                    database.translationDao().invalidateContaining(sourceText)
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread {
                        Toast.makeText(this, "용어를 삭제했습니다", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun importGlossaryFile(uri: Uri) {
        dbExecutor.execute {
            try {
                val name = queryDisplayName(uri).lowercase()
                if (name.endsWith(".doc")) {
                    throw IllegalArgumentException("구형 .doc 파일은 직접 읽지 않습니다. Word에서 .docx 또는 .txt로 저장한 뒤 가져오세요.")
                }
                val text = contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "파일을 열 수 없습니다" }
                    if (name.endsWith(".docx")) readDocxText(input.readBytes())
                    else decodeText(input.readBytes())
                }
                val parsed = parseGlossaryText(text)
                if (parsed.pairs.isEmpty()) throw IllegalArgumentException("인식 가능한 용어 쌍이 없습니다")
                savePairsOnWorker(parsed.pairs, parsed.skipped, fileName = queryDisplayName(uri))
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "파일 가져오기 실패: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun savePairs(pairs: List<Pair<String, String>>, skipped: Int) {
        dbExecutor.execute { savePairsOnWorker(pairs, skipped) }
    }

    private fun savePairsOnWorker(
        pairs: List<Pair<String, String>>,
        skipped: Int,
        fileName: String? = null
    ) {
        val unique = LinkedHashMap<String, String>()
        pairs.take(MAX_IMPORT_TERMS).forEach { (source, target) -> unique[source] = target }
        val now = System.currentTimeMillis()
        database.runInTransaction {
            unique.forEach { (source, target) ->
                database.glossaryDao().upsert(GlossaryEntity(source, target, now))
                database.translationDao().invalidateContaining(source)
            }
        }
        OpenAiTranslationProvider.clearMemoryCache()
        runOnUiThread {
            val prefix = fileName?.let { "$it: " }.orEmpty()
            val extra = if (skipped > 0) " · 형식 불명 ${skipped}줄 제외" else ""
            Toast.makeText(this, "$prefix${unique.size}개 용어를 저장했습니다$extra", Toast.LENGTH_LONG).show()
            refreshList()
        }
    }

    private data class ParsedGlossary(
        val pairs: List<Pair<String, String>>,
        val skipped: Int
    )

    private fun parseGlossaryText(text: String): ParsedGlossary {
        val pairs = mutableListOf<Pair<String, String>>()
        var skipped = 0
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return@forEach

            val pair = splitGlossaryLine(line)
            if (pair == null) {
                skipped += 1
            } else {
                val source = pair.first.trim().trim('"', '\'', '“', '”')
                val target = pair.second.trim().trim('"', '\'', '“', '”')
                if (source.isNotBlank() && target.isNotBlank() && source != target) {
                    pairs += source to target
                } else {
                    skipped += 1
                }
            }
        }
        return ParsedGlossary(pairs, skipped)
    }

    private fun splitGlossaryLine(line: String): Pair<String, String>? {
        val separators = listOf("\t", " → ", "→", " -> ", "=>", "=", ",")
        for (separator in separators) {
            val index = line.indexOf(separator)
            if (index > 0 && index + separator.length < line.length) {
                return line.substring(0, index) to line.substring(index + separator.length)
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun decodeText(bytes: ByteArray): String {
        val data = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes

        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            String(data, charset("Shift_JIS"))
        }
    }

    private fun readDocxText(bytes: ByteArray): String {
        require(bytes.size <= MAX_FILE_BYTES) { "파일이 너무 큽니다" }
        var xml: String? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
        }
        val document = xml ?: throw IllegalArgumentException("DOCX 본문을 찾지 못했습니다")
        return document
            .replace(Regex("</w:tc>"), "\t")
            .replace(Regex("</w:tr>"), "\n")
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:br[^>]*/>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    companion object {
        private const val MAX_IMPORT_TERMS = 5000
        private const val MAX_FILE_BYTES = 12 * 1024 * 1024
    }
}
