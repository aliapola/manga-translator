package com.mangatranslator.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single user-editable translation pair, e.g. "お前" -> "تو".
 * This is the custom dictionary that always takes priority over the
 * offline ML Kit model, per the app's translation-priority rule.
 *
 * [sourceLang] / [targetLang] use ML Kit language codes ("ja", "ko",
 * "zh", "en", "fa" ...) so entries can be filtered per language pair.
 */
@Entity(tableName = "dictionary_entries")
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val caseSensitive: Boolean = false,
    val note: String = "",
    /** Optional free-form tag, e.g. a manga title, so entries can be grouped/filtered. */
    val category: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
