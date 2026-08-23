package kr.co.zillocr.overlay.data

import android.content.Context
import android.graphics.RectF

object RegionStore {
    private const val PREFS = "ocr_region"
    private const val KEY_LEFT = "left"
    private const val KEY_TOP = "top"
    private const val KEY_RIGHT = "right"
    private const val KEY_BOTTOM = "bottom"
    private const val KEY_HAS_REGION = "has_region"

    fun save(context: Context, region: RectF) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_REGION, true)
            .putFloat(KEY_LEFT, region.left.coerceIn(0f, 1f))
            .putFloat(KEY_TOP, region.top.coerceIn(0f, 1f))
            .putFloat(KEY_RIGHT, region.right.coerceIn(0f, 1f))
            .putFloat(KEY_BOTTOM, region.bottom.coerceIn(0f, 1f))
            .apply()
    }

    fun load(context: Context): RectF? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_REGION, false)) return null
        return RectF(
            prefs.getFloat(KEY_LEFT, 0.05f),
            prefs.getFloat(KEY_TOP, 0.60f),
            prefs.getFloat(KEY_RIGHT, 0.95f),
            prefs.getFloat(KEY_BOTTOM, 0.92f)
        )
    }
}
