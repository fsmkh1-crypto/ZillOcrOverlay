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
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.SpeakerEntity
import kr.co.zillocr.overlay.db.SpeakerStyleEntity
import kr.co.zillocr.overlay.translation.OpenAiTranslationProvider
import java.util.concurrent.Executors

class LearningManagerActivity : ComponentActivity() {
    private val database by lazy { AppDatabase.get(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var summaryView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        refresh()
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "화자 · 학습 관리"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "사용자가 승인한 화자 이름, 화자별 말투 메모, OCR alias만 영구 학습 데이터로 사용합니다."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "화자 이름 추가 / 수정"
            setOnClickListener { showSpeakerEdit() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "화자 말투 메모 추가 / 수정"
            setOnClickListener { showStyleEdit() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "화자 삭제"
            setOnClickListener { showSpeakerDelete() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "OCR alias 삭제"
            setOnClickListener { showAliasDelete() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        summaryView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(14), 0, dp(18))
        }
        root.addView(summaryView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
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
        val source = EditText(this).apply { hint = "일본어 화자명 (화자 사전에 등록된 이름)" }
        val style = EditText(this).apply {
            hint = "예: 거친 반말. 짧고 퉁명스럽게. 존댓말 사용 안 함."
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

    private fun refresh() {
        executor.execute {
            val speakers = database.speakerDao().all()
            val styles = database.speakerStyleDao().all().associateBy { it.sourceName }
            val aliases = database.ocrAliasDao().all()
            val feedback = database.feedbackDao().recent(20)
            val body = buildString {
                append("확정 화자: ${speakers.size}개\n")
                speakers.take(150).forEach { speaker ->
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
            runOnUiThread { summaryView.text = body }
        }
    }
}
