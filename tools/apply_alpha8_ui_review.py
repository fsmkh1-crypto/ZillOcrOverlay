from pathlib import Path

path = Path("app/src/main/java/kr/co/zillocr/overlay/capture/ScreenOcrService.kt")
s = path.read_text(encoding="utf-8")

if "private var detailsPanel: LinearLayout? = null" in s:
    print("alpha8 final control UI already applied")
    raise SystemExit(0)

s = s.replace("import android.widget.EditText\nimport android.widget.LinearLayout\n", "import android.widget.EditText\nimport android.widget.FrameLayout\nimport android.widget.LinearLayout\n")
s = s.replace("import kr.co.zillocr.overlay.MainActivity\n", "import kr.co.zillocr.overlay.LearningManagerActivity\nimport kr.co.zillocr.overlay.MainActivity\n")
s = s.replace("import kr.co.zillocr.overlay.db.FeedbackEntity\n", "import kr.co.zillocr.overlay.db.FeedbackEntity\nimport kr.co.zillocr.overlay.db.OcrAliasEntity\nimport kr.co.zillocr.overlay.db.SpeakerEntity\n")

s = s.replace(
'''    private var controlView: View? = null
    private var speakerApproveButton: Button? = null
    private var aliasApproveButton: Button? = null
    private var selectorView: RegionSelectionView? = null
    private var correctionDialog: AlertDialog? = null
''',
'''    private var controlView: View? = null
    private var selectorView: RegionSelectionView? = null
    private var correctionDialog: AlertDialog? = null
    private var detailsPanel: LinearLayout? = null
    private var primaryActions: LinearLayout? = null
    private var gearBadgeView: TextView? = null
    private var speakerApprovalButton: Button? = null
    private var aliasApprovalButton: Button? = null
    private var autoHeightButton: Button? = null
    private var speakerModeButton: Button? = null
    private var alphaButton: Button? = null
''')

start = s.index("    private fun showPendingLearningHintIfNeeded() {")
end = s.index("    private fun recordPositiveFeedback() {", start)
s = s[:start] + r'''    private fun showPendingLearningHintIfNeeded() {
        refreshLearningControls()
        val speaker = OpenAiTranslationProvider.peekPendingSpeakerCandidate()
        val alias = OpenAiTranslationProvider.peekPendingAliasCandidate()
        if (speaker != null || alias != null) {
            val message = buildString {
                speaker?.let { append("화자 후보 ${it.source} → ${it.suggestedTarget} · ⚙에서 확인") }
                if (speaker != null && alias != null) append("\n")
                alias?.let { append("OCR 후보 ${it.observed} → ${it.canonical} · ⚙에서 확인") }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun retryLastTranslation() {
        val previous = lastTranslationRequest ?: return
        val forced = previous.copy(force = true)
        lastTranslationRequest = forced
        showResultOverlay("다시 번역 중…")
        enqueueTranslation(forced)
    }

    private fun requestSpeakerCandidateApproval() {
        val candidate = OpenAiTranslationProvider.peekPendingSpeakerCandidate()
        if (candidate == null) {
            Toast.makeText(this, "승인 대기 중인 화자 후보가 없습니다", Toast.LENGTH_SHORT).show()
            refreshLearningControls()
            return
        }
        val input = EditText(this).apply {
            setText(candidate.suggestedTarget)
            selectAll()
            maxLines = 1
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("화자 후보 등록")
            .setMessage("${candidate.source} → 아래 한국어 이름으로 등록합니다. 필요하면 수정하세요.")
            .setView(input)
            .setPositiveButton("등록") { _, _ ->
                val target = input.text.toString().trim()
                if (target.isNotBlank()) approveSpeakerCandidate(target)
            }
            .setNegativeButton("취소", null)
            .create()
        showOverlayDialog(dialog)
    }

    private fun approveSpeakerCandidate(target: String) {
        learningExecutor.execute {
            val approved = OpenAiTranslationProvider.approvePendingSpeaker()
            if (approved != null && target != approved.suggestedTarget) {
                AppDatabase.get(this).speakerDao().upsert(
                    SpeakerEntity(approved.source, target, System.currentTimeMillis())
                )
                OpenAiTranslationProvider.clearMemoryCache()
            }
            mainHandler.post {
                Toast.makeText(
                    this,
                    approved?.let { "화자 등록: ${it.source} → $target" } ?: "화자 후보가 이미 변경되었습니다",
                    Toast.LENGTH_SHORT
                ).show()
                refreshLearningControls()
            }
        }
    }

    private fun requestAliasCandidateApproval() {
        val candidate = OpenAiTranslationProvider.peekPendingAliasCandidate()
        if (candidate == null) {
            Toast.makeText(this, "승인 대기 중인 용어 후보가 없습니다", Toast.LENGTH_SHORT).show()
            refreshLearningControls()
            return
        }
        val input = EditText(this).apply {
            setText(candidate.canonical)
            selectAll()
            maxLines = 1
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("OCR 용어 후보 등록")
            .setMessage("${candidate.observed} → 아래 원문으로 보정합니다. 필요하면 수정하세요.\n한국어 표기 참고: ${candidate.target}")
            .setView(input)
            .setPositiveButton("등록") { _, _ ->
                val canonical = input.text.toString().trim()
                if (canonical.isNotBlank()) approveAliasCandidate(canonical)
            }
            .setNegativeButton("취소", null)
            .create()
        showOverlayDialog(dialog)
    }

    private fun approveAliasCandidate(canonical: String) {
        learningExecutor.execute {
            val approved = OpenAiTranslationProvider.approvePendingAlias()
            if (approved != null && canonical != approved.canonical) {
                AppDatabase.get(this).ocrAliasDao().upsert(
                    OcrAliasEntity(approved.observed, canonical, System.currentTimeMillis())
                )
                OpenAiTranslationProvider.clearMemoryCache()
            }
            mainHandler.post {
                Toast.makeText(
                    this,
                    approved?.let { "OCR alias 등록: ${it.observed} → $canonical" } ?: "용어 후보가 이미 변경되었습니다",
                    Toast.LENGTH_SHORT
                ).show()
                refreshLearningControls()
            }
        }
    }

''' + s[end:]

