package com.maik.app

import com.maik.app.data.Download
import com.maik.app.data.DownloadBus
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A finished download used to be replayed to every new screen, which started a second
 * load of a 2.5 GB model each time the screen was recreated.
 */
class DownloadBusTest {

    @Test
    fun `a finished download is not replayed to a screen that arrives later`() = runBlocking {
        DownloadBus.events.tryEmit(Download.Done(File("model.litertlm"), "gemma-4-e2b-it"))

        val late = withTimeoutOrNull(200) { DownloadBus.events.first() }
        assertNull("a stale event reached a new collector", late)
    }

    @Test
    fun `a screen that is listening does receive the event`() = runBlocking {
        val listening = async(start = CoroutineStart.UNDISPATCHED) { DownloadBus.events.first() }
        val event = Download.Done(File("model.litertlm"), "lfm2.5-1.2b-instruct-int4")
        DownloadBus.events.emit(event)
        assertEquals(event, listening.await())
    }
}
