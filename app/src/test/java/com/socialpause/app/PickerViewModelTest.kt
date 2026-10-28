package com.socialpause.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val apps = listOf(LaunchableApp("org.example.app", "Example"))
    private class MemoryStore(var saved: Set<String> = emptySet(), var failWrite: Boolean = false) : SelectionStore {
        override suspend fun read() = saved
        override suspend fun write(packages: Set<String>) {
            if (failWrite) error("Storage failure")
            saved = packages
        }
    }
    private val catalog = object : AppCatalog { override suspend fun load() = apps }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsSavesAndClearsSelection() = runTest(dispatcher) {
        val store = MemoryStore(setOf("org.example.app"))
        val vm = PickerViewModel(catalog, store)
        advanceUntilIdle()
        assertEquals(setOf("org.example.app"), vm.state.value.selected)
        vm.clearSelection()
        advanceUntilIdle()
        assertTrue(store.saved.isEmpty())
        vm.toggle("org.example.app")
        advanceUntilIdle()
        assertEquals(setOf("org.example.app"), store.saved)
    }

    @Test fun failedWriteDoesNotPretendSelectionWasSaved() = runTest(dispatcher) {
        val store = MemoryStore(failWrite = true)
        val vm = PickerViewModel(catalog, store)
        advanceUntilIdle()
        vm.toggle("org.example.app")
        advanceUntilIdle()
        assertTrue(vm.state.value.selectionError)
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test fun missingPackagesRequireExplicitRemoval() = runTest(dispatcher) {
        val store = MemoryStore(setOf("missing.package"))
        val vm = PickerViewModel(catalog, store)
        advanceUntilIdle()
        assertEquals(setOf("missing.package"), vm.state.value.unavailable)
        vm.removeUnavailable()
        advanceUntilIdle()
        assertTrue(store.saved.isEmpty())
    }
}
