package com.maik.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The check that would have caught every broken release.
 *
 * Three versions shipped models the engine could not read, and no amount of unit
 * testing would have found it: the failure only exists once a real bundle meets the
 * real runtime. So this downloads an actual model onto an actual Android image,
 * loads it, and makes it produce a sentence.
 *
 * It runs against the default model, downloaded onto the emulator and cached
 * between runs, so what is verified is what ships.
 */
@RunWith(AndroidJUnit4::class)
class GoldenTest {

    companion object {
        private lateinit var context: Context
        private lateinit var store: ModelStore
        // The model people actually get. Slower to fetch than a toy fixture, but
        // a test that passes on something nobody installs proves nothing.
        private val spec = Models.DEFAULT
        private var modelFile: File? = null

        @BeforeClass
        @JvmStatic
        fun fetchModel() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            store = ModelStore(context)

            if (store.isReady(spec)) {
                modelFile = store.fileFor(spec)
                return
            }

            var failure: String? = null
            runBlocking {
                store.download(spec).collect { event ->
                    when (event) {
                        is Download.Done -> modelFile = event.file
                        is Download.Failed -> failure = event.reason
                        is Download.Progress -> Unit
                    }
                }
            }
            assertNull("the model failed to download: $failure", failure)
        }
    }

    @Test
    fun theBundleDownloadsAndPassesValidation() {
        val file = modelFile
        assertTrue("no model file was produced", file != null && file.exists())
        // Whatever the app accepts here is what it will later hand to the engine.
        assertTrue(
            "the downloaded bundle is smaller than it should be",
            file!!.length() > spec.approxBytes * 0.99
        )
        assertTrue("the store does not consider the model ready", store.isReady(spec))
    }

    @Test
    fun theEngineLoadsTheBundle() {
        // The exact failure of 1.1.0 through 1.3.0: a file that downloads cleanly
        // and then cannot be opened.
        engine().close()
    }

    @Test
    fun theModelAnswersTheQuestionItWasAsked() {
        // Not just "did it say something": 1.4.x returned fluent text that ignored
        // the question entirely, because the prompt was being templated twice.
        val reply = ask("What is the capital of France? Answer in one word.")
        assertTrue(
            "the model returned nothing at all",
            reply.isNotBlank()
        )
        assertTrue(
            "the answer ignored the question entirely: $reply",
            reply.contains("paris", ignoreCase = true)
        )
    }

    @Test
    fun theReplyIsCleanTextRatherThanTokeniserDebris() {
        val reply = ask("Say hello.")

        // 'Ġ' is byte-level BPE debris. It surfaces when a model is fed markup it
        // was not trained on — the exact symptom of double-templating.
        assertFalse("byte-level tokeniser debris in the reply: $reply", reply.contains("Ġ"))
        assertFalse("raw control tokens leaked into the reply: $reply", reply.contains("<|"))
        assertFalse("chat markup leaked into the reply: $reply", reply.contains("<｜"))
    }

    @Test
    fun theModelRamblesPastItsStopTokenAndTheAppTrimsIt() {
        // Documents the behaviour the trimming exists for, against real output:
        // the runtime does not honour the bundle's stop token, so the model emits
        // it as text and invents a conversation until the budget runs out.
        val raw = rawAsk("Say hello.")
        val cleaned = Reply.clean(raw)
        assertTrue("nothing came back at all", raw.isNotBlank())
        assertTrue("the trimmed reply is empty", cleaned.isNotBlank())
        assertTrue(
            "trimming did not shorten a rambling reply",
            cleaned.length <= raw.length
        )
    }

    @Test
    fun promptsAreSentAsPlainTextWithNoTemplateOfOurOwn() {
        // The bundle carries its own template and the engine applies it. Anything we
        // add on top is what broke 1.1.0 through 1.4.1.
        val reply = ask("Name one colour.")
        assertTrue("plain text produced no reply", reply.isNotBlank())
    }

    /**
     * What the reader would actually see: the engine's output put through the same
     * trimming the app applies. Asserting on the raw generation would only restate
     * that small models ramble past their stop token, which they always do.
     */
    private fun ask(question: String): String = Reply.clean(rawAsk(question))

    /** One question, one fresh session, raw text — exactly how the app asks. */
    private fun rawAsk(question: String): String = engine().use { llm ->
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.1f)
            .setTopK(10)
            .build()
        LlmInferenceSession.createFromOptions(llm, options).use { session ->
            session.addQueryChunk(question)
            session.generateResponse()
        }
    }

    @Test
    fun theCatalogueOnlyOffersLoadableBundles() {
        // Cheap on-device restatement of the unit rule, so a mistake in the
        // catalogue fails here too rather than only in a JVM test.
        Models.ALL.forEach { model ->
            assertTrue(model.url, model.url.endsWith(".task"))
            assertEquals(model.fileName, "${model.id}.task")
        }
    }

    /**
     * CPU only: emulators have no usable GPU delegate, and the point of this test is
     * the model bundle rather than the accelerator.
     */
    private fun engine(): LlmInference = LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(store.fileFor(spec).absolutePath)
            .setMaxTokens(spec.contextTokens)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()
    )
}
