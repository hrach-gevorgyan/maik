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
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the engine is doing, independent of which screen you're looking at. */
sealed interface Stage {
    data object NeedsModel : Stage
    data class Downloading(val bytes: Long, val total: Long) : Stage {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }

    data object Loading : Stage
    data object Ready : Stage
    data class Broken(val reason: String) : Stage
}

enum class Screen { List, Chat, Settings }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app.applicationContext)
    private val chats = ChatStore(app.applicationContext)

    val conversations = mutableStateListOf<Conversation>()
    val spec: ModelSpec get() = store.spec
    fun installedModels(): Set<String> = store.installed()

    var screen by mutableStateOf(Screen.List)
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<Stage>(Stage.NeedsModel)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Tokens arriving from the current generation, before they're committed. */
    var streaming by mutableStateOf("")
        private set

    private var engine: LlmInference? = null
    private var session: LlmInferenceSession? = null

    val current: Conversation?
        get() = conversations.firstOrNull { it.id == currentId }

    init {
        conversations.addAll(chats.load())
        watchDownloads()
        stage = when {
            store.isReady() -> Stage.Loading.also { loadEngine() }
            DownloadBus.running.value -> Stage.Downloading(0, spec.approxBytes)
            else -> Stage.NeedsModel
        }
    }

    /* ---------- navigation ---------- */

    fun openList() {
        screen = Screen.List
        currentId = null
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    fun open(id: String) {
        currentId = id
        screen = Screen.Chat
    }

    fun newChat() {
        val fresh = Conversation(id = UUID.randomUUID().toString(), title = "New chat")
        conversations.add(0, fresh)
        currentId = fresh.id
        screen = Screen.Chat
    }

    fun delete(id: String) {
        conversations.removeAll { it.id == id }
        if (currentId == id) {
            currentId = null
            screen = Screen.List
        }
        persist()
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().ifEmpty { return }
        replace(id) { it.copy(title = clean) }
        persist()
    }

    fun deleteAll() {
        conversations.clear()
        currentId = null
        screen = Screen.List
        persist()
    }

    /* ---------- model ---------- */

    fun selectModel(next: ModelSpec) {
        if (next.id == store.spec.id) return
        closeEngine()
        store.select(next)
        stage = if (store.isReady()) Stage.Loading.also { loadEngine() } else Stage.NeedsModel
    }

    fun startDownload() {
        if (stage is Stage.Downloading) return
        stage = Stage.Downloading(0, spec.approxBytes)
        // Handed to a foreground service so it keeps going when the screen locks.
        DownloadService.start(getApplication())
    }

    fun cancelDownload() {
        getApplication<Application>().startService(
            android.content.Intent(getApplication(), DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL)
        )
    }

    /** Attaches to whatever the service is doing, whenever the UI comes back. */
    private fun watchDownloads() {
        viewModelScope.launch {
            DownloadBus.state.collect { event ->
                when (event) {
                    is Download.Progress -> stage = Stage.Downloading(event.bytes, event.total)
                    is Download.Failed -> stage = Stage.Broken(event.reason)
                    is Download.Done -> if (stage !is Stage.Ready) loadEngine()
                    null -> Unit
                }
            }
        }
    }

    fun retry() {
        if (store.isReady()) loadEngine() else stage = Stage.NeedsModel
    }

    fun deleteModel() {
        closeEngine()
        store.delete()
        stage = Stage.NeedsModel
    }

    private fun loadEngine() {
        stage = Stage.Loading
        viewModelScope.launch {
            try {
                engine = withContext(Dispatchers.IO) {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(store.fileFor().absolutePath)
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

    private fun closeEngine() {
        runCatching { session?.close() }
        runCatching { engine?.close() }
        session = null
        engine = null
    }

    /* ---------- generation ---------- */

    fun send(text: String) {
        val prompt = text.trim()
        val llm = engine
        val convo = current
        if (prompt.isEmpty() || busy || llm == null || convo == null || stage !is Stage.Ready) return

        val isFirst = convo.messages.isEmpty()
        replace(convo.id) {
            it.copy(
                title = if (isFirst) Conversation.titleFrom(prompt) else it.title,
                messages = it.messages + Message(prompt, fromUser = true),
                updatedAt = System.currentTimeMillis()
            )
        }
        bumpToTop(convo.id)
        persist()

        busy = true
        streaming = ""
        val conversationId = convo.id

        viewModelScope.launch {
            try {
                val built = buildPrompt(conversationId)
                withContext(Dispatchers.Default) {
                    val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTemperature(0.7f)
                        .setTopK(40)
                        .build()
                    // A fresh session per turn: we replay the transcript ourselves, so
                    // "delete" genuinely deletes and context never leaks across chats.
                    val s = LlmInferenceSession.createFromOptions(llm, options)
                    session = s
                    s.addQueryChunk(built)
                    s.generateResponseAsync(ProgressListener<String> { partial, done ->
                        onToken(conversationId, partial, done)
                    })
                }
            } catch (e: Throwable) {
                finish(conversationId, e.message ?: e::class.java.simpleName, isError = true)
            }
        }
    }

    private fun onToken(conversationId: String, partial: String?, done: Boolean) {
        // Callbacks arrive off the main thread; hop back before touching state.
        viewModelScope.launch(Dispatchers.Main) {
            streaming += partial.orEmpty()
            if (done) finish(conversationId, streaming.trim().ifEmpty { "…" }, isError = false)
        }
    }

    private fun finish(conversationId: String, text: String, isError: Boolean) {
        runCatching { session?.close() }
        session = null
        streaming = ""
        busy = false
        replace(conversationId) {
            it.copy(
                messages = it.messages + Message(text, fromUser = false, isError = isError),
                updatedAt = System.currentTimeMillis()
            )
        }
        persist()
    }

    /** Qwen2.5 expects ChatML; the bundle ships a tokenizer, not a chat template. */
    private fun buildPrompt(conversationId: String): String {
        val convo = conversations.firstOrNull { it.id == conversationId } ?: return ""
        return buildString {
            append("<|im_start|>system\n")
            append("You are maik, a helpful assistant running entirely on the user's phone. ")
            append("Answer clearly and concisely.")
            append("<|im_end|>\n")
            convo.messages.filterNot { it.isError }.forEach { m ->
                append("<|im_start|>${if (m.fromUser) "user" else "assistant"}\n")
                append(m.text)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    /* ---------- plumbing ---------- */

    private inline fun replace(id: String, transform: (Conversation) -> Conversation) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index >= 0) conversations[index] = transform(conversations[index])
    }

    private fun bumpToTop(id: String) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index > 0) conversations.add(0, conversations.removeAt(index))
    }

    private fun persist() = chats.save(conversations.toList())

    override fun onCleared() {
        super.onCleared()
        closeEngine()
    }
}
