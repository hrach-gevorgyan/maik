package com.maik.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Message(
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false
)

/** What the whole screen is doing, top level. */
sealed interface Stage {
    data object NeedsModel : Stage
    data class Downloading(val bytes: Long, val total: Long) : Stage {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }

    data object Loading : Stage
    data object Ready : Stage
    data class Broken(val reason: String) : Stage
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app.applicationContext)
    val spec = store.spec

    val messages = mutableStateListOf<Message>()
    var stage by mutableStateOf<Stage>(if (store.isReady()) Stage.Loading else Stage.NeedsModel)
        private set
    var busy by mutableStateOf(false)
        private set

    private var engine: LlmInference? = null

    init {
        if (store.isReady()) loadEngine()
    }

    fun startDownload() {
        if (stage is Stage.Downloading) return
        stage = Stage.Downloading(0, spec.approxBytes)
        viewModelScope.launch {
            store.download().collect { event ->
                when (event) {
                    is Download.Progress -> stage = Stage.Downloading(event.bytes, event.total)
                    is Download.Failed -> stage = Stage.Broken(event.reason)
                    is Download.Done -> loadEngine()
                }
            }
        }
    }

    private fun loadEngine() {
        stage = Stage.Loading
        viewModelScope.launch {
            try {
                engine = withContext(Dispatchers.IO) {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(store.file.absolutePath)
                        .setMaxTokens(1280)
                        .build()
                    LlmInference.createFromOptions(getApplication(), options)
                }
                stage = Stage.Ready
            } catch (e: Throwable) {
                // A truncated or corrupt bundle can only be fixed by refetching it.
                store.delete()
                stage = Stage.Broken(e.message ?: e::class.java.simpleName)
            }
        }
    }

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        val llm = engine
        if (trimmed.isEmpty() || busy || llm == null || stage !is Stage.Ready) return

        messages += Message(trimmed, fromUser = true)
        busy = true

        viewModelScope.launch {
            val reply = try {
                withContext(Dispatchers.Default) {
                    // A fresh session per turn; we replay the transcript ourselves so
                    // that "clear" genuinely clears and context never leaks between runs.
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTemperature(0.7f)
                        .setTopK(40)
                        .build()
                    LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
                        session.addQueryChunk(buildPrompt())
                        session.generateResponse()
                    }
                }.trim()
            } catch (e: Throwable) {
                messages += Message(
                    e.message ?: e::class.java.simpleName,
                    fromUser = false,
                    isError = true
                )
                busy = false
                return@launch
            }

            messages += Message(reply.ifEmpty { "…" }, fromUser = false)
            busy = false
        }
    }

    fun clear() {
        if (!busy) messages.clear()
    }

    fun retry() {
        stage = if (store.isReady()) Stage.Loading.also { loadEngine() } else Stage.NeedsModel
    }

    /** Qwen2.5 expects ChatML; the bundle ships a tokenizer, not a chat template. */
    private fun buildPrompt(): String = buildString {
        append("<|im_start|>system\n")
        append("You are maik, a concise assistant running entirely on the user's phone.")
        append("<|im_end|>\n")
        messages.filterNot { it.isError }.forEach { m ->
            append("<|im_start|>${if (m.fromUser) "user" else "assistant"}\n")
            append(m.text)
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
        engine = null
    }
}
