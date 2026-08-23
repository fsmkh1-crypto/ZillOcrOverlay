package kr.co.zillocr.overlay.data

import android.content.Context

object TranslationSettingsStore {
    private const val PREFS = "translation_settings"
    private const val KEY_API_KEY = "openai_api_key"
    private const val KEY_MODEL = "openai_model"
    private const val KEY_ENABLED = "translation_enabled"

    const val DEFAULT_MODEL = "gpt-5.6-luna"

    data class Settings(
        val enabled: Boolean,
        val apiKey: String,
        val model: String
    )

    fun load(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL)
                ?.trim()
                ?.ifBlank { DEFAULT_MODEL }
                ?: DEFAULT_MODEL
        )
    }

    fun save(context: Context, enabled: Boolean, apiKey: String, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }
}
