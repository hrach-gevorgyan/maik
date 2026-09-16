package com.maik.app

import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules exist because breaking each of them shipped a broken release.
 *
 * Earlier versions listed models the runtime could not load, GPU-only builds that
 * failed on the CPU fallback, and a 3.7 GB model that locked the phone up.
 */
class ModelCatalogTest {

    @Test
    fun `every bundle is a LiteRT-LM file`() {
        Models.ALL.forEach { model ->
            assertTrue(
                "${model.label} must be a .litertlm bundle: ${model.url}",
                model.url.endsWith(".litertlm")
            )
        }
    }

    @Test
    fun `no GPU-only bundles, since they cannot fall back to CPU`() {
        // A -gpu bundle fails on the CPU backend, which is exactly where the app lands
        // whenever the GPU is refused — and CPU is the default.
        Models.ALL.forEach { model ->
            assertTrue(
                "${model.label} must not be a GPU-only build: ${model.url}",
                !model.url.contains("-gpu.") && !model.url.contains("_gpu.")
            )
        }
    }

    @Test
    fun `no web or chip-specific bundles`() {
        // Web builds target browsers; chip-specific builds only load on that one SoC.
        Models.ALL.forEach { model ->
            val name = model.url.substringAfterLast('/').lowercase()
            assertTrue("${model.label} is a web build", "web" !in name)
            assertTrue(
                "${model.label} is tied to one chip",
                listOf("qualcomm", "tensor", "intel", "mediatek").none { it in name }
            )
        }
    }

    @Test
    fun `every source is an ungated litert-community repo over https`() {
        Models.ALL.forEach { model ->
            assertTrue(model.url, model.url.startsWith("https://huggingface.co/litert-community/"))
        }
    }

    @Test
    fun `ids are unique and filenames keep the bundle extension`() {
        assertEquals(Models.ALL.size, Models.ALL.map { it.id }.toSet().size)
        Models.ALL.forEach { assertTrue(it.fileName, it.fileName.endsWith(".litertlm")) }
    }

    @Test
    fun `declared sizes are plausible, never zero or a placeholder`() {
        Models.ALL.forEach { model ->
            assertTrue("${model.label} has an implausible size", model.approxBytes > 50_000_000L)
        }
    }

    @Test
    fun `an unknown or missing id falls back to the default`() {
        assertEquals(Models.DEFAULT, Models.byId(null))
        assertEquals(Models.LFM_2_5_1_2B, Models.byId(Models.LFM_2_5_1_2B.id))
        // Chats pinned to models that were removed must still open.
        listOf(
            "deepseek-r1-distill-1.5b-q8",
            "qwen2.5-1.5b-instruct-q8",
            "qwen3.5-2b-int8",
            "phi-4-mini-q8",
            "tinyllama-1.1b-q8"
        ).forEach { assertEquals(it, Models.DEFAULT, Models.byId(it)) }
    }

    @Test
    fun `nothing is offered that a phone cannot actually run`() {
        // Phi-4-mini at 3.7 GB throttled the device, took over a minute per answer
        // and then locked up. Size is not a feature if the result is unusable.
        Models.ALL.forEach { model ->
            assertTrue(
                "${model.label} is too big to be a sensible suggestion",
                model.approxBytes <= Models.MAX_SENSIBLE_BYTES
            )
        }
    }

    @Test
    fun `there is more than one model, so a bad one is not a dead end`() {
        assertTrue(Models.ALL.size >= 2)
    }

    @Test
    fun `the default is one of the offered models`() {
        assertTrue(Models.DEFAULT in Models.ALL)
    }

    @Test
    fun `every download is pinned to a commit and has a checksum`() {
        Models.ALL.forEach { spec ->
            assertFalse("${spec.id} follows a moving branch", spec.url.contains("/resolve/main/"))
            assertTrue("${spec.id} is not pinned", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(spec.url))
            assertTrue("${spec.id} has no sha256", Regex("^[0-9a-f]{64}$").matches(spec.sha256))
        }
    }
}
