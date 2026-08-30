package com.mangatranslator.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    @Query("SELECT * FROM dictionary_entries WHERE sourceLang = :src AND targetLang = :tgt ORDER BY updatedAt DESC")
    fun observeEntries(src: String, tgt: String): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionary_entries ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DictionaryEntity>>

    @Query("""
        SELECT * FROM dictionary_entries
        WHERE sourceLang = :src AND targetLang = :tgt
        AND (sourceText LIKE '%' || :query || '%' OR translatedText LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC
    """)
    fun search(src: String, tgt: String, query: String): Flow<List<DictionaryEntity>>

    /** Exact match lookup used at translation time - the "custom dictionary first" rule. */
    @Query("SELECT * FROM dictionary_entries WHERE sourceLang = :src AND targetLang = :tgt AND sourceText = :text LIMIT 1")
    suspend fun findExact(src: String, tgt: String, text: String): DictionaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DictionaryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntity>)

    @Update
    suspend fun update(entry: DictionaryEntity)

    @Delete
    suspend fun delete(entry: DictionaryEntity)

    @Query("DELETE FROM dictionary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM dictionary_entries ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<DictionaryEntity>

    @Query("SELECT DISTINCT category FROM dictionary_entries WHERE category != '' ORDER BY category ASC")
    fun observeCategories(): Flow<List<String>>
}
