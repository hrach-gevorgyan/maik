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

    /** [detail] is the raw engine message, kept for the expandable section. */
    data class Broken(val summary: String, val detail: String, val refetch: Boolean) : Stage
}

enum class Screen { List, Chat, Settings }

/** Which compute unit the loaded engine ended up on. */
enum class Backend { GPU, CPU, NONE }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app.applicationContext)
    private val chats = ChatStore(app.applicationContext)

    val conversations = mutableStateListOf<Conversation>()

    /** The model the app is set to use. A chat may pin a different one. */
    val spec: ModelSpec get() = store.spec
    fun installedModels(): Set<String> = store.installed()
    fun bytesOnDisk(): Long = store.bytesOnDisk()

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

    var systemPrompt by mutableStateOf(DEFAULT_SYSTEM_PROMPT)
        private set
    var thinkingEnabled by mutableStateOf(true)
        private set

    /** Raw tokens from the current generation, reasoning tags and all. */
    var streaming by mutableStateOf("")
        private set

    /** The live split of [streaming] into reasoning and answer. */
    val live: Split get() = Split.of(streaming)

    /** When the current turn started, so the thinking indicator can count up. */
    var turnStartedAt by mutableStateOf(0L)
        private set

    /** How many old messages fell outside the context window on the last turn. */
    var dropped by mutableStateOf(0)
        private set

    private var engine: LlmInference? = null
    private var session: LlmInferenceSession? = null

    /** Which spec [engine] was built from, so a chat can demand a different one. */
    private var loadedId: String? = null

    /** Bumped per turn so a stopped generation's stray callbacks are ignored. */
    private var generation = 0

    val current: Conversation?
        get() = conversations.firstOrNull { it.id == currentId }

    /** The model a chat is held with: whatever it pinned, else the current choice. */
    fun modelFor(convo: Conversation?): ModelSpec =
        convo?.modelId?.let { Models.byId(it) } ?: store.spec

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
        thinkingEnabled = store.thinking
        systemPrompt = store.systemPrompt
        watchDownloads()
        stage = when {
            store.isReady() -> Stage.Loading.also { loadEngine(store.spec) }
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
        // A chat pinned to another model needs that model loaded before it can talk.
        ensureEngineFor(modelFor(conversations.firstOrNull { it.id == id }))
    }

    fun newChat() {
        val fresh = Conversation(id = UUID.randomUUID().toString(), title = "New chat")
        conversations.add(0, fresh)
        currentId = fresh.id
        query = ""
        screen = Screen.Chat
        ensureEngineFor(store.spec)
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

    /* ---------- settings ---------- */

    fun setThinking(enabled: Boolean) {
        store.setThinking(enabled)
        thinkingEnabled = enabled
    }

    fun updateSystemPrompt(text: String) {
        store.setSystemPrompt(text)
        systemPrompt = store.systemPrompt
    }

    /* ---------- model ---------- */

    fun selectModel(next: ModelSpec) {
        if (next.id == store.spec.id) return
        store.select(next)
        closeEngine()
        stage = if (store.isReady(next)) Stage.Loading.also { loadEngine(next) } else Stage.NeedsModel
    }

    /** Pins the open chat to a model, downloading or loading it if needed. */
    fun setModelForCurrentChat(next: ModelSpec) {
        val convo = current ?: return
        replace(convo.id) { it.copy(modelId = next.id) }
        persist()
        ensureEngineFor(next)
    }

    private fun ensureEngineFor(target: ModelSpec) {
        if (loadedId == target.id && stage is Stage.Ready) return
        if (!store.isReady(target)) {
            store.select(target)
            stage = Stage.NeedsModel
            return
        }
        closeEngine()
        loadEngine(target)
    }

    /** True when the active connection would bill you for the download. */
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
        stage = Stage.NeedsModel
    }

    private fun watchDownloads() {
        viewModelScope.launch {
            DownloadBus.state.collect { event ->
                when (event) {
                    is Download.Progress -> stage = Stage.Downloading(event.bytes, event.total)
                    is Download.Failed ->
                        if (event.reason == "Cancelled") stage = Stage.NeedsModel
                        else stage = Stage.Broken(event.reason, "", refetch = true)

                    is Download.Done -> if (stage !is Stage.Ready) loadEngine(store.spec)
                    null -> Unit
                }
            }
        }
    }

    fun retry() {
        if (store.isReady()) loadEngine(store.spec) else stage = Stage.NeedsModel
    }

    fun deleteModel(s: ModelSpec = store.spec) {
        if (s.id == loadedId) closeEngine()
        store.delete(s)
        if (s.id == store.spec.id) stage = Stage.NeedsModel
    }

    private fun loadEngine(target: ModelSpec) {
        stage = Stage.Loading
        viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) { openEngine(target) }
                engine = opened.first
                backend = opened.second
                loadedId = target.id
                stage = Stage.Ready
            } catch (e: Throwable) {
                backend = Backend.NONE
                loadedId = null
                val detail = e.message ?: e::class.java.simpleName
                // A bundle that is present but unreadable is almost always a bad
                // download, and the only cure is fetching it again.
                stage = Stage.Broken(
                    summary = "${target.label} could not be loaded.",
                    detail = detail,
                    refetch = true
                )
            }
        }
    }

    /**
     * Prefers the GPU, which is markedly faster, but falls back to CPU when the
     * delegate is refused — common enough across drivers to be worth handling.
     *
     * If both fail, the GPU error is reported: it is the one that explains why the
     * fast path was unavailable, and the CPU failure is usually a consequence.
     */
    private fun openEngine(target: ModelSpec): Pair<LlmInference, Backend> {
        val path = store.fileFor(target).absolutePath

        fun build(preferred: LlmInference.Backend) = LlmInference.createFromOptions(
            getApplication(),
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(target.contextTokens)
                .setPreferredBackend(preferred)
                .build()
        )

        val gpuFailure = try {
            return Pair(build(LlmInference.Backend.GPU), Backend.GPU)
        } catch (e: Throwable) {
            e
        }

        return try {
            Pair(build(LlmInference.Backend.CPU), Backend.CPU)
        } catch (_: Throwable) {
            throw gpuFailure
        }
    }

    private fun closeEngine() {
        generation++
        runCatching { session?.close() }
        runCatching { engine?.close() }
        session = null
        engine = null
        loadedId = null
        busy = false
        backend = Backend.NONE
    }

    /* ---------- generation ---------- */

    fun send(text: String) {
        val prompt = text.trim()
        val convo = current ?: return
        if (prompt.isEmpty() || busy) return

        val isFirst = convo.messages.isEmpty()
        replace(convo.id) {
            it.copy(
                title = if (isFirst) Conversation.titleFrom(prompt) else it.title,
                // Pin the model on the first exchange so the chat keeps one voice.
                modelId = it.modelId ?: store.spec.id,
                messages = it.messages + Message(prompt, fromUser = true),
                updatedAt = System.currentTimeMillis()
            )
        }
        bumpToTop(convo.id)
        persist()
        generate(convo.id)
    }

    /** Drops the last reply and asks again from the same point. */
    fun regenerate() {
        val convo = current ?: return
        if (busy) return
        val trimmed = convo.messages.dropLastWhile { !it.fromUser }
        if (trimmed.isEmpty()) return
        replace(convo.id) { it.copy(messages = trimmed) }
        persist()
        generate(convo.id)
    }

    private fun generate(conversationId: String) {
        val llm = engine
        if (llm == null || stage !is Stage.Ready) return

        busy = true
        streaming = ""
        turnStartedAt = System.currentTimeMillis()
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
     * Keeps whatever the reader has already seen and walks away from the rest.
     *
     * The native call can't be interrupted safely mid-flight, so rather than tear
     * the session down underneath it we stop listening: late tokens arrive with a
     * stale turn number and get discarded.
     */
    fun stop() {
        if (!busy) return
        val conversationId = currentId ?: return
        val split = Split.of(streaming)
        val seconds = thoughtSeconds()
        generation++
        session = null
        streaming = ""
        busy = false

        val kept = split.answer.trim().ifEmpty { split.reasoning.trim() }
        if (kept.isNotEmpty()) {
            replace(conversationId) {
                it.copy(
                    messages = it.messages + Message(
                        text = kept,
                        fromUser = false,
                        reasoning = split.reasoning.ifEmpty { null },
                        thoughtSeconds = seconds
                    ),
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
            if (done) {
                val split = Split.of(streaming)
                finish(
                    conversationId = conversationId,
                    text = split.answer.trim().ifEmpty { "…" },
                    isError = false,
                    reasoning = split.reasoning.ifEmpty { null }
                )
            }
        }
    }

    private fun finish(
        conversationId: String,
        text: String,
        isError: Boolean,
        reasoning: String? = null
    ) {
        val seconds = thoughtSeconds()
        runCatching { session?.close() }
        session = null
        streaming = ""
        busy = false
        replace(conversationId) {
            it.copy(
                messages = it.messages + Message(
                    text = text,
                    fromUser = false,
                    isError = isError,
                    reasoning = reasoning,
                    thoughtSeconds = if (reasoning != null) seconds else 0
                ),
                updatedAt = System.currentTimeMillis()
            )
        }
        persist()
    }

    private fun thoughtSeconds(): Int =
        if (turnStartedAt == 0L) 0
        else ((System.currentTimeMillis() - turnStartedAt) / 1000).toInt()

    /**
     * Renders the transcript in whichever format the loaded family expects.
     *
     * The KV cache is fixed when a model is converted, so a long transcript has to
     * be trimmed or generation fails outright. Keep the newest messages that fit and
     * report how many were dropped, rather than silently forgetting them.
     */
    private fun buildPrompt(conversationId: String): String {
        val convo = conversations.firstOrNull { it.id == conversationId } ?: return ""
        val model = modelFor(convo)
        val system = systemPrompt

        val history = convo.messages.filterNot { it.isError }
        // Leave a generous slice for the reply — a reasoning model spends much of
        // the window thinking before the first user-visible word appears.
        val inputBudget = (model.contextTokens * 0.55).toInt()
        var budget = inputBudget - estimateTokens(system) - 8
        val kept = ArrayDeque<Message>()

        for (message in history.asReversed()) {
            val cost = estimateTokens(message.text) + 8 // role tags and newlines
            if (budget - cost < 0 && kept.isNotEmpty()) break
            budget -= cost
            kept.addFirst(message)
        }
        dropped = history.size - kept.size

        return when (model.template) {
            Template.CHATML -> buildChatMl(system, kept.toList(), model)
            Template.GEMMA -> buildGemma(system, kept.toList())
        }
    }

    private fun buildChatMl(system: String, kept: List<Message>, model: ModelSpec) = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        kept.forEachIndexed { index, m ->
            append("<|im_start|>")
            append(if (m.fromUser) "user" else "assistant")
            append("\n")
            append(m.text)
            // Some families read /no_think as a per-turn switch; only the last counts.
            if (m.fromUser && index == kept.lastIndex && model.reasoning && !thinkingEnabled) {
                append(" /no_think")
            }
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /** Gemma has no system role, so the instructions ride along with the first turn. */
    private fun buildGemma(system: String, kept: List<Message>) = buildString {
        kept.forEachIndexed { index, m ->
            append("<start_of_turn>")
            append(if (m.fromUser) "user" else "model")
            append("\n")
            if (index == 0 && m.fromUser) append(system).append("\n\n")
            append(m.text)
            append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
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
        /** Rough for English, deliberately pessimistic so we under-fill. */
        fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 1
    }
}
