package com.nmc.stocktake.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nmc.stocktake.data.Medication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class StockTakeViewModel(application: Application) : AndroidViewModel(application) {
    private val _medications = MutableStateFlow<List<Medication>>(emptyList())
    val medications: StateFlow<List<Medication>> = _medications

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    fun loadDefaultList() {
        viewModelScope.launch {
            try {
                val json = getApplication<Application>().assets.open("nmc_medications.json")
                    .bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                val list = mutableListOf<Medication>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(Medication(
                        name = obj.getString("name"),
                        onSystem = obj.getInt("onSystem")
                    ))
                }
                _medications.value = list
                _currentIndex.value = 0
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importFromCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val reader = BufferedReader(InputStreamReader(input))
                    val list = mutableListOf<Medication>()
                    reader.readLine() // skip header if present
                    reader.forEachLine { line ->
                        val parts = line.split(",").map { it.trim().removeSurrounding("\"") }
                        if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                            val name = parts[0]
                            val onSystem = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            list.add(Medication(name = name, onSystem = onSystem))
                        }
                    }
                    _medications.value = list
                    _currentIndex.value = 0
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveCurrent(physical: Int, expiry: String, batch: String, note: String = "") {
        val list = _medications.value.toMutableList()
        val idx = _currentIndex.value
        if (idx in list.indices) {
            list[idx] = list[idx].copy(
                physicalCount = physical,
                expiry = expiry,
                batch = batch,
                note = note
            )
            _medications.value = list
        }
    }

    fun next() {
        if (_currentIndex.value < _medications.value.lastIndex) {
            _currentIndex.value += 1
        }
    }

    fun previous() {
        if (_currentIndex.value > 0) {
            _currentIndex.value -= 1
        }
    }

    fun exportToCsv(): File {
        val file = File(getApplication<Application>().filesDir, "StockTake_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { writer ->
            writer.write("Medication,On System,Physical Count,Expiry,Batch,Note\n")
            _medications.value.forEach { m ->
                writer.write("\"${m.name}\",${m.onSystem},${m.physicalCount},\"${m.expiry}\",\"${m.batch}\",\"${m.note}\"\n")
            }
        }
        return file
    }
}

    fun updateCurrentBatchExpiry(batch: String, expiry: String) {
        val list = _medications.value.toMutableList()
        val idx = _currentIndex.value
        if (idx in list.indices) {
            list[idx] = list[idx].copy(batch = batch, expiry = expiry)
            _medications.value = list
        }
    }

    fun clearList() {
        _medications.value = emptyList()
        _currentIndex.value = 0
    }
