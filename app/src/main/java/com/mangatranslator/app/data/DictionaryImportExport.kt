package com.mangatranslator.app.data

import android.content.Context
import android.net.Uri
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Handles reading/writing the user-editable dictionary as JSON or CSV,
 * either from the app's own "export" flow or from a file picked by the
 * user (SAF - Storage Access Framework, so no broad storage permission
 * is required on modern Android).
 *
 * JSON schema (array of objects):
 * [
 *   {
 *     "source": "お前",
 *     "translation": "تو",
 *     "sourceLang": "ja",
 *     "targetLang": "fa",
 *     "caseSensitive": false,
 *     "note": ""
 *   }, ...
 * ]
 *
 * CSV schema (header row required):
 * source,translation,sourceLang,targetLang,caseSensitive,note
 */
object DictionaryImportExport {

    fun exportToJson(context: Context, uri: Uri, entries: List<DictionaryEntity>) {
        val array = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject()
            obj.put("source", e.sourceText)
            obj.put("translation", e.translatedText)
            obj.put("sourceLang", e.sourceLang)
            obj.put("targetLang", e.targetLang)
            obj.put("caseSensitive", e.caseSensitive)
            obj.put("note", e.note)
            obj.put("category", e.category)
            array.put(obj)
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(array.toString(2)) }
        }
    }

    fun importFromJson(context: Context, uri: Uri): List<DictionaryEntity> {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return emptyList()

        val array = JSONArray(text)
        val result = mutableListOf<DictionaryEntity>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result += DictionaryEntity(
                sourceText = obj.optString("source"),
                translatedText = obj.optString("translation"),
                sourceLang = obj.optString("sourceLang", "ja"),
                targetLang = obj.optString("targetLang", "fa"),
                caseSensitive = obj.optBoolean("caseSensitive", false),
                note = obj.optString("note", ""),
                category = obj.optString("category", "")
            )
        }
        return result
    }

    fun exportToCsv(context: Context, uri: Uri, entries: List<DictionaryEntity>) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            CSVWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                writer.writeNext(arrayOf("source", "translation", "sourceLang", "targetLang", "caseSensitive", "note", "category"))
                entries.forEach { e ->
                    writer.writeNext(
                        arrayOf(
                            e.sourceText, e.translatedText, e.sourceLang, e.targetLang,
                            e.caseSensitive.toString(), e.note, e.category
                        )
                    )
                }
            }
        }
    }

    fun importFromCsv(context: Context, uri: Uri): List<DictionaryEntity> {
        val result = mutableListOf<DictionaryEntity>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            CSVReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val rows = reader.readAll()
                if (rows.isEmpty()) return@use
                // skip header row
                for (i in 1 until rows.size) {
                    val row = rows[i]
                    if (row.size < 4) continue
                    result += DictionaryEntity(
                        sourceText = row[0],
                        translatedText = row[1],
                        sourceLang = row[2],
                        targetLang = row[3],
                        caseSensitive = row.getOrNull(4)?.toBooleanStrictOrNull() ?: false,
                        note = row.getOrNull(5) ?: "",
                        category = row.getOrNull(6) ?: ""
                    )
                }
            }
        }
        return result
    }

    /** Dispatches based on file extension in the display name / uri path. */
    fun importAuto(context: Context, uri: Uri, fileName: String): List<DictionaryEntity> {
        return if (fileName.endsWith(".csv", ignoreCase = true)) {
            importFromCsv(context, uri)
        } else {
            importFromJson(context, uri)
        }
    }
}
