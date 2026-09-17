package com.maik.app

import com.maik.app.data.ResumePlan
import com.maik.app.data.ResumePlan.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** An interrupted 2.6 GB download used to start again from zero, or splice two files. */
class ResumePlanTest {

    private val size = 2_588_147_712L

    @Test
    fun `a fresh download starts at zero`() {
        assertEquals(Outcome.Copy(0, size, resuming = false), ResumePlan.decide(0, 200, size, size, null))
    }

    @Test
    fun `a 206 continues from the bytes already on disk`() {
        val have = 1_000_000_000L
        assertEquals(Outcome.Copy(have, size, resuming = true), ResumePlan.decide(have, 206, size - have, size, have))
    }

    @Test
    fun `a 200 to a range request starts over rather than appending a second copy`() {
        assertEquals(Outcome.Copy(0, size, resuming = false), ResumePlan.decide(1_000_000_000L, 200, size, size, null))
    }

    @Test
    fun `a 206 of unknown length falls back to the model's known size`() {
        assertEquals(Outcome.Copy(500, size, resuming = true), ResumePlan.decide(500, 206, -1, size, 500))
    }

    @Test
    fun `a 416 on a complete partial means it is already downloaded`() {
        assertEquals(Outcome.AlreadyComplete, ResumePlan.decide(size, 416, -1, size, null))
    }

    @Test
    fun `a 416 on an oversized partial starts again`() {
        assertEquals(Outcome.RestartFromZero, ResumePlan.decide(size + 10, 416, -1, size, null))
    }

    @Test
    fun `a 206 from the wrong offset would splice files, so start again`() {
        assertEquals(Outcome.RestartFromZero, ResumePlan.decide(100, 206, size - 100, size, 0))
    }

    @Test
    fun `a 206 for a fresh download is a plain copy`() {
        assertEquals(Outcome.Copy(0, size, resuming = false), ResumePlan.decide(0, 206, size, size, 0))
    }

    @Test
    fun `a partial bigger than the model is never continued`() {
        assertEquals(Outcome.RestartFromZero, ResumePlan.decide(size + 1, 206, 5, size, size + 1))
    }

    @Test
    fun `content range start is parsed or null`() {
        assertEquals(100L, ResumePlan.parseContentRangeStart("bytes 100-199/200"))
        assertEquals(null, ResumePlan.parseContentRangeStart("bytes */200"))
        assertEquals(null, ResumePlan.parseContentRangeStart(null))
    }
}
