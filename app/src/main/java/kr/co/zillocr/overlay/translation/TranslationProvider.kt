package kr.co.zillocr.overlay.translation

interface TranslationProvider {
    @Throws(Exception::class)
    fun translate(
        japaneseText: String,
        previousContext: List<String>
    ): String
}
