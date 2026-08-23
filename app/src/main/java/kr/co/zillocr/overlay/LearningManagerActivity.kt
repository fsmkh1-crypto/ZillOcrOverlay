package kr.co.zillocr.overlay

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kr.co.zillocr.overlay.data.IgnoreListStore
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.SpeakerEntity
import kr.co.zillocr.overlay.db.SpeakerStyleEntity
import kr.co.zillocr.overlay.translation.OpenAiTranslationProvider
import java.util.concurrent.Executors

class LearningManagerActivity : ComponentActivity() {
    private val database by lazy { AppDatabase.get(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        executor.execute {
            val speakers = database.speakerDao().all()
            val styles = database.speakerStyleDao().all().associateBy { it.sourceName }
            val aliases = database.ocrAliasDao().all()
            val feedback = database.feedbackDao().recent(20)
            val speakerCandidates = OpenAiTranslationProvider.pendingSpeakerCandidates()
            val aliasCandidates = OpenAiTranslationProvider.pendingAliasCandidates()
            val ignored = IgnoreListStore.all(this)
            runOnUiThread {
                root.removeAllViews()
                buildHeader()
                buildCandidateInbox(speakerCandidates, aliasCandidates)
                buildIgnoreList(ignored)
                buildManualManagement()
                buildSummary(speakers, styles, aliases, feedback)
                addButton("닫기") { finish() }
            }
        }
    }

    private fun buildHeader() {
        addText("화자 · 학습 관리", 23f)
        addText(
            "후보는 플레이 중 알림을 띄우지 않고 여기에 모아둡니다. 승인한 항목만 영구 학습되고, 무시한 원문은 다시 후보로 올리지 않습니다.",
            13f
        )
    }

    private fun buildCandidateInbox(
        speakerCandidates: List<OpenAiTranslationProvider.PendingSpeakerCandidate>,
        aliasCandidates: List<OpenAiTranslationProvider.PendingAliasCandidate>
    ) {
        addSection("학습 후보함 · ${speakerCandidates.size + aliasCandidates.size}개")
        if (speakerCandidates.isEmpty() && aliasCandidates.isEmpty()) {
            addText("대기 중인 후보가 없습니다.", 14f)
            return
        }

        speakerCandidates.forEach { candidate ->
            val box = candidateBox(
                "화자 후보 · ${candidate.hitCount}회",
                "${candidate.source} → ${candidate.suggestedTarget}"
            )
            box.addView(actionRow(
                "승인" to { approveSpeaker(candidate.source, null) },
                "수정" to { showSpeakerCandidateEdit(candidate) },
                "무시" to { ignoreSpeaker(candidate.source) }
            ))
            root.addView(box, matchWrap())
        }

        aliasCandidates.forEach { candidate ->
            val box = candidateBox(
                "OCR 보정 후보 · ${candidate.hitCount}회",
                "${candidate.observed} → ${candidate.canonical}\n한국어 참고: ${candidate.target}"
            )
            box.addView(actionRow(
                "승인" to { approveAlias(candidate.observed, null) },
                "수정" to { showAliasCandidateEdit(candidate) },
                "무시" to { ignoreAlias(candidate.observed) }
            ))
            root.addView(box, matchWrap())
        }
    }

    private fun buildIgnoreList(ignored: List<String>) {
        addSection("무시 목록 · ${ignored.size}개")
        if (ignored.isEmpty()) {
            addText("무시한 항목이 없습니다.", 14f)
            return
        }
        ignored.forEach { key ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = IgnoreListStore.displayLabel(key)
                textSize = 13f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "무시 해제"
                isAllCaps = false
                setOnClickListener {
                    IgnoreListStore.remove(this@LearningManagerActivity, key)
                    refresh()
                }
            })
            root.addView(row, matchWrap())
        }
    }

    private fun buildManualManagement() {
        addSection("확정 학습 데이터 관리")
        addButton("화자 이름 추가 / 수정") { showSpeakerEdit() }
        addButton("화자 말투 메모 추가 / 수정") { showStyleEdit() }
        addButton("화자 삭제") { showSpeakerDelete() }
        addButton("OCR alias 삭제") { showAliasDelete() }
    }

    private fun buildSummary(
        speakers: List<SpeakerEntity>,
        styles: Map<String, SpeakerStyleEntity>,
        aliases: List<kr.co.zillocr.overlay.db.OcrAliasEntity>,
        feedback: List<kr.co.zillocr.overlay.db.FeedbackEntity>
    ) {
        addSection("현재 저장 상태")
        val body = buildString {
            append("확정 화자: ${speakers.size}개\n")
            speakers.take(100).forEach { speaker ->
                append("• ${speaker.sourceName} → ${speaker.targetName}")
                styles[speaker.sourceName]?.styleNote?.takeIf { it.isNotBlank() }?.let {
                    append("\n  말투: ").append(it)
                }
                append('\n')
            }
            append("\n승인 OCR alias: ${aliases.size}개\n")
            aliases.take(100).forEach { append("• ${it.observedText} → ${it.canonicalText}\n") }
            append("\n최근 피드백: ${feedback.size}개\n")
            feedback.take(20).forEach {
                append(if (it.rating > 0) "• 좋음 " else "• 수정/별로 ")
                append(it.sourceText.replace('\n', ' ').take(32)).append('\n')
            }
        }
        addText(body, 14f)
    }

    private fun approveSpeaker(source: String, target: String?) {
        executor.execute {
            val approved = OpenAiTranslationProvider.approveSpeakerCandidate(source, target)
            runOnUiThread {
                Toast.makeText(this, if (approved != null) "화자 후보를 승인했습니다" else "후보가 이미 변경되었습니다", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun approveAlias(observed: String, canonical: String?) {
        executor.execute {
            val approved = OpenAiTranslationProvider.approveAliasCandidate(observed, canonical)
            runOnUiThread {
                Toast.makeText(this, if (approved != null) "OCR 보정 후보를 승인했습니다" else "후보가 이미 변경되었습니다", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun ignoreSpeaker(source: String) {
        executor.execute {
            OpenAiTranslationProvider.ignoreSpeakerCandidate(source)
            runOnUiThread { refresh() }
        }
    }

    private fun ignoreAlias(observed: String) {
        executor.execute {
            OpenAiTranslationProvider.ignoreAliasCandidate(observed)
            runOnUiThread { refresh() }
        }
    }

    private fun showSpeakerCandidateEdit(candidate: OpenAiTranslationProvider.PendingSpeakerCandidate) {
        val input = EditText(this).apply {
            setText(candidate.suggestedTarget)
            selectAll()
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("화자 후보 수정 후 승인")
            .setMessage(candidate.source)
            .setView(input)
            .setPositiveButton("승인") { _, _ ->
                val target = input.text.toString().trim()
                if (target.isNotBlank()) approveSpeaker(candidate.source, target)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAliasCandidateEdit(candidate: OpenAiTranslationProvider.PendingAliasCandidate) {
        val input = EditText(this).apply {
            setText(candidate.canonical)
            selectAll()
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("OCR 보정 수정 후 승인")
            .setMessage("관측: ${candidate.observed}\n한국어 참고: ${candidate.target}")
            .setView(input)
            .setPositiveButton("승인") { _, _ ->
                val canonical = input.text.toString().trim()
                if (canonical.isNotBlank()) approveAlias(candidate.observed, canonical)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showSpeakerEdit() {
        val source = EditText(this).apply { hint = "일본어 화자명" }
        val target = EditText(this).apply { hint = "한국어 화자명" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(source)
            addView(target)
        }
        AlertDialog.Builder(this)
            .setTitle("화자 이름 추가 / 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val s = source.text.toString().trim()
                val t = target.text.toString().trim()
                if (s.isBlank() || t.isBlank()) return@setPositiveButton
                executor.execute {
                    database.speakerDao().upsert(SpeakerEntity(s, t, System.currentTimeMillis()))
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showStyleEdit() {
        val source = EditText(this).apply { hint = "일본어 화자명 (등록된 이름)" }
        val style = EditText(this).apply {
            hint = "예: 거친 반말. 짧고 퉁명스럽게."
            minLines = 3
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(source)
            addView(style)
        }
        AlertDialog.Builder(this)
            .setTitle("화자 말투 메모")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val s = source.text.toString().trim()
                val note = style.text.toString().trim()
                if (s.isBlank() || note.isBlank()) return@setPositiveButton
                executor.execute {
                    if (database.speakerDao().find(s) == null) {
                        runOnUiThread { Toast.makeText(this, "먼저 화자 이름을 등록하세요", Toast.LENGTH_SHORT).show() }
                    } else {
                        database.speakerStyleDao().upsert(SpeakerStyleEntity(s, note, System.currentTimeMillis()))
                        OpenAiTranslationProvider.clearMemoryCache()
                        runOnUiThread { refresh() }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showSpeakerDelete() {
        val input = EditText(this).apply { hint = "삭제할 일본어 화자명" }
        AlertDialog.Builder(this)
            .setTitle("화자 삭제")
            .setView(input)
            .setPositiveButton("삭제") { _, _ ->
                val s = input.text.toString().trim()
                if (s.isBlank()) return@setPositiveButton
                executor.execute {
                    database.speakerDao().delete(s)
                    database.speakerStyleDao().delete(s)
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAliasDelete() {
        val input = EditText(this).apply { hint = "삭제할 OCR 오인식 문자열" }
        AlertDialog.Builder(this)
            .setTitle("OCR alias 삭제")
            .setView(input)
            .setPositiveButton("삭제") { _, _ ->
                val s = input.text.toString().trim()
                if (s.isBlank()) return@setPositiveButton
                executor.execute {
                    database.ocrAliasDao().delete(s)
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun candidateBox(title: String, body: String): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(TextView(this@LearningManagerActivity).apply {
                text = title
                textSize = 15f
            }, matchWrap())
            addView(TextView(this@LearningManagerActivity).apply {
                text = body
                textSize = 13f
                setPadding(0, dp(4), 0, dp(4))
            }, matchWrap())
        }
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            actions.forEach { (label, action) ->
                addView(Button(this@LearningManagerActivity).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun addSection(text: String) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setPadding(0, dp(18), 0, dp(6))
        }, matchWrap())
    }

    private fun addText(text: String, size: Float) {
        val density = resources.displayMetrics.density
        root.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 0, 0, (8 * density).toInt())
        }, matchWrap())
    }

    private fun addButton(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, matchWrap())
    }

    private fun matchWrap(): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
