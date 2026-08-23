package kr.co.zillocr.overlay.data

import android.content.Context

object TranslationSettingsStore {
    private const val PREFS = "translation_settings"
    private const val KEY_API_KEY = "openrouter_api_key"
    private const val KEY_MODEL = "openrouter_model"
    private const val KEY_ENABLED = "translation_enabled"
    private const val KEY_ENGINE = "translation_engine"
    private const val KEY_LOCAL_MODEL_PATH = "local_model_path"

    const val DEFAULT_MODEL = "openrouter/free"
    const val ENGINE_API = "api"
    const val ENGINE_LOCAL = "local"

    data class Settings(
        val enabled: Boolean,
        val apiKey: String,
        val model: String,
        val engine: String,
        val localModelPath: String
    )

    fun load(context: Context): Settings {
        AppContextHolder.init(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL)
                ?.trim()
                ?.ifBlank { DEFAULT_MODEL }
                ?: DEFAULT_MODEL,
            engine = prefs.getString(KEY_ENGINE, ENGINE_API).orEmpty().ifBlank { ENGINE_API },
            localModelPath = prefs.getString(KEY_LOCAL_MODEL_PATH, "").orEmpty()
        )
    }

    fun save(
        context: Context,
        enabled: Boolean,
        apiKey: String,
        model: String,
        engine: String = ENGINE_API,
        localModelPath: String = ""
    ) {
        AppContextHolder.init(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .putString(KEY_ENGINE, engine)
            .putString(KEY_LOCAL_MODEL_PATH, localModelPath)
            .apply()
    }
}
