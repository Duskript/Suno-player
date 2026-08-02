package com.duskript.sunolocal.core.storage

import android.content.Context
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * Unit tests for the v0.1.18 playlist cleanup tools.
 *
 * The pure candidate-selection helper [emptySyncedPlaylists] runs on a plain
 * JVM. The store's bulk-hide methods are exercised with a Mockito-mocked
 * [Context] whose filesDir points at a JVM temp folder, so the JSON
 * read/write path behaves exactly as on-device without Robolectric.
 */
class HiddenPlaylistStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): LibraryStore {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        return LibraryStore(context)
    }

    @Test
    fun `emptySyncedPlaylists keeps only non-custom zero-track playlists`() {
        val candidates = emptySyncedPlaylists(
            listOf(
                SunoPlaylistJson(id = "pl-empty", title = "Empty synced"),
                SunoPlaylistJson(id = "pl-full", title = "Full", tracks = listOf(SunoTrackJson(id = "t1", title = "T"))),
                SunoPlaylistJson(id = "custom-empty", title = "My Mix", isCustom = true)
            )
        )

        assertEquals(listOf("pl-empty"), candidates.map { it.id })
        assertFalse("custom playlists must never be bulk-hidden", candidates.any { it.isCustom })
    }

    @Test
    fun `hideSyncedPlaylists hides ids removes local metadata and returns newly hidden count`() {
        val libraryStore = store()
        libraryStore.savePlaylists(
            listOf(
                SunoPlaylistJson(id = "pl-1", title = "One"),
                SunoPlaylistJson(id = "pl-2", title = "Two"),
                SunoPlaylistJson(id = "pl-3", title = "Three")
            )
        )

        // Blank ids are ignored and duplicates are deduped.
        val hidden = libraryStore.hideSyncedPlaylists(listOf("pl-1", "pl-2", "   ", "pl-1"))

        assertEquals("two ids newly hidden", 2, hidden)
        assertEquals(setOf("pl-1", "pl-2"), libraryStore.loadHiddenPlaylistIds())
        assertEquals(2, libraryStore.hiddenPlaylistCount())
        assertEquals(listOf("pl-3"), libraryStore.loadPlaylists().map { it.id })
    }

    @Test
    fun `hideSyncedPlaylists does not re-count already hidden ids`() {
        val libraryStore = store()
        libraryStore.savePlaylists(listOf(SunoPlaylistJson(id = "pl-1", title = "One")))

        assertEquals(1, libraryStore.hideSyncedPlaylists(listOf("pl-1")))
        // Second pass with a mix of already-hidden and new ids.
        assertEquals(1, libraryStore.hideSyncedPlaylists(listOf("pl-1", "pl-new")))
        assertEquals(setOf("pl-1", "pl-new"), libraryStore.loadHiddenPlaylistIds())
        assertEquals(2, libraryStore.hiddenPlaylistCount())
    }

    @Test
    fun `hidden ids persist as sorted stable JSON`() {
        val libraryStore = store()
        libraryStore.hideSyncedPlaylists(listOf("zeta", "alpha"))

        val file = tempFolder.root.listFiles()!!.first { it.name == "suno_hidden_playlists.json" }
        val text = file.readText()
        val alphaIndex = text.indexOf("\"alpha\"")
        val zetaIndex = text.indexOf("\"zeta\"")

        assertTrue("hidden ids are persisted sorted", alphaIndex in 0 until zetaIndex)
        // Reload from disk returns the same set.
        assertEquals(setOf("alpha", "zeta"), libraryStore.loadHiddenPlaylistIds())
    }

    @Test
    fun `clearHiddenPlaylists empties the hidden set`() {
        val libraryStore = store()
        libraryStore.hideSyncedPlaylists(listOf("pl-1", "pl-2"))

        libraryStore.clearHiddenPlaylists()

        assertTrue(libraryStore.loadHiddenPlaylistIds().isEmpty())
        assertEquals(0, libraryStore.hiddenPlaylistCount())
    }
}
