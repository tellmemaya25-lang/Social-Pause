package com.socialpause.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** Saved choices only; this store is not evidence of an active session. */
interface SelectionStore {
    suspend fun read(): Set<String>
    suspend fun write(packages: Set<String>)
}

private val Context.selectionDataStore by preferencesDataStore(name = "saved_selection")

class LocalSelectionStore(context: Context) : SelectionStore {
    private val store = context.applicationContext.selectionDataStore
    private val key = stringSetPreferencesKey("packages")

    override suspend fun read(): Set<String> = store.data.first()[key]?.toSet() ?: emptySet()
    override suspend fun write(packages: Set<String>) {
        store.edit { preferences ->
            if (packages.isEmpty()) preferences.remove(key) else preferences[key] = packages.toSet()
        }
    }
}
