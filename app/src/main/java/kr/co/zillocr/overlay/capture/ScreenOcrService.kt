package kr.co.zillocr.overlay.capture

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.StaticLayout
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kr.co.zillocr.overlay.MainActivity
import kr.co.zillocr.overlay.R
import kr.co.zillocr.overlay.data.OverlaySettingsStore
import kr.co.zillocr.overlay.data.RegionStore
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.FeedbackEntity
import kr.co.zillocr.overlay.db.TranslationOverrideEntity
import kr.co.zillocr.overlay.overlay.RegionSelectionView
import kr.co.zillocr.overlay.translation.OpenAiTranslationProvider
import kr.co.zillocr.overlay.translation.TranslationCancelledException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class ScreenOcrService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var recognizer: TextRecognizer
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var ocrRegion: RectF? = null
    private var selectingRegion = false
    private var lastOcrAt = 0L
    @Volatile private var lastRecognizedText = ""
    private var candidateOcrText = ""
    private var candidateOcrHits = 0
    private val ocrInFlight = AtomicBoolean(false)

    private val recentJapanese = ArrayDeque<String>()
    private val translationExecutor = Executors.newSingleThreadExecutor()
    private val learningExecutor = Executors.newSingleThreadExecutor()
    private val translationLock = Any()
    private var translationRunning = false
    private var pendingTranslation: TranslationRequest? = null
    @Volatile private var currentProvider: OpenAiTranslationProvider? = null
    @Volatile private var lastTranslationRequest: TranslationRequest? = null

    private var resultContainer: LinearLayout? = null
    private var resultView: TextView? = null
    private var resultParams: WindowManager.LayoutParams? = null
    private var controlView: View? = null
    private var speakerApproveButton: Button? = null
    private var aliasApproveButton: Button? = null
    private var selectorView: RegionSelectionView? = null
    private var correctionDialog: AlertDialog? = null

    private var overlayTextSizeSp = 19f
    private var overlayAlpha = 191
    private var autoHeightEnabled = true
    private var speakerAlways = false
    private var lastDisplayedSpeakerTarget: String? = null
    @Volatile private var lastRawTranslation = ""
    @Volatile private var lastResultSpeakerSource: String? = null
    @Volatile private var lastResultSpeakerTarget: String? = null

    private data class TranslationRequest(
        val text: String,
        val context: List<String>,
        val apiKey: String,
        val model: String,
        val force: Boolean = false
    )

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                releaseProjectionResources(stopProjection = false)
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        captureThread = HandlerThread("zill-ocr-capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        ocrRegion = RegionStore.load(this)
        OverlaySettingsStore.load(this).also {
            autoHeightEnabled = it.autoHeight
            speakerAlways = it.speakerAlways
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> if (mediaProjection == null) {
                startForegroundForProjection()
                startProjectionFromIntent(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(translationLock) {
            currentProvider?.cancelInFlight()
            currentProvider = null
            pendingTranslation = null
        }
        correctionDialog?.dismiss()
        correctionDialog = null
        removeAllOverlays()
        releaseProjectionResources(stopProjection = true)
        OpenAiTranslationProvider.clearDialogueContext()
        recognizer.close()
        captureThread.quitSafely()
        translationExecutor.shutdownNow()
        learningExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startProjectionFromIntent(intent: Intent) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: run {
            stopSelf()
            return
        }
        updateScreenMetrics()
        try {
            val projection = projectionManager.getMediaProjection(resultCode, projectionData) ?: run {
                stopSelf(); return
            }
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, captureHandler)
            }
            virtualDisplay = projection.createVirtualDisplay(
                "ZillOcrCapture",
                screenWidth,
                screenHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
            mainHandler.post {
                showControlOverlay()
                showResultOverlay("준비됨 · PPSSPP에서 ‘영역’을 눌러 일본어 대화창을 지정하세요")
            }
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalStateException) {
            stopSelf()
        }
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.let {
                screenWidth = it.widthPixels
                screenHeight = it.heightPixels
            }
        }
        densityDpi = resources.configuration.densityDpi
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val region = ocrRegion ?: return
            if (selectingRegion) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrAt < OCR_INTERVAL_MS) return
            if (!ocrInFlight.compareAndSet(false, true)) return
            lastOcrAt = now
            val cropped = cropImage(image, region)
            if (cropped == null) {
                ocrInFlight.set(false)
                return
            }
            recognizer.process(InputImage.fromBitmap(cropped, 0))
                .addOnSuccessListener { result -> processOcrCandidate(normalizeText(result.text)) }
                .addOnFailureListener { error ->
                    mainHandler.post { showResultOverlay("OCR 오류: ${error.javaClass.simpleName}") }
                }
                .addOnCompleteListener {
                    cropped.recycle()
                    ocrInFlight.set(false)
                }
        } finally {
            image.close()
        }
    }

    private fun processOcrCandidate(text: String) {
        if (text.isBlank()) return
        val stable = lastRecognizedText
        if (stable.isNotBlank() && textSimilarity(stable, text) >= SAME_TEXT_SIMILARITY) {
            candidateOcrText = ""
            candidateOcrHits = 0
            return
        }
        if (candidateOcrText.isBlank() || textSimilarity(candidateOcrText, text) < CANDIDATE_SIMILARITY) {
            candidateOcrText = text
            candidateOcrHits = 1
            return
        }
        candidateOcrHits += 1
        if (candidateOcrHits < REQUIRED_STABLE_HITS) return
        val accepted = text
        candidateOcrText = ""
        candidateOcrHits = 0
        lastRecognizedText = accepted
        handleRecognizedText(accepted)
    }

    private fun textSimilarity(first: String, second: String): Double {
        val a = canonicalizeForComparison(first)
        val b = canonicalizeForComparison(second)
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshteinDistance(a, b).toDouble() / max(a.length, b.length).toDouble()
    }

    private fun canonicalizeForComparison(text: String): String = buildString(text.length) {
        text.forEach { if (!it.isWhitespace() && it !in OCR_IGNORED_PUNCTUATION) append(it) }
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (i in first.indices) {
            current[0] = i + 1
            for (j in second.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (first[i] == second[j]) 0 else 1
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private fun handleRecognizedText(text: String) {
        val previousContext = synchronized(recentJapanese) { recentJapanese.toList().takeLast(2) }
        synchronized(recentJapanese) {
            if (recentJapanese.lastOrNull() != text) {
                recentJapanese.addLast(text)
                while (recentJapanese.size > 3) recentJapanese.removeFirst()
            }
        }
        val settings = TranslationSettingsStore.load(this)
        if (!settings.enabled) {
            mainHandler.post { showResultOverlay(text) }
            return
        }
        if (settings.apiKey.isBlank()) {
            mainHandler.post { showResultOverlay("API 키를 앱에서 먼저 입력하세요\n\n$text") }
            return
        }
        val request = TranslationRequest(text, previousContext, settings.apiKey, settings.model)
        lastTranslationRequest = request
        enqueueTranslation(request)
    }

    private fun enqueueTranslation(request: TranslationRequest) {
        var shouldStart = false
        synchronized(translationLock) {
            if (translationRunning) currentProvider?.cancelInFlight()
            pendingTranslation = request
            if (!translationRunning) {
                translationRunning = true
                shouldStart = true
            }
        }
        if (shouldStart) translationExecutor.execute { drainTranslationQueue() }
    }

    private fun drainTranslationQueue() {
        while (!Thread.currentThread().isInterrupted) {
            val pair = synchronized(translationLock) {
                val request = pendingTranslation
                pendingTranslation = null
                if (request == null) {
                    translationRunning = false
                    currentProvider = null
                    null
                } else {
                    val provider = OpenAiTranslationProvider(request.apiKey, request.model)
                    currentProvider = provider
                    request to provider
                }
            } ?: return
            val request = pair.first
            val provider = pair.second
            try {
                val translated = if (request.force) {
                    provider.translateForced(request.text, request.context)
                } else {
                    provider.translate(request.text, request.context)
                }
                val speakerSource = provider.lastSpeakerSource
                val speakerTarget = provider.lastSpeakerTarget
                val explicit = provider.lastSpeakerExplicit
                val candidate = provider.lastSpeakerWasCandidate
                lastRawTranslation = translated
                lastResultSpeakerSource = speakerSource
                lastResultSpeakerTarget = speakerTarget
                mainHandler.post {
                    if (lastRecognizedText == request.text) {
                        showResultOverlay(formatTranslatedDisplay(translated, speakerTarget, explicit, candidate))
                        showPendingLearningHintIfNeeded()
                    }
                }
            } catch (_: TranslationCancelledException) {
                // 최신 안정화 대사로 교체된 정상 취소.
            } catch (error: Exception) {
                mainHandler.post {
                    if (lastRecognizedText == request.text) {
                        val detail = error.message?.take(160).orEmpty()
                        showResultOverlay(if (detail.isBlank()) "번역 오류: ${error.javaClass.simpleName}" else "번역 오류: $detail")
                    }
                }
            } finally {
                synchronized(translationLock) {
                    if (currentProvider === provider) currentProvider = null
                }
            }
        }
    }

    private fun formatTranslatedDisplay(
        translated: String,
        speakerTarget: String?,
        explicitSpeaker: Boolean,
        candidate: Boolean
    ): String {
        if (speakerTarget.isNullOrBlank()) {
            lastDisplayedSpeakerTarget = null
            return translated
        }
        val showName = candidate || speakerAlways || speakerTarget != lastDisplayedSpeakerTarget || explicitSpeaker && lastDisplayedSpeakerTarget == null
        lastDisplayedSpeakerTarget = speakerTarget
        return when {
            candidate -> "[화자 후보] $speakerTarget\n$translated"
            showName -> "$speakerTarget\n$translated"
            else -> translated
        }
    }

    private fun showPendingLearningHintIfNeeded() {
        updatePendingLearningButtons()
        val speaker = OpenAiTranslationProvider.peekPendingSpeakerCandidate()
        val alias = OpenAiTranslationProvider.peekPendingAliasCandidate()
        if (speaker != null || alias != null) {
            val message = buildString {
                speaker?.let { append("화자 후보 ${it.source} → ${it.suggestedTarget} · ‘화자등록’으로 승인") }
                if (speaker != null && alias != null) append("\n")
                alias?.let { append("OCR 후보 ${it.observed} → ${it.canonical} · ‘용어등록’으로 승인") }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePendingLearningButtons() {
        val speakerPending = OpenAiTranslationProvider.peekPendingSpeakerCandidate() != null
        val aliasPending = OpenAiTranslationProvider.peekPendingAliasCandidate() != null
        speakerApproveButton?.apply {
            isEnabled = speakerPending
            alpha = if (speakerPending) 1f else 0.45f
            text = if (speakerPending) "화자등록 (1)" else "화자등록"
        }
        aliasApproveButton?.apply {
            isEnabled = aliasPending
            alpha = if (aliasPending) 1f else 0.45f
            text = if (aliasPending) "용어등록 (1)" else "용어등록"
        }
    }

    private fun retryLastTranslation() {
        val previous = lastTranslationRequest ?: return
        val forced = previous.copy(force = true)
        lastTranslationRequest = forced
        showResultOverlay("다시 번역 중…")
        enqueueTranslation(forced)
    }

    private fun approveSpeakerCandidate() {
        learningExecutor.execute {
            val approved = OpenAiTranslationProvider.approvePendingSpeaker()
            mainHandler.post {
                updatePendingLearningButtons()
                Toast.makeText(
                    this,
                    approved?.let { "화자 등록: ${it.source} → ${it.suggestedTarget}" } ?: "등록할 화자 후보가 없습니다",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun approveAliasCandidate() {
        learningExecutor.execute {
            val approved = OpenAiTranslationProvider.approvePendingAlias()
            mainHandler.post {
                updatePendingLearningButtons()
                Toast.makeText(
                    this,
                    approved?.let { "OCR alias 등록: ${it.observed} → ${it.canonical}" } ?: "등록할 OCR 후보가 없습니다",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun recordPositiveFeedback() {
        val request = lastTranslationRequest ?: return
        if (lastRawTranslation.isBlank()) return
        val speaker = lastResultSpeakerSource
        learningExecutor.execute {
            AppDatabase.get(this).feedbackDao().insert(
                FeedbackEntity(
                    sourceText = request.text,
                    model = request.model,
                    rating = 1,
                    category = "good",
                    correctedText = null,
                    speakerSource = speaker,
                    createdAt = System.currentTimeMillis()
                )
            )
            mainHandler.post { Toast.makeText(this, "좋은 번역으로 기록했습니다", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showCorrectionDialog() {
        val request = lastTranslationRequest ?: return
        val current = lastRawTranslation
        if (current.isBlank()) return
        val input = EditText(this).apply {
            setText(current)
            minLines = 3
            gravity = Gravity.TOP
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("번역 직접 수정")
            .setMessage("저장한 수정본은 같은 원문·모델에서 AI보다 먼저 사용됩니다.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val corrected = input.text.toString().trim()
                if (corrected.isNotBlank()) saveCorrection(request, corrected)
            }
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
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
    }

    private fun saveCorrection(request: TranslationRequest, corrected: String) {
        val speakerSource = lastResultSpeakerSource
        learningExecutor.execute {
            val db = AppDatabase.get(this)
            val now = System.currentTimeMillis()
            db.translationOverrideDao().upsert(
                TranslationOverrideEntity(request.text, request.model, corrected, speakerSource, now)
            )
            db.feedbackDao().insert(
                FeedbackEntity(
                    sourceText = request.text,
                    model = request.model,
                    rating = -1,
                    category = "manual_correction",
                    correctedText = corrected,
                    speakerSource = speakerSource,
                    createdAt = now
                )
            )
            db.translationDao().invalidateContaining(request.text)
            OpenAiTranslationProvider.clearMemoryCache()
            lastRawTranslation = corrected
            mainHandler.post {
                showResultOverlay(formatTranslatedDisplay(corrected, lastResultSpeakerTarget, true, false))
                Toast.makeText(this, "수정 번역을 저장했습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleAutoHeight() {
        autoHeightEnabled = !autoHeightEnabled
        OverlaySettingsStore.saveAutoHeight(this, autoHeightEnabled)
        Toast.makeText(this, if (autoHeightEnabled) "자동 높이 ON" else "자동 높이 OFF", Toast.LENGTH_SHORT).show()
        if (autoHeightEnabled) applyAutoHeight()
    }

    private fun toggleSpeakerDisplayMode() {
        speakerAlways = !speakerAlways
        OverlaySettingsStore.saveSpeakerAlways(this, speakerAlways)
        lastDisplayedSpeakerTarget = null
        Toast.makeText(this, if (speakerAlways) "화자명: 항상 표시" else "화자명: 바뀔 때만 표시", Toast.LENGTH_SHORT).show()
    }

    private fun cropImage(image: Image, normalizedRegion: RectF): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * screenWidth
        val paddedWidth = screenWidth + rowPadding / pixelStride
        val fullBitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        fullBitmap.copyPixelsFromBuffer(buffer)
        val left = (normalizedRegion.left * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val top = (normalizedRegion.top * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        val right = (normalizedRegion.right * screenWidth).toInt().coerceIn(left + 1, screenWidth)
        val bottom = (normalizedRegion.bottom * screenHeight).toInt().coerceIn(top + 1, screenHeight)
        return try {
            Bitmap.createBitmap(fullBitmap, left, top, right - left, bottom - top)
        } finally {
            fullBitmap.recycle()
        }
    }

    private fun normalizeText(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()

    private fun showControlOverlay() {
        if (controlView != null) return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setBackgroundColor(0x77111111)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val learningRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun actionButton(label: String, widthDp: Int = 52, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(3), 0, dp(3), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(48)).apply {
                marginStart = dp(2)
                bottomMargin = dp(2)
            }
        }

        val menu = actionButton("☰", 48) {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updatePendingLearningButtons()
        }

        primaryRow.addView(actionButton("영역", 60) { beginRegionSelection() })
        primaryRow.addView(actionButton("A−", 48) { changeOverlayTextSize(-1f) })
        primaryRow.addView(actionButton("A+", 48) { changeOverlayTextSize(1f) })
        primaryRow.addView(actionButton("투명", 52) { cycleOverlayAlpha() })
        primaryRow.addView(actionButton("높이", 52) { toggleAutoHeight() })
        primaryRow.addView(actionButton("이름", 52) { toggleSpeakerDisplayMode() })
        primaryRow.addView(actionButton("다시", 52) { retryLastTranslation() })

        learningRow.addView(actionButton("좋음", 52) { recordPositiveFeedback() })
        learningRow.addView(actionButton("수정", 60) { showCorrectionDialog() })
        speakerApproveButton = actionButton("화자등록", 78) { approveSpeakerCandidate() }.also {
            learningRow.addView(it)
        }
        aliasApproveButton = actionButton("용어등록", 78) { approveAliasCandidate() }.also {
            learningRow.addView(it)
        }
        learningRow.addView(actionButton("숨김", 52) { toggleResultVisibility() })
        learningRow.addView(actionButton("종료", 52) { stopSelf() })

        controls.addView(primaryRow)
        controls.addView(learningRow)
        root.addView(menu)
        root.addView(controls)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6)
            y = dp(40)
        }
        windowManager.addView(root, params)
        controlView = root
        updatePendingLearningButtons()
    }

    private fun toggleResultVisibility() {
        resultContainer?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    }

    private fun showResultOverlay(text: String) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        if (resultContainer == null) {
            val saved = OverlaySettingsStore.load(this)
            overlayTextSizeSp = saved.textSizeSp
            overlayAlpha = saved.backgroundAlpha
            autoHeightEnabled = saved.autoHeight
            speakerAlways = saved.speakerAlways
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(overlayAlpha, 0, 0, 0))
                setPadding(dp(6), dp(3), dp(6), dp(6))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dragHandle = TextView(this).apply {
                this.text = "≡ 이동"
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 11f
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            val resizeHandle = TextView(this).apply {
                this.text = "↘ 크기"
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 11f
                gravity = Gravity.END
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            header.addView(dragHandle, LinearLayout.LayoutParams(0, dp(26), 1f))
            header.addView(resizeHandle, LinearLayout.LayoutParams(dp(86), dp(26)))
            val textView = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = overlayTextSizeSp
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(6))
                maxLines = 20
                ellipsize = TextUtils.TruncateAt.END
            }
            container.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, dp(26))
            container.addView(textView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            val width = (screenWidth * saved.widthRatio).toInt().coerceIn(dp(180), (screenWidth * 0.995f).toInt())
            val height = (screenHeight * saved.heightRatio).toInt().coerceIn(dp(80), (screenHeight * 0.92f).toInt())
            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (saved.xRatio * screenWidth).toInt()
                y = (saved.yRatio * screenHeight).toInt()
            }
            clampResultGeometry(params)
            attachDragHandler(dragHandle, container, params)
            attachResizeHandler(resizeHandle, container, params)
            windowManager.addView(container, params)
            resultContainer = container
            resultView = textView
            resultParams = params
        }
        resultView?.let { if (it.text?.toString() != text) it.text = text }
        if (autoHeightEnabled) mainHandler.post { applyAutoHeight() }
    }

    private fun applyAutoHeight() {
        if (!autoHeightEnabled) return
        val view = resultView ?: return
        val container = resultContainer ?: return
        val params = resultParams ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val availableWidth = (params.width - dp(28)).coerceAtLeast(dp(120))
        val layout = StaticLayout.Builder.obtain(view.text ?: "", 0, view.text?.length ?: 0, view.paint, availableWidth)
            .setIncludePad(true)
            .setMaxLines(20)
            .build()
        val desired = (layout.height + dp(26) + dp(22)).coerceIn(dp(80), (screenHeight * 0.50f).toInt())
        if (params.height != desired) {
            params.height = desired
            clampResultGeometry(params)
            runCatching { windowManager.updateViewLayout(container, params) }
        }
    }

    private fun attachDragHandler(handle: View, container: View, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY; startX = params.x; startY = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downRawX).toInt()
                    params.y = startY + (event.rawY - downRawY).toInt()
                    clampResultGeometry(params)
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { saveOverlayGeometry(params); true }
                else -> false
            }
        }
    }

    private fun attachResizeHandler(handle: View, container: View, params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val minWidth = (180 * density).toInt()
        val minHeight = (80 * density).toInt()
        val maxWidth = (screenWidth * 0.995f).toInt()
        val maxHeight = (screenHeight * 0.92f).toInt()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startWidth = 0
        var startHeight = 0
        var resizing = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    resizing = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!resizing && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >= touchSlop) {
                        resizing = true
                        if (autoHeightEnabled) {
                            autoHeightEnabled = false
                            OverlaySettingsStore.saveAutoHeight(this, false)
                            Toast.makeText(this, "직접 크기 조절 · 자동 높이 OFF", Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (resizing) {
                        params.width = (startWidth + dx.toInt()).coerceIn(minWidth, maxWidth)
                        params.height = (startHeight + dy.toInt()).coerceIn(minHeight, maxHeight)
                        clampResultGeometry(params)
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizing) saveOverlayGeometry(params)
                    true
                }
                else -> false
            }
        }
    }

    private fun clampResultGeometry(params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val safety = (48 * density).toInt().coerceAtMost(minOf(screenWidth, screenHeight) / 3)
        params.width = params.width.coerceIn((180 * density).toInt(), (screenWidth * 0.995f).toInt())
        params.height = params.height.coerceIn((80 * density).toInt(), (screenHeight * 0.92f).toInt())
        params.x = params.x.coerceIn(safety - params.width, screenWidth - safety)
        params.y = params.y.coerceIn(safety - params.height, screenHeight - safety)
    }

    private fun saveOverlayGeometry(params: WindowManager.LayoutParams) {
        OverlaySettingsStore.saveGeometry(
            this,
            params.x.toFloat() / screenWidth.coerceAtLeast(1),
            params.y.toFloat() / screenHeight.coerceAtLeast(1),
            params.width.toFloat() / screenWidth.coerceAtLeast(1),
            params.height.toFloat() / screenHeight.coerceAtLeast(1)
        )
    }

    private fun changeOverlayTextSize(delta: Float) {
        overlayTextSizeSp = (overlayTextSizeSp + delta).coerceIn(13f, 30f)
        resultView?.textSize = overlayTextSizeSp
        OverlaySettingsStore.saveTextSize(this, overlayTextSizeSp)
        if (autoHeightEnabled) applyAutoHeight()
    }

    private fun cycleOverlayAlpha() {
        val index = ALPHA_LEVELS.indices.minByOrNull { abs(ALPHA_LEVELS[it] - overlayAlpha) } ?: 0
        overlayAlpha = ALPHA_LEVELS[(index + 1) % ALPHA_LEVELS.size]
        resultContainer?.setBackgroundColor(Color.argb(overlayAlpha, 0, 0, 0))
        OverlaySettingsStore.saveBackgroundAlpha(this, overlayAlpha)
    }

    private fun beginRegionSelection() {
        if (selectorView != null) return
        selectingRegion = true
        val selector = RegionSelectionView(
            context = this,
            onSelected = { selected ->
                ocrRegion = selected
                RegionStore.save(this, selected)
                resetOcrStabilityState()
                synchronized(recentJapanese) { recentJapanese.clear() }
                synchronized(translationLock) {
                    pendingTranslation = null
                    currentProvider?.cancelInFlight()
                }
                lastTranslationRequest = null
                lastRawTranslation = ""
                lastResultSpeakerSource = null
                lastResultSpeakerTarget = null
                lastDisplayedSpeakerTarget = null
                OpenAiTranslationProvider.clearDialogueContext()
                updatePendingLearningButtons()
                endRegionSelection()
                showResultOverlay("영역 지정 완료 · 일본어를 인식 중입니다")
            },
            onCancelled = { endRegionSelection() }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(selector, params)
        selectorView = selector
    }

    private fun resetOcrStabilityState() {
        lastRecognizedText = ""
        candidateOcrText = ""
        candidateOcrHits = 0
    }

    private fun endRegionSelection() {
        selectorView?.let { runCatching { windowManager.removeView(it) } }
        selectorView = null
        selectingRegion = false
    }

    private fun removeAllOverlays() {
        selectorView?.let { runCatching { windowManager.removeView(it) } }
        resultContainer?.let { runCatching { windowManager.removeView(it) } }
        controlView?.let { runCatching { windowManager.removeView(it) } }
        selectorView = null
        resultContainer = null
        resultView = null
        resultParams = null
        controlView = null
        speakerApproveButton = null
        aliasApproveButton = null
    }

    private fun releaseProjectionResources(stopProjection: Boolean) {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (stopProjection) runCatching { projection.stop() }
        }
        mediaProjection = null
    }

    private fun startForegroundForProjection() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "화면 OCR/번역", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ScreenOcrService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ocr)
            .setContentTitle("질올 실시간 번역 실행 중")
            .setContentText("PPSSPP 지정 영역을 OCR하고 번역합니다")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_ocr), "중지", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "kr.co.zillocr.overlay.action.START"
        const val ACTION_STOP = "kr.co.zillocr.overlay.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val OCR_INTERVAL_MS = 320L
        private const val REQUIRED_STABLE_HITS = 2
        private const val SAME_TEXT_SIMILARITY = 0.92
        private const val CANDIDATE_SIMILARITY = 0.90
        private const val OCR_IGNORED_PUNCTUATION = "、。,.!！?？:：;；'\"「」『』()（）[]【】<>＜＞・…―ー-~～"
        private val ALPHA_LEVELS = intArrayOf(255, 191, 128, 64, 26, 0)
        private const val NOTIFICATION_CHANNEL_ID = "zill_ocr_capture"
        private const val NOTIFICATION_ID = 1001
    }
}
