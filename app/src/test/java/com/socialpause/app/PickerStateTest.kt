package com.socialpause.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerStateTest {
    private val apps = listOf(LaunchableApp("org.example.one", "Chat"), LaunchableApp("org.example.two", "Chat"), LaunchableApp("org.example.news", "News"))

    @Test fun searchMatchesLabelsWithoutCollapsingDuplicates() {
        val state = PickerState(apps = apps, query = "cHaT")
        assertEquals(listOf("org.example.one", "org.example.two"), state.filteredApps.map { it.packageName })
    }

    @Test fun searchMatchesPackageName() {
        assertEquals(listOf(apps.last()), PickerState(apps = apps, query = "example.news").filteredApps)
    }

    @Test fun unavailableSavedPackagesAreNotSilentlyDiscarded() {
        val state = PickerState(apps = apps, selected = setOf("org.example.one", "org.example.missing"))
        assertEquals(setOf("org.example.missing"), state.unavailable)
    }

    @Test fun emptySearchShowsAllApps() {
        assertEquals(apps, PickerState(apps = apps).filteredApps)
    }
}
