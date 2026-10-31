package com.xuanthanhtnt.xtplayer.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UpdateChecker.swift: only `v*` tags are app releases; the version compare it already had. */
class UpdateCheckerTest {
    @Test
    fun onlyVersionTagsAreUpdates() {
        assertTrue(UpdateChecker.isAppRelease("v0.2.8"))
        assertTrue(UpdateChecker.isAppRelease("v0.1.8"))
        // Prebuilt FFmpeg for the build, published as a release on the same repository.
        assertFalse(UpdateChecker.isAppRelease("deps-ffmpeg-7.1"))
        assertFalse(UpdateChecker.isAppRelease("0.2.8"))
    }

    @Test
    fun newerVersion() {
        assertTrue(UpdateChecker.isNewer("0.10.1", "0.9.3"))
        assertTrue(UpdateChecker.isNewer("0.2.9", "0.2.8"))
        assertFalse(UpdateChecker.isNewer("0.2.8", "0.2.8"))
        assertFalse(UpdateChecker.isNewer("0.2", "0.2.0"))
    }
}
