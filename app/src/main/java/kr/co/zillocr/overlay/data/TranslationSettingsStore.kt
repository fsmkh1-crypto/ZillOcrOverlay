package kr.co.zillocr.overlay.data

import android.content.Context

object TranslationSettingsStore {
    private const val PREFS = "translation_settings"
    private const val KEY_ENABLED = "translation_enabled"
    private const val KEY_MODEL = "translation_model"

    const val DEFAULT_MODEL = "gpt-5.6-terra"

    data class ModelOption(val id: String, val label: String)

    val MODEL_OPTIONS = listOf(
        ModelOption("gpt-5.6-terra", "GPT-5.6 Terra · 균형 / 기본"),
        ModelOption("gpt-5.6-luna", "GPT-5.6 Luna · 가장 빠르고 저렴"),
        ModelOption("gpt-5.6-sol", "GPT-5.6 Sol · 최고 품질"),
        ModelOption("gpt-5.5", "GPT-5.5"),
        ModelOption("gpt-5.5-pro", "GPT-5.5 Pro · 매우 느리고 고가"),
        ModelOption("gpt-5.4", "GPT-5.4"),
        ModelOption("gpt-5.4-mini", "GPT-5.4 mini"),
        ModelOption("gpt-5.4-nano", "GPT-5.4 nano"),
        ModelOption("gpt-5.2", "GPT-5.2"),
        ModelOption("gpt-5.1", "GPT-5.1"),
        ModelOption("gpt-5", "GPT-5"),
        ModelOption("gpt-5-mini", "GPT-5 mini"),
        ModelOption("gpt-5-nano", "GPT-5 nano"),
        ModelOption("gpt-5-pro", "GPT-5 Pro · 느리고 고가"),
        ModelOption("o3", "o3"),
        ModelOption("o3-pro", "o3 Pro · 느림"),
        ModelOption("gpt-4.1", "GPT-4.1"),
        ModelOption("gpt-4.1-mini", "GPT-4.1 mini"),
        ModelOption("gpt-4o", "GPT-4o"),
        ModelOption("gpt-4o-mini", "GPT-4o mini")
    )

    data class Settings(
        val enabled: Boolean,
        val apiKey: String,
        val model: String
    )

    fun load(context: Context): Settings {
        AppContextHolder.init(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedModel = prefs.getString(KEY_MODEL, null)
            ?.takeIf { value -> MODEL_OPTIONS.any { it.id == value } }
            ?: DEFAULT_MODEL
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            apiKey = ApiKeyStore.load(context),
            model = savedModel
        )
    }

    fun save(context: Context, enabled: Boolean, apiKey: String, model: String) {
        AppContextHolder.init(context)
        val safeModel = model.takeIf { value -> MODEL_OPTIONS.any { it.id == value } } ?: DEFAULT_MODEL
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_MODEL, safeModel)
            .apply()
        ApiKeyStore.save(context, apiKey)
    }
}
