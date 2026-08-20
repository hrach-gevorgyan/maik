package com.maik.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

/** Which compute unit the loaded engine actually ended up on. */
enum class Backend { GPU, CPU, NONE }

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
    var backend by mutableStateOf(Backend.NONE)
        private set
    var query by mutableStateOf("")

    /** Tokens arriving from the current generation, before they're committed. */
    var streaming by mutableStateOf("")
        private set

    /** How many old messages fell outside the context window on the last turn. */
    var dropped by mutableStateOf(0)
        private set

    private var engine: LlmInference? = null
    private var session: LlmInferenceSession? = null

    /** Bumped per turn so a stopped generation's stray callbacks are ignored. */
    private var generation = 0

    val current: Conversation?
        get() = conversations.firstOrNull { it.id == currentId }

    val visibleConversations: List<Conversation>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return conversations
            return conversations.filter { convo ->
                convo.title.contains(q, ignoreCase = true) ||
                    convo.messages.any { it.text.contains(q, ignoreCase = true) }
            }
        }

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
        query = ""
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

    /** True when the active connection would bill you for a 1.5 GB download. */
    fun onMeteredNetwork(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun startDownload() {
        if (stage is Stage.Downloading) return
        stage = Stage.Downloading(0, spec.approxBytes)
        // Handed to a foreground service so it keeps going when the screen locks.
        DownloadService.start(getApplication())
    }

    fun cancelDownload() {
        getApplication<Application>().startService(
            Intent(getApplication(), DownloadService::class.java)
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
                val opened = withContext(Dispatchers.IO) { openEngine() }
                engine = opened.first
                backend = opened.second
                stage = Stage.Ready
            } catch (e: Throwable) {
                // A truncated or corrupt bundle can only be fixed by refetching it.
                store.delete()
                backend = Backend.NONE
                stage = Stage.Broken(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /**
     * Try the GPU first — it's markedly faster on modern chips — but plenty of
     * devices and drivers refuse the delegate, so fall back instead of dying.
     */
    private fun openEngine(): Pair<LlmInference, Backend> {
        val path = store.fileFor().absolutePath

        fun build(preferred: LlmInference.Backend) = LlmInference.createFromOptions(
            getApplication(),
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .setPreferredBackend(preferred)
                .build()
        )

        return try {
            Pair(build(LlmInference.Backend.GPU), Backend.GPU)
        } catch (_: Throwable) {
            Pair(build(LlmInference.Backend.CPU), Backend.CPU)
        }
    }

    private fun closeEngine() {
        generation++
        runCatching { session?.close() }
        runCatching { engine?.close() }
        session = null
        engine = null
        busy = false
        backend = Backend.NONE
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
        val turn = ++generation

        viewModelScope.launch {
            try {
                val built = buildPrompt(conversationId)
                withContext(Dispatchers.Default) {
                    val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTemperature(0.7f)
                        .setTopK(40)
                        .build()
                    // A fresh session per turn: we replay the transcript ourselves, so
                    // deleting a chat genuinely deletes its context.
                    val s = LlmInferenceSession.createFromOptions(llm, options)
                    session = s
                    s.addQueryChunk(built)
                    s.generateResponseAsync(ProgressListener<String> { partial, done ->
                        onToken(turn, conversationId, partial, done)
                    })
                }
            } catch (e: Throwable) {
                if (turn == generation) {
                    finish(conversationId, e.message ?: e::class.java.simpleName, isError = true)
                }
            }
        }
    }

    /**
     * Keeps whatever the user has already seen and walks away from the rest.
     *
     * The native call can't be interrupted safely mid-flight, so rather than tear
     * the session down underneath it we stop listening: late tokens arrive with a
     * stale turn number and get discarded.
     */
    fun stop() {
        if (!busy) return
        val conversationId = currentId ?: return
        val partial = streaming.trim()
        generation++
        session = null
        streaming = ""
        busy = false
        if (partial.isNotEmpty()) {
            replace(conversationId) {
                it.copy(
                    messages = it.messages + Message(partial, fromUser = false),
                    updatedAt = System.currentTimeMillis()
                )
            }
            persist()
        }
    }

    private fun onToken(turn: Int, conversationId: String, partial: String?, done: Boolean) {
        // Callbacks arrive off the main thread; hop back before touching state.
        viewModelScope.launch(Dispatchers.Main) {
            if (turn != generation) {
                // A stopped or superseded turn. Let it finish, then drop it.
                if (done) runCatching { session?.close() }
                return@launch
            }
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

    /**
     * Qwen2.5 expects ChatML; the bundle ships a tokenizer, not a chat template.
     *
     * The KV cache is fixed at [MAX_TOKENS] when the model is converted, so a long
     * transcript has to be trimmed or generation fails outright. Keep the newest
     * messages that fit and report how many were dropped, rather than silently
     * forgetting them.
     */
    private fun buildPrompt(conversationId: String): String {
        val convo = conversations.firstOrNull { it.id == conversationId } ?: return ""
        val system = "You are maik, a helpful assistant running entirely on the user " +
            "device. Answer clearly and concisely."

        val history = convo.messages.filterNot { it.isError }
        var budget = INPUT_TOKEN_BUDGET - estimateTokens(system) - 8
        val kept = ArrayDeque<Message>()

        for (message in history.asReversed()) {
            val cost = estimateTokens(message.text) + 8 // role tags and newlines
            if (budget - cost < 0 && kept.isNotEmpty()) break
            budget -= cost
            kept.addFirst(message)
        }
        dropped = history.size - kept.size

        return buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            kept.forEach { m ->
                append("<|im_start|>")
                append(if (m.fromUser) "user" else "assistant")
                append("\n")
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

    companion object {
        /** Must not exceed the `ekv` figure baked into the bundle's filename. */
        const val MAX_TOKENS = 1280

        /** The remainder of the window is left free for the reply. */
        const val INPUT_TOKEN_BUDGET = 880

        /** Rough for English, deliberately pessimistic so we under-fill. */
        fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 1
    }
}
