package com.socialpause.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

    @Test fun reloadAfterFirstLoadKeepsControlsUsable() = runTest(dispatcher) {
        var gate: CompletableDeferred<Unit>? = null
        val slowCatalog = object : AppCatalog {
            override suspend fun load(): List<LaunchableApp> { gate?.await(); return apps }
        }
        val store = MemoryStore()
        val vm = PickerViewModel(slowCatalog, store)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)

        gate = CompletableDeferred()
        vm.reload()
        runCurrent()
        assertFalse("Revisiting the picker must not flash the loading state", vm.state.value.loading)

        // A selection change made mid-reload is applied once the reload finishes, not lost.
        vm.toggle("org.example.app")
        runCurrent()
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("org.example.app"), store.saved)
        assertEquals(setOf("org.example.app"), vm.state.value.selected)
    }

    @Test fun firstLoadShowsLoading() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = PickerViewModel(object : AppCatalog { override suspend fun load(): List<LaunchableApp> { gate.await(); return apps } }, MemoryStore())
        runCurrent()
        assertTrue(vm.state.value.loading)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
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
