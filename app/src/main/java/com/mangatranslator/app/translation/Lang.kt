package com.mangatranslator.app.translation

/** Language codes (previously came from ML Kit's TranslateLanguage; ML Kit Translate is no longer used). */
object Lang {
    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"
    const val CHINESE = "zh"
    const val PERSIAN = "fa"

    /** MyMemory expects "zh-CN" rather than plain "zh". */
    fun forMyMemory(code: String): String = if (code == CHINESE) "zh-CN" else code
}
