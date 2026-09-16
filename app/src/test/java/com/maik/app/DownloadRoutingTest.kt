package com.maik.app

import com.maik.app.data.Download
import com.maik.app.data.Effect
import com.maik.app.data.reduceDownload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** A download of one model used to take over the chat of another. */
class DownloadRoutingTest {

    private fun done(id: String) = Download.Done(File("$id.litertlm"), id)

    @Test
    fun `progress never changes the stage`() {
        assertEquals(Stage.Ready to Effect.None, reduceDownload(Stage.Ready, "gemma", Download.Progress(1, 2), false))
    }

    @Test
    fun `another model finishing leaves a ready chat alone`() {
        assertEquals(Stage.Ready to Effect.None, reduceDownload(Stage.Ready, "gemma", done("lfm"), false))
    }

    @Test
    fun `another model finishing while this screen showed a download re-checks the target`() {
        val downloading = Stage.Downloading(1, 2)
        assertEquals(downloading to Effect.LoadTarget, reduceDownload(downloading, "gemma", done("lfm"), false))
    }

    @Test
    fun `the target finishing on the setup screen confirms, elsewhere just loads`() {
        assertEquals(Effect.ConfirmAndLoad, reduceDownload(Stage.Downloading(1, 2), "gemma", done("gemma"), true).second)
        assertEquals(Effect.LoadTarget, reduceDownload(Stage.Downloading(1, 2), "gemma", done("gemma"), false).second)
    }

    @Test
    fun `cancelling the target goes back to needing it`() {
        val failed = Download.Failed("gemma", "Cancelled", cancelled = true)
        assertEquals(Stage.NeedsModel to Effect.None, reduceDownload(Stage.Downloading(1, 2), "gemma", failed, false))
    }

    @Test
    fun `another model failing leaves a ready chat alone`() {
        assertEquals(Stage.Ready to Effect.None, reduceDownload(Stage.Ready, "lfm", Download.Failed("gemma", "boom"), false))
    }

    @Test
    fun `the target failing offers to continue`() {
        assertEquals(
            Stage.Broken("boom", "", Fix.RESUME_DOWNLOAD) to Effect.None,
            reduceDownload(Stage.Downloading(1, 2), "gemma", Download.Failed("gemma", "boom"), false)
        )
    }
}
