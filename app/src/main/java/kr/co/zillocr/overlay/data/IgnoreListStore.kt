package kr.co.zillocr.overlay.data

import android.content.Context

object IgnoreListStore {
    private const val PREFS = "zill_learning_ignore"
    private const val KEY_ITEMS = "items"
    private const val SPEAKER_PREFIX = "speaker:"
    private const val ALIAS_PREFIX = "alias:"

    fun isSpeakerIgnored(context: Context, normalizedSource: String): Boolean =
        contains(context, speakerKey(normalizedSource))

    fun isAliasIgnored(context: Context, observed: String): Boolean =
        contains(context, aliasKey(observed))

    fun ignoreSpeaker(context: Context, normalizedSource: String) =
        add(context, speakerKey(normalizedSource))

    fun ignoreAlias(context: Context, observed: String) =
        add(context, aliasKey(observed))

    fun all(context: Context): List<String> = read(context).sorted()

    fun remove(context: Context, key: String) {
        val next = read(context).toMutableSet()
        if (next.remove(key)) write(context, next)
    }

    fun clear(context: Context) = write(context, emptySet())

    fun displayLabel(key: String): String = when {
        key.startsWith(SPEAKER_PREFIX) -> "화자 · ${key.removePrefix(SPEAKER_PREFIX)}"
        key.startsWith(ALIAS_PREFIX) -> "OCR · ${key.removePrefix(ALIAS_PREFIX)}"
        else -> key
    }

    private fun speakerKey(value: String) = "$SPEAKER_PREFIX${value.trim()}"
    private fun aliasKey(value: String) = "$ALIAS_PREFIX${value.trim()}"

    private fun contains(context: Context, key: String): Boolean = read(context).contains(key)

    private fun add(context: Context, key: String) {
        val next = read(context).toMutableSet()
        if (next.add(key)) write(context, next)
    }

    private fun read(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ITEMS, emptySet())
            ?.toSet()
            .orEmpty()

    private fun write(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ITEMS, values.toSet())
            .apply()
    }
}
