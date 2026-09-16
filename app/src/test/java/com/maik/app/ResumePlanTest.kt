package com.maik.app

import com.maik.app.data.ResumePlan
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
}
