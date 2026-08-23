package kr.co.zillocr.overlay.data

import android.content.Context

object TranslationSettingsStore {
    private const val PREFS = "translation_settings"
    private const val KEY_ENABLED = "translation_enabled"

    const val DEFAULT_MODEL = "gpt-5.6-luna"

    data class Settings(
        val enabled: Boolean,
        val apiKey: String,
        val model: String
    )

    fun load(context: Context): Settings {
        AppContextHolder.init(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            apiKey = ApiKeyStore.load(context),
            model = DEFAULT_MODEL
        )
    }

    fun save(context: Context, enabled: Boolean, apiKey: String) {
        AppContextHolder.init(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        ApiKeyStore.save(context, apiKey)
    }
}
