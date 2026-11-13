package com.xuanthanhtnt.xtplayer.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LibraryScanner.swift: a file without a picture is not tried again for a week. */
class ThumbnailStoreTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun aWeekBeforeTryingAgain() {
        assertTrue(ThumbnailStore.isRecent(now - 6 * day, now))
        assertFalse(ThumbnailStore.isRecent(now - 8 * day, now))
        // Never failed.
        assertFalse(ThumbnailStore.isRecent(0, now))
    }

    @Test
    fun referencesAreLocalFileNames() {
        val reference = ThumbnailStore.reference("xtsmb://nas/Music/a.flac")
        assertTrue(ThumbnailStore.isLocal(reference))
        assertTrue(reference.endsWith(".jpg"))
        assertFalse(ThumbnailStore.isLocal("/abc.jpg"))
    }
}
