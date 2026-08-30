package com.mangatranslator.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangatranslator.app.MyApplication
import com.mangatranslator.app.data.DictionaryEntity
import com.mangatranslator.app.data.DictionaryImportExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DictionaryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as MyApplication).database.dictionaryDao()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _categoryFilter = MutableStateFlow<String?>(null) // null = all categories
    val categoryFilter: StateFlow<String?> = _categoryFilter

    val categories: StateFlow<List<String>> =
        dao.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val entries: StateFlow<List<DictionaryEntity>> =
        combine(dao.observeAll(), _query, _categoryFilter) { list, q, category ->
            list.filter { entry ->
                val matchesQuery = q.isBlank() ||
                    entry.sourceText.contains(q, ignoreCase = true) ||
                    entry.translatedText.contains(q, ignoreCase = true)
                val matchesCategory = category == null || entry.category == category
                matchesQuery && matchesCategory
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun setCategoryFilter(category: String?) { _categoryFilter.value = category }

    fun addOrUpdate(entry: DictionaryEntity) = viewModelScope.launch {
        if (entry.id == 0L) dao.insert(entry) else dao.update(entry)
    }

    fun delete(entry: DictionaryEntity) = viewModelScope.launch {
        dao.delete(entry)
    }

    fun exportJson(uri: Uri) = viewModelScope.launch {
        DictionaryImportExport.exportToJson(getApplication(), uri, dao.getAllOnce())
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        DictionaryImportExport.exportToCsv(getApplication(), uri, dao.getAllOnce())
    }

    fun importFile(uri: Uri, fileName: String) = viewModelScope.launch {
        val imported = DictionaryImportExport.importAuto(getApplication(), uri, fileName)
        dao.insertAll(imported)
    }
}