s = s.replace(
'''        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnShowListener {
            dialog.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.35f }
            }
        }
        dialog.setOnDismissListener { if (correctionDialog === dialog) correctionDialog = null }
        correctionDialog = dialog
        dialog.show()
''',
'''        dialog.setOnDismissListener { if (correctionDialog === dialog) correctionDialog = null }
        correctionDialog = dialog
        showOverlayDialog(dialog)
''')

s = s.replace(
'''        Toast.makeText(this, if (autoHeightEnabled) "자동 높이 ON" else "자동 높이 OFF", Toast.LENGTH_SHORT).show()
        if (autoHeightEnabled) applyAutoHeight()
''',
'''        Toast.makeText(this, if (autoHeightEnabled) "자동 높이 ON" else "자동 높이 OFF", Toast.LENGTH_SHORT).show()
        updateControlStateLabels()
        if (autoHeightEnabled) applyAutoHeight()
''')
s = s.replace(
'''        lastDisplayedSpeakerTarget = null
        Toast.makeText(this, if (speakerAlways) "화자명: 항상 표시" else "화자명: 바뀔 때만 표시", Toast.LENGTH_SHORT).show()
''',
'''        lastDisplayedSpeakerTarget = null
        Toast.makeText(this, if (speakerAlways) "화자명: 항상 표시" else "화자명: 바뀔 때만 표시", Toast.LENGTH_SHORT).show()
        updateControlStateLabels()
''')

