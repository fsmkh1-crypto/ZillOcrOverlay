package kr.co.zillocr.overlay.capture

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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kr.co.zillocr.overlay.MainActivity
import kr.co.zillocr.overlay.R
import kr.co.zillocr.overlay.data.RegionStore
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.overlay.RegionSelectionView
import kr.co.zillocr.overlay.translation.OpenAiTranslationProvider
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    private val translationLock = Any()
    private var translationRunning = false
    private var pendingTranslation: TranslationRequest? = null

    private var resultView: TextView? = null
    private var resultParams: WindowManager.LayoutParams? = null
    private var controlView: View? = null
    private var selectorView: RegionSelectionView? = null

    private data class TranslationRequest(
        val text: String,
        val context: List<String>,
        val apiKey: String,
        val model: String
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (mediaProjection == null) {
                    startForegroundForProjection()
                    startProjectionFromIntent(intent)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeAllOverlays()
        releaseProjectionResources(stopProjection = true)
        recognizer.close()
        captureThread.quitSafely()
        translationExecutor.shutdownNow()
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
                stopSelf()
                return
            }
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            ).apply {
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
                positionResultOutsideRegion()
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
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
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

            val inputImage = InputImage.fromBitmap(cropped, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val normalized = normalizeText(result.text)
                    processOcrCandidate(normalized)
                }
                .addOnFailureListener { error ->
                    mainHandler.post {
                        showResultOverlay("OCR 오류: ${error.javaClass.simpleName}")
                    }
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

        val maxLength = max(a.length, b.length)
        val distance = levenshteinDistance(a, b)
        return 1.0 - distance.toDouble() / maxLength.toDouble()
    }

    private fun canonicalizeForComparison(text: String): String = buildString(text.length) {
        text.forEach { char ->
            if (!char.isWhitespace() && char !in OCR_IGNORED_PUNCTUATION) append(char)
        }
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)

        for (i in first.indices) {
            current[0] = i + 1
            for (j in second.indices) {
                val substitutionCost = if (first[i] == second[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private fun handleRecognizedText(text: String) {
        val previousContext = synchronized(recentJapanese) {
            recentJapanese.toList().takeLast(2)
        }

        synchronized(recentJapanese) {
            if (recentJapanese.lastOrNull() != text) {
                recentJapanese.addLast(text)
                while (recentJapanese.size > 3) recentJapanese.removeFirst()
            }
        }

        val settings = TranslationSettingsStore.load(this)
        if (!settings.enabled) {
            mainHandler.post {
                showResultOverlay(text)
                positionResultOutsideRegion()
            }
            return
        }

        if (settings.apiKey.isBlank()) {
            mainHandler.post {
                showResultOverlay("API 키를 앱에서 먼저 입력하세요\n\n$text")
                positionResultOutsideRegion()
            }
            return
        }

        enqueueTranslation(
            TranslationRequest(
                text = text,
                context = previousContext,
                apiKey = settings.apiKey,
                model = settings.model
            )
        )
    }

    private fun enqueueTranslation(request: TranslationRequest) {
        var shouldStart = false
        synchronized(translationLock) {
            pendingTranslation = request
            if (!translationRunning) {
                translationRunning = true
                shouldStart = true
            }
        }
        if (shouldStart) {
            translationExecutor.execute { drainTranslationQueue() }
        }
    }

    private fun drainTranslationQueue() {
        while (!Thread.currentThread().isInterrupted) {
            val request = synchronized(translationLock) {
                val next = pendingTranslation
                pendingTranslation = null
                if (next == null) {
                    translationRunning = false
                }
                next
            } ?: return

            try {
                val translated = OpenAiTranslationProvider(
                    apiKey = request.apiKey,
                    model = request.model
                ).translate(
                    japaneseText = request.text,
                    previousContext = request.context
                )

                mainHandler.post {
                    if (lastRecognizedText == request.text) {
                        showResultOverlay(translated)
                        positionResultOutsideRegion()
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (lastRecognizedText == request.text) {
                        val detail = error.message?.take(160).orEmpty()
                        showResultOverlay(
                            if (detail.isBlank()) "번역 오류: ${error.javaClass.simpleName}"
                            else "번역 오류: $detail"
                        )
                        positionResultOutsideRegion()
                    }
                }
            }
        }
    }

    private fun cropImage(image: Image, normalizedRegion: RectF): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null

        val rowPadding = rowStride - pixelStride * screenWidth
        val paddedWidth = screenWidth + rowPadding / pixelStride

        val fullBitmap = Bitmap.createBitmap(
            paddedWidth,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )

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

    private fun normalizeText(text: String): String = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()

    private fun showControlOverlay() {
        if (controlView != null) return

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA111111.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        container.addView(Button(this).apply {
            text = "영역"
            textSize = 12f
            setOnClickListener { beginRegionSelection() }
        })
        container.addView(Button(this).apply {
            text = "중지"
            textSize = 12f
            setOnClickListener { stopSelf() }
        })

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
            x = dp(8)
            y = dp(70)
        }

        windowManager.addView(container, params)
        controlView = container
    }

    private fun showResultOverlay(text: String) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        if (resultView == null) {
            val view = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xC9000000.toInt())
                textSize = 19f
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxLines = 6
            }

            val params = WindowManager.LayoutParams(
                (screenWidth * 0.74f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(14)
                y = dp(18)
            }

            windowManager.addView(view, params)
            resultView = view
            resultParams = params
        }

        val view = resultView ?: return
        if (view.text?.toString() != text) {
            view.text = text
        }
    }

    private fun positionResultOutsideRegion() {
        val region = ocrRegion ?: return
        val params = resultParams ?: return
        val view = resultView ?: return

        val density = resources.displayMetrics.density
        val margin = (14 * density).toInt()
        val maxExpectedHeight = max(view.height, (170 * density).toInt())
        val regionBottom = (region.bottom * screenHeight).toInt()

        val targetX = margin
        val targetY = if (region.top >= 0.35f) {
            margin
        } else {
            (regionBottom + margin).coerceAtMost(screenHeight - maxExpectedHeight - margin)
                .coerceAtLeast(margin)
        }

        if (params.x == targetX && params.y == targetY) return
        params.x = targetX
        params.y = targetY
        windowManager.updateViewLayout(view, params)
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
                synchronized(translationLock) { pendingTranslation = null }
                endRegionSelection()
                showResultOverlay("영역 지정 완료 · 일본어를 인식 중입니다")
                positionResultOutsideRegion()
            },
            onCancelled = {
                endRegionSelection()
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(selector, params)
        selectorView = selector
    }

    private fun resetOcrStabilityState() {
        lastRecognizedText = ""
        candidateOcrText = ""
        candidateOcrHits = 0
    }

    private fun endRegionSelection() {
        selectorView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        selectorView = null
        selectingRegion = false
    }

    private fun removeAllOverlays() {
        selectorView?.let { runCatching { windowManager.removeView(it) } }
        resultView?.let { runCatching { windowManager.removeView(it) } }
        controlView?.let { runCatching { windowManager.removeView(it) } }
        selectorView = null
        resultView = null
        controlView = null
    }

    private fun releaseProjectionResources(stopProjection: Boolean) {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (stopProjection) {
                runCatching { projection.stop() }
            }
        }
        mediaProjection = null
    }

    private fun startForegroundForProjection() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "화면 OCR/번역",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenOcrService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ocr)
            .setContentTitle("질올 실시간 번역 실행 중")
            .setContentText("PPSSPP 지정 영역을 OCR하고 번역합니다")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_ocr),
                    "중지",
                    stopIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "kr.co.zillocr.overlay.action.START"
        const val ACTION_STOP = "kr.co.zillocr.overlay.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val OCR_INTERVAL_MS = 500L
        private const val REQUIRED_STABLE_HITS = 2
        private const val SAME_TEXT_SIMILARITY = 0.86
        private const val CANDIDATE_SIMILARITY = 0.90
        private const val OCR_IGNORED_PUNCTUATION = "、。,.!！?？:：;；'\"「」『』()（）[]【】<>＜＞・…―ー-~～"
        private const val NOTIFICATION_CHANNEL_ID = "zill_ocr_capture"
        private const val NOTIFICATION_ID = 1001
    }
}
