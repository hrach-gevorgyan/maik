package com.maik.app

import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The check that no unit test can make: a real model, downloaded onto a real
 * Android image, loaded by the real runtime, asked a real question.
 *
 * Every broken release so far failed exactly here — where a bundle meets the
 * runtime — so a release cannot publish unless this passes.
 */
@RunWith(AndroidJUnit4::class)
class GoldenTest {

    companion object {
        private lateinit var context: Context
        private lateinit var store: ModelStore

        // A model the app really offers, but the lighter of the two: the CI emulator
        // has 4 GB of RAM and a time limit, and Gemma's 2.6 GB would risk both. Same
        // runtime, same format, same code path.
        private val spec = Models.LFM_2_5_1_2B

        /** Loaded once for the whole class: reading 2 GB per question is not a test. */
        private var engine: Engine? = null

        @BeforeClass
        @JvmStatic
        fun fetchAndLoad() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            store = ModelStore(context)

            if (!store.isReady(spec)) {
                var failure: String? = null
                runBlocking {
                    store.download(spec).collect { event ->
                        if (event is Download.Failed) failure = event.reason
                    }
                }
                assertNull("the model failed to download: $failure", failure)
            }

            // CPU: emulators have no usable GPU, and the bundle is what's under test.
            engine = Engine(
                EngineConfig(
                    modelPath = store.fileFor(spec).absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = spec.contextTokens,
                    cacheDir = context.cacheDir.absolutePath
                )
            ).also { it.initialize() }
        }

        @AfterClass
        @JvmStatic
        fun release() {
            runCatching { engine?.close() }
            engine = null
        }
    }

    @Test
    fun theBundleDownloadsAndPassesValidation() {
        val file = store.fileFor(spec)
        assertTrue("no model file was produced", file.exists())
        assertTrue(
            "the downloaded bundle is smaller than it should be",
            file.length() > spec.approxBytes * 0.99
        )
        assertTrue("the store does not consider the model ready", store.isReady(spec))
    }

    @Test
    fun theEngineLoadsTheBundle() {
        assertTrue("the engine did not initialise", engine?.isInitialized() == true)
    }

    @Test
    fun theModelAnswersTheQuestionItWasAsked() {
        val reply = ask("What is the capital of France? Answer in one word.")
        assertTrue("the model returned nothing at all", reply.isNotBlank())
        assertTrue(
            "the answer ignored the question: $reply",
            reply.contains("paris", ignoreCase = true)
        )
    }

    @Test
    fun theReplyStopsOnItsOwnAndCarriesNoMarkup() {
        // The old runtime ignored stop tokens, so the model invented whole
        // conversations and leaked its control tokens. This runtime must not.
        val reply = ask("Say hello in one short sentence.")
        assertTrue("nothing came back", reply.isNotBlank())
        assertFalse("control tokens leaked: $reply", reply.contains("<|") || reply.contains("<start_of_turn>"))
        assertFalse("byte-level tokenizer debris: $reply", reply.contains("Ġ"))
        assertTrue("the reply ran on instead of stopping: ${reply.length} chars", reply.length < 600)
    }

    @Test
    fun aConversationRemembersWhatWasSaid() {
        val conversation = engine!!.createConversation(config())
        conversation.use {
            textOf(it.sendMessage("My name is Arman. Just say OK."))
            val reply = textOf(it.sendMessage("What is my name? Answer in one word."))
            assertTrue("the conversation forgot the earlier turn: $reply", reply.contains("arman", ignoreCase = true))
        }
    }

    /** One question in a fresh conversation, trimmed exactly as the app trims it. */
    private fun ask(question: String): String =
        engine!!.createConversation(config()).use { textOf(it.sendMessage(question)).trim() }

    private fun config() = ConversationConfig(
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
        // Thinking off: the test asks for short answers and wants them promptly.
        thinkingConfig = ThinkingConfig(false)
    )

    private fun textOf(message: com.google.ai.edge.litertlm.Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
}
