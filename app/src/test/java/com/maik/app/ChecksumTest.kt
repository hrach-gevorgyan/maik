package com.maik.app

import com.maik.app.data.ModelStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every downloaded model is accepted or rejected on this hash alone. */
class ChecksumTest {

    @Test
    fun `sha256 matches the published test vector`() = runBlocking {
        val file = File.createTempFile("maik", ".bin").apply {
            writeText("abc")
            deleteOnExit()
        }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelStore.sha256Of(file)
        )
    }

    @Test
    fun `sha256 of an empty file is the empty-input vector`() = runBlocking {
        val file = File.createTempFile("maik", ".bin").apply { deleteOnExit() }
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelStore.sha256Of(file)
        )
    }
}
