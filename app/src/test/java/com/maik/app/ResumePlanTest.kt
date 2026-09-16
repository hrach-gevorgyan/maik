package com.maik.app

import com.maik.app.data.ResumePlan
import com.maik.app.data.ResumePlan.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An interrupted 2.6 GB download used to start again from zero. */
class ResumePlanTest {

    private val size = 2_588_147_712L

    @Test
    fun `a fresh download starts at zero`() {
        val plan = ResumePlan.of(have = 0, responseCode = 200, contentLength = size, expectedTotal = size)
        assertFalse(plan.resuming)
        assertEquals(0L, plan.start)
        assertEquals(size, plan.total)
    }

    @Test
    fun `a 206 continues from the bytes already on disk`() {
        val have = 1_000_000_000L
        val plan = ResumePlan.of(have, responseCode = 206, contentLength = size - have, expectedTotal = size)
        assertTrue(plan.resuming)
        assertEquals(have, plan.start)
        assertEquals(size, plan.total)
    }

    @Test
    fun `a 200 to a range request means start over, not append a second copy`() {
        val plan = ResumePlan.of(have = 1_000_000_000L, responseCode = 200, contentLength = size, expectedTotal = size)
        assertFalse(plan.resuming)
        assertEquals(0L, plan.start)
        assertEquals(size, plan.total)
    }

    @Test
    fun `an unknown length falls back to the model's known size`() {
        val have = 500L
        val plan = ResumePlan.of(have, responseCode = 206, contentLength = -1, expectedTotal = size)
        assertEquals(size, plan.total)
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
    fun `a 206 from the right offset continues`() {
        assertEquals(Outcome.Copy(100, size, resuming = true), ResumePlan.decide(100, 206, size - 100, size, 100))
    }

    @Test
    fun `a 206 for a fresh download is a plain copy`() {
        assertEquals(Outcome.Copy(0, size, resuming = false), ResumePlan.decide(0, 206, size, size, 0))
    }

    @Test
    fun `a 200 ignores the partial and copies from zero`() {
        assertEquals(Outcome.Copy(0, size, resuming = false), ResumePlan.decide(100, 200, -1, size, null))
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