start = s.index("    private fun showControlOverlay() {")
end = s.index("    private fun toggleResultVisibility() {", start)
s = s[:start] + r'''    private fun showControlOverlay() {
        if (controlView != null) return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val primaryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99111111.toInt())
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        primaryActions = actions

        fun primaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
        }

        val menu = primaryButton("☰") {
            val nowVisible = actions.visibility == View.VISIBLE
            actions.visibility = if (nowVisible) View.GONE else View.VISIBLE
            if (nowVisible) detailsPanel?.visibility = View.GONE
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        primaryBar.addView(menu)
        actions.addView(primaryButton("숨") { toggleResultVisibility() })
        actions.addView(primaryButton("재") { retryLastTranslation() })

        val gearFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) }
        }
        val gear = Button(this).apply {
            text = "⚙"
            textSize = 15f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                detailsPanel?.let { panel ->
                    panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    if (panel.visibility == View.VISIBLE) {
                        updateControlStateLabels()
                        refreshLearningControls()
                    }
                }
            }
        }
        gearFrame.addView(gear, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.START))
        val badge = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(0xFFFFB648.toInt())
            visibility = View.GONE
        }
        gearFrame.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        gearBadgeView = badge
        actions.addView(gearFrame)
        actions.addView(primaryButton("■") { stopSelf() })
        primaryBar.addView(actions)
        root.addView(primaryBar)

        val panelWidth = minOf(dp(240), (screenWidth - dp(16)).coerceAtLeast(dp(190)))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF21C1C22.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        detailsPanel = panel

        fun groupLabel(label: String, learning: Boolean = false): TextView = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(if (learning) 0xFFFFC96B.toInt() else 0xFFAAAAAA.toInt())
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        fun panelButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(3) }
        }
        fun addTwo(left: Button, right: Button) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            left.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(2); topMargin = dp(3) }
            right.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(2); topMargin = dp(3) }
            row.addView(left)
            row.addView(right)
            panel.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        panel.addView(groupLabel("표시 설정"))
        addTwo(panelButton("A− 작게") { changeOverlayTextSize(-1f) }, panelButton("A+ 크게") { changeOverlayTextSize(1f) })
        val alphaState = panelButton("투명도") { cycleOverlayAlpha() }
        alphaButton = alphaState
        val autoState = panelButton("자동높이") { toggleAutoHeight() }
        autoHeightButton = autoState
        addTwo(alphaState, autoState)
        val speakerState = panelButton("화자명 표시") { toggleSpeakerDisplayMode() }
        speakerModeButton = speakerState
        panel.addView(speakerState)

        val learningGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x553A2F18)
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        learningGroup.addView(groupLabel("학습 승인 · DB에 저장됨", learning = true))
        val speakerApprove = panelButton("화자 후보 승인") { requestSpeakerCandidateApproval() }
        val aliasApprove = panelButton("용어 후보 승인") { requestAliasCandidateApproval() }
        speakerApprovalButton = speakerApprove
        aliasApprovalButton = aliasApprove
        learningGroup.addView(speakerApprove)
        learningGroup.addView(aliasApprove)
        learningGroup.addView(panelButton("이 번역 좋아요") { recordPositiveFeedback() })
        learningGroup.addView(panelButton("번역 직접 수정") { showCorrectionDialog() })
        panel.addView(learningGroup, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        panel.addView(groupLabel("세션"))
        panel.addView(panelButton("OCR 영역 다시 지정") { beginRegionSelection(); panel.visibility = View.GONE })
        panel.addView(panelButton("화자 · 학습 관리 열기") { openLearningManager() })
        root.addView(panel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6)
            y = dp(40)
        }
        windowManager.addView(root, params)
        controlView = root
        updateControlStateLabels()
        refreshLearningControls()
    }

    private fun updateControlStateLabels() {
        autoHeightButton?.text = if (autoHeightEnabled) "자동높이 ON" else "자동높이 OFF"
        speakerModeButton?.text = if (speakerAlways) "화자명: 항상 표시" else "화자명: 변경 시"
        alphaButton?.text = "투명도 ${backgroundOpacityPercent()}%"
    }

    private fun backgroundOpacityPercent(): Int = when (overlayAlpha) {
        255 -> 100
        191 -> 75
        128 -> 50
        64 -> 25
        26 -> 10
        0 -> 0
        else -> (overlayAlpha * 100f / 255f).toInt()
    }

    private fun refreshLearningControls() {
        val speakerCount = if (OpenAiTranslationProvider.peekPendingSpeakerCandidate() != null) 1 else 0
        val aliasCount = if (OpenAiTranslationProvider.peekPendingAliasCandidate() != null) 1 else 0
        val total = speakerCount + aliasCount
        gearBadgeView?.apply {
            text = total.toString()
            visibility = if (total > 0) View.VISIBLE else View.GONE
        }
        speakerApprovalButton?.apply {
            text = "화자 후보 승인 ($speakerCount)"
            isEnabled = speakerCount > 0
            alpha = if (isEnabled) 1f else 0.45f
        }
        aliasApprovalButton?.apply {
            text = "용어 후보 승인 ($aliasCount)"
            isEnabled = aliasCount > 0
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun openLearningManager() {
        startActivity(Intent(this, LearningManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showOverlayDialog(dialog: AlertDialog) {
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.35f }
            }
        }
        dialog.show()
    }

''' + s[end:]

s = s.replace("        OverlaySettingsStore.saveBackgroundAlpha(this, overlayAlpha)\n    }\n\n    private fun beginRegionSelection()", "        OverlaySettingsStore.saveBackgroundAlpha(this, overlayAlpha)\n        updateControlStateLabels()\n    }\n\n    private fun beginRegionSelection()")
s = s.replace("                OpenAiTranslationProvider.clearDialogueContext()\n                updatePendingLearningButtons()\n", "                OpenAiTranslationProvider.clearDialogueContext()\n                refreshLearningControls()\n")
s = s.replace(
'''        resultParams = null
        controlView = null
        speakerApproveButton = null
        aliasApproveButton = null
''',
'''        resultParams = null
        controlView = null
        detailsPanel = null
        primaryActions = null
        gearBadgeView = null
        speakerApprovalButton = null
        aliasApprovalButton = null
        autoHeightButton = null
        speakerModeButton = null
        alphaButton = null
''')

path.write_text(s, encoding="utf-8")
print("applied final alpha8 control UI")
