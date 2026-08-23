from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

p = Path("app/src/main/java/kr/co/zillocr/overlay/capture/ScreenOcrService.kt")
s = p.read_text(encoding="utf-8")

s = replace_once(
    s,
    "import android.content.Intent\nimport android.content.pm.ServiceInfo\n",
    "import android.content.Intent\nimport android.content.pm.ServiceInfo\nimport android.content.res.Configuration\n",
    "Configuration import",
)

s = replace_once(
    s,
    "    override fun onBind(intent: Intent?): IBinder? = null\n\n    override fun onDestroy() {\n",
    '''    override fun onBind(intent: Intent?): IBinder? = null\n\n    override fun onConfigurationChanged(newConfig: Configuration) {\n        super.onConfigurationChanged(newConfig)\n        mainHandler.post { handleConfigurationChange() }\n    }\n\n    private fun handleConfigurationChange() {\n        val oldWidth = screenWidth\n        val oldHeight = screenHeight\n        val oldDensityDpi = densityDpi\n        val displayedText = resultView?.text?.toString().orEmpty()\n        val resultWasVisible = resultContainer?.visibility != View.GONE\n        val selectionWasActive = selectorView != null\n\n        if (oldWidth > 0 && oldHeight > 0) {\n            resultParams?.let { saveOverlayGeometry(it) }\n        }\n        if (selectionWasActive) endRegionSelection()\n\n        updateScreenMetrics()\n        if (screenWidth == oldWidth && screenHeight == oldHeight && densityDpi == oldDensityDpi) {\n            if (selectionWasActive) beginRegionSelection()\n            return\n        }\n\n        resetOcrStabilityState()\n        if (mediaProjection != null) reconfigureCaptureSurface()\n\n        removeControlOverlay()\n        removeResultOverlay()\n        showControlOverlay()\n        if (displayedText.isNotBlank()) {\n            showResultOverlay(displayedText)\n            if (!resultWasVisible) resultContainer?.visibility = View.GONE\n        }\n        if (selectionWasActive) beginRegionSelection()\n    }\n\n    override fun onDestroy() {\n''',
    "configuration handler",
)

s = replace_once(
    s,
    "    private fun onImageAvailable(reader: ImageReader) {\n",
    '''    private fun reconfigureCaptureSurface() {\n        val projection = mediaProjection ?: return\n        val oldReader = imageReader\n        oldReader?.setOnImageAvailableListener(null, null)\n        virtualDisplay?.setSurface(null)\n        oldReader?.close()\n\n        val newReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2).apply {\n            setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, captureHandler)\n        }\n        imageReader = newReader\n\n        val display = virtualDisplay\n        if (display != null) {\n            display.resize(screenWidth, screenHeight, densityDpi)\n            display.setSurface(newReader.surface)\n        } else {\n            virtualDisplay = projection.createVirtualDisplay(\n                "ZillOcrCapture",\n                screenWidth,\n                screenHeight,\n                densityDpi,\n                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,\n                newReader.surface,\n                null,\n                captureHandler\n            )\n        }\n    }\n\n    private fun onImageAvailable(reader: ImageReader) {\n''',
    "capture reconfigure",
)

old_crop = '''        val rowPadding = rowStride - pixelStride * screenWidth\n        val paddedWidth = screenWidth + rowPadding / pixelStride\n        val fullBitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)\n        buffer.rewind()\n        fullBitmap.copyPixelsFromBuffer(buffer)\n        val left = (normalizedRegion.left * screenWidth).toInt().coerceIn(0, screenWidth - 1)\n        val top = (normalizedRegion.top * screenHeight).toInt().coerceIn(0, screenHeight - 1)\n        val right = (normalizedRegion.right * screenWidth).toInt().coerceIn(left + 1, screenWidth)\n        val bottom = (normalizedRegion.bottom * screenHeight).toInt().coerceIn(top + 1, screenHeight)\n'''
new_crop = '''        val captureWidth = image.width\n        val captureHeight = image.height\n        if (captureWidth <= 0 || captureHeight <= 0) return null\n        val rowPadding = rowStride - pixelStride * captureWidth\n        val paddedWidth = captureWidth + rowPadding / pixelStride\n        val fullBitmap = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)\n        buffer.rewind()\n        fullBitmap.copyPixelsFromBuffer(buffer)\n        val left = (normalizedRegion.left * captureWidth).toInt().coerceIn(0, captureWidth - 1)\n        val top = (normalizedRegion.top * captureHeight).toInt().coerceIn(0, captureHeight - 1)\n        val right = (normalizedRegion.right * captureWidth).toInt().coerceIn(left + 1, captureWidth)\n        val bottom = (normalizedRegion.bottom * captureHeight).toInt().coerceIn(top + 1, captureHeight)\n'''
s = replace_once(s, old_crop, new_crop, "image-sized crop")

s = replace_once(
    s,
    "        val panelMaxHeight = (screenHeight - dp(110)).coerceAtLeast(dp(160))\n",
    "        val panelMaxHeight = (screenHeight - dp(110)).coerceAtLeast(dp(96))\n",
    "landscape panel minimum",
)

old_remove = '''    private fun removeAllOverlays() {\n        selectorView?.let { runCatching { windowManager.removeView(it) } }\n        resultContainer?.let { runCatching { windowManager.removeView(it) } }\n        controlView?.let { runCatching { windowManager.removeView(it) } }\n        selectorView = null\n        resultContainer = null\n        resultView = null\n        resultParams = null\n        controlView = null\n        detailsPanel = null\n        primaryActions = null\n        gearBadgeView = null\n        speakerApprovalButton = null\n        aliasApprovalButton = null\n        autoHeightButton = null\n        speakerModeButton = null\n        alphaButton = null\n    }\n'''
new_remove = '''    private fun removeResultOverlay() {\n        resultContainer?.let { runCatching { windowManager.removeView(it) } }\n        resultContainer = null\n        resultView = null\n        resultParams = null\n    }\n\n    private fun removeControlOverlay() {\n        controlView?.let { runCatching { windowManager.removeView(it) } }\n        controlView = null\n        detailsPanel = null\n        primaryActions = null\n        gearBadgeView = null\n        speakerApprovalButton = null\n        aliasApprovalButton = null\n        autoHeightButton = null\n        speakerModeButton = null\n        alphaButton = null\n    }\n\n    private fun removeAllOverlays() {\n        selectorView?.let { runCatching { windowManager.removeView(it) } }\n        selectorView = null\n        selectingRegion = false\n        removeResultOverlay()\n        removeControlOverlay()\n    }\n'''
s = replace_once(s, old_remove, new_remove, "overlay cleanup split")

p.write_text(s, encoding="utf-8")

b = Path("app/build.gradle.kts")
t = b.read_text(encoding="utf-8")
t = replace_once(t, '        versionCode = 23\n        versionName = "0.5.0-alpha8.2"\n', '        versionCode = 24\n        versionName = "0.5.0-alpha8.3"\n', "version bump")
b.write_text(t, encoding="utf-8")
print("alpha8.3 rotation patch applied")
