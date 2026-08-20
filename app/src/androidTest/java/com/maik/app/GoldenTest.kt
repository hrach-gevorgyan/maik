package com.maik.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * It uses a 159 MB fixture rather than one of the models the app offers, so running
 * it on every push stays affordable. The format, the loader and the generation path
 * are identical — only the weights are smaller.
 */
@RunWith(AndroidJUnit4::class)
class GoldenTest {

    companion object {
        private lateinit var context: Context
        private lateinit var store: ModelStore
        private val spec = Models.GOLDEN_TEST_MODEL
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
    fun theModelAnswersAQuestion() {
        engine().use { llm ->
            val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0.2f)
                .setTopK(20)
                .build()

            val reply = LlmInferenceSession.createFromOptions(llm, options).use { session ->
                session.addQueryChunk(
                    "<|im_start|>user\nName one colour.<|im_end|>\n<|im_start|>assistant\n"
                )
                session.generateResponse()
            }

            assertTrue("the model returned nothing at all", reply.isNotBlank())
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
