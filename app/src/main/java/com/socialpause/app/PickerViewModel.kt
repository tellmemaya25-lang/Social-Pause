package com.socialpause.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PickerState(
    val apps: List<LaunchableApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val loading: Boolean = true,
    val error: Boolean = false,
    val selectionError: Boolean = false,
) {
    val filteredApps: List<LaunchableApp> get() = apps.filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    val unavailable: Set<String> get() = selected - apps.map { it.packageName }.toSet()
}

class PickerViewModel(private val catalog: AppCatalog, private val selections: SelectionStore) : ViewModel() {
    private val mutableState = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = mutableState.asStateFlow()
    private val selectionLock = Mutex()
    private var loadedSelection = false

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            // Serialize loading and changes so a delayed read cannot overwrite a user action.
            selectionLock.withLock {
                mutableState.update { it.copy(loading = true, error = false, selectionError = false) }
                try {
                    if (!loadedSelection) {
                        val selected = selections.read()
                        mutableState.update { it.copy(selected = selected) }
                        loadedSelection = true
                    }
                    val apps = catalog.load()
                    mutableState.update { it.copy(apps = apps, loading = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableState.update { it.copy(loading = false, error = true) }
                }
            }
        }
    }
    fun search(query: String) = mutableState.update { it.copy(query = query) }

    fun toggle(packageName: String) = changeSelection { state ->
        if (state.apps.none { it.packageName == packageName }) state.selected
        else if (packageName in state.selected) state.selected - packageName else state.selected + packageName
    }

    fun clearSelection() = changeSelection { emptySet() }
    fun removeUnavailable() = changeSelection { state -> state.selected - state.unavailable }

    private fun changeSelection(change: (PickerState) -> Set<String>) {
        viewModelScope.launch {
            selectionLock.withLock {
                val current = mutableState.value
                if (!loadedSelection || current.loading || current.error) return@withLock
                val updated = change(current)
                try {
                    selections.write(updated)
                    mutableState.update { it.copy(selected = updated, selectionError = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the last confirmed selection visible, rather than claiming a failed save worked.
                    mutableState.update { it.copy(selectionError = true) }
                }
            }
        }
    }

    class Factory(private val catalog: AppCatalog, private val selections: SelectionStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PickerViewModel::class.java))
            return PickerViewModel(catalog, selections) as T
        }
    }
}
