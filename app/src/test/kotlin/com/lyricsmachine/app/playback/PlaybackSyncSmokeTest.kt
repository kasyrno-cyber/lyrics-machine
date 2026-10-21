package com.lyricsmachine.app.playback

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.playback.TimingLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric smoke test only: real playback needs a device. Checks construction, the initial state,
 * the no-op seek for an untimed line and that close() releases cleanly (twice).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSyncSmokeTest {
    @Test fun `constructs, ignores untimed seek and closes`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val lookup = TimingLookup(listOf(LineTiming(0, 1_000, 3_000)), emptyList())
        val sync = PlaybackSync(ctx, Uri.fromFile(File(ctx.cacheDir, "missing.m4a")), lookup)
        assertFalse(sync.isPlaying.value)
        assertNull(sync.position.value.lineIndex)
        assertNull(sync.durationMs.value)
        sync.seekToLine(7) // no timing for line 7: no-op
        assertEquals(0L, sync.positionMs.value)
        sync.close()
        sync.close()
        assertFalse(sync.isPlaying.value)
    }
}
