package kr.co.zillocr.overlay.data

import android.content.Context

object OverlaySettingsStore {
    private const val PREFS = "overlay_settings"
    private const val KEY_AUTO = "auto_position"
    private const val KEY_X = "x_ratio"
    private const val KEY_Y = "y_ratio"
    private const val KEY_WIDTH = "width_ratio"
    private const val KEY_HEIGHT = "height_ratio"
    private const val KEY_TEXT_SIZE = "text_size_sp"
    private const val KEY_ALPHA = "background_alpha"

    data class Settings(
        val autoPosition: Boolean,
        val xRatio: Float,
        val yRatio: Float,
        val widthRatio: Float,
        val heightRatio: Float,
        val textSizeSp: Float,
        val backgroundAlpha: Int
    )

    fun load(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            autoPosition = prefs.getBoolean(KEY_AUTO, true),
            xRatio = prefs.getFloat(KEY_X, 0.03f).coerceIn(0f, 0.95f),
            yRatio = prefs.getFloat(KEY_Y, 0.03f).coerceIn(0f, 0.95f),
            widthRatio = prefs.getFloat(KEY_WIDTH, 0.74f).coerceIn(0.30f, 0.96f),
            heightRatio = prefs.getFloat(KEY_HEIGHT, 0.18f).coerceIn(0.10f, 0.50f),
            textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 19f).coerceIn(13f, 30f),
            backgroundAlpha = prefs.getInt(KEY_ALPHA, 205).coerceIn(70, 235)
        )
    }

    fun saveGeometry(
        context: Context,
        autoPosition: Boolean,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, autoPosition)
            .putFloat(KEY_X, xRatio.coerceIn(0f, 0.95f))
            .putFloat(KEY_Y, yRatio.coerceIn(0f, 0.95f))
            .putFloat(KEY_WIDTH, widthRatio.coerceIn(0.30f, 0.96f))
            .putFloat(KEY_HEIGHT, heightRatio.coerceIn(0.10f, 0.50f))
            .apply()
    }

    fun setAutoPosition(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, enabled)
            .apply()
    }

    fun saveTextSize(context: Context, textSizeSp: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TEXT_SIZE, textSizeSp.coerceIn(13f, 30f))
            .apply()
    }

    fun saveBackgroundAlpha(context: Context, alpha: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ALPHA, alpha.coerceIn(70, 235))
            .apply()
    }
}
