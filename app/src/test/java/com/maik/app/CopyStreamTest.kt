package com.maik.app

import com.maik.app.data.ModelStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The progress bar used to stop short of 100% when the size wasn't a round number. */
class CopyStreamTest {

    private fun copy(input: ByteArray, start: Long): Pair<ByteArray, List<Long>> = runBlocking {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        ModelStore.copyStream(ByteArrayInputStream(input), out, start) { seen += it }
        out.toByteArray() to seen
    }

    @Test
    fun `copies every byte and always reports the end`() {
        val input = ByteArray(10 * 1024 * 1024 + 7) { (it % 251).toByte() }
        val (out, seen) = copy(input, 0)
        assertArrayEquals(input, out)
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
        assertEquals(input.size.toLong(), seen.last())
    }

    @Test
    fun `a resumed copy counts from where it started`() {
        val input = ByteArray(5_000_000)
        val (_, seen) = copy(input, 1000)
        assertTrue(seen.first() > 1000)
        assertEquals(1000L + input.size, seen.last())
    }

    @Test
    fun `an empty stream reports once`() {
        val (_, seen) = copy(ByteArray(0), 0)
        assertEquals(listOf(0L), seen)
    }
}
