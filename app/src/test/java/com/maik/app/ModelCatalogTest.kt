package com.maik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules exist because breaking each of them shipped a broken release.
 *
 * 1.1.0 and 1.2.0 both listed models the runtime cannot load. The checks below are
 * derived from what the bundles actually contain, verified by reading their ZIP
 * directories over HTTP range requests.
 */
class ModelCatalogTest {

    @Test
    fun `every bundle is a task file`() {
        // LiteRT-LM .litertlm bundles carry no SentencePiece tokenizer and fail with
        // "SentencePiece tokenizer not found". Only .task ZIPs work.
        Models.ALL.forEach { model ->
            assertTrue(
                "${model.label} must be a .task bundle: ${model.url}",
                model.url.endsWith(".task")
            )
        }
    }

    @Test
    fun `no GPU-only bundles, since they cannot fall back to CPU`() {
        // A -gpu bundle fails on the CPU executor, which is exactly where the app
        // lands whenever the GPU delegate is refused.
        Models.ALL.forEach { model ->
            assertTrue(
                "${model.label} must not be a GPU-only build: ${model.url}",
                !model.url.contains("-gpu.") && !model.url.contains("_gpu.")
            )
        }
    }

    @Test
    fun `no web bundles, which are raw tflite rather than task archives`() {
        Models.ALL.forEach { model ->
            assertTrue(
                "${model.label} must not be a web build: ${model.url}",
                !model.url.contains("-web") && !model.url.contains("_web")
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
    fun `context matches the ekv size baked into the bundle filename`() {
        // Asking for more tokens than the KV cache holds fails at generation time.
        Models.ALL.forEach { model ->
            val ekv = Regex("ekv(\\d+)").find(model.url)?.groupValues?.get(1)?.toInt()
            if (ekv != null) {
                assertEquals(
                    "${model.label} declares a context its bundle cannot serve",
                    ekv,
                    model.contextTokens
                )
            }
        }
    }

    @Test
    fun `ids are unique and filenames keep the task extension`() {
        assertEquals(Models.ALL.size, Models.ALL.map { it.id }.toSet().size)
        Models.ALL.forEach { assertTrue(it.fileName, it.fileName.endsWith(".task")) }
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
        // Chats pinned to models dropped from the catalogue must still open.
        assertEquals(Models.DEFAULT, Models.byId("qwen2.5-1.5b-instruct-q8-4k"))
        assertEquals(Models.PHI_4_MINI, Models.byId(Models.PHI_4_MINI.id))
    }

    @Test
    fun `the default is the smallest, so a first run proves itself cheaply`() {
        assertEquals(Models.ALL.minByOrNull { it.approxBytes }, Models.DEFAULT)
        assertTrue("the default should be flagged as a quick check", Models.DEFAULT.isProbe)
    }

    @Test
    fun `only the probe is marked as one`() {
        assertEquals(1, Models.ALL.count { it.isProbe })
    }
}
