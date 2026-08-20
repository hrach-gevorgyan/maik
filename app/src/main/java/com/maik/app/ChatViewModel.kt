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

enum class Screen { List, Chat, Settings, Setup }

/** Settings is a menu of pages, not one long scroll. */
enum class SettingsPage { Root, Models, Appearance, Behaviour, Instructions, Storage, About }

/** Which compute unit the loaded engine ended up on. */
enum class Backend { GPU, CPU, NONE }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app.applicationContext)
    private val chats = ChatStore(app.applicationContext)

    val conversations = mutableStateListOf<Conversation>()

    /** The model the app is set to use. A chat may pin a different one. */
    val spec: ModelSpec get() = store.spec

    /**
     * What the setup screen should fetch. Usually [spec], but opening a chat that
     * is pinned to a model you haven't downloaded points it there instead —
     * without quietly changing what new chats will use.
     */
    var target by mutableStateOf(Models.DEFAULT)
        private set
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

    var settingsPage by mutableStateOf(SettingsPage.Root)
        private set
    var themeMode by mutableStateOf(ThemeMode.LIGHT)
        private set
    var systemPrompt by mutableStateOf(DEFAULT_SYSTEM_PROMPT)
        private set
    var hapticsEnabled by mutableStateOf(true)
        private set
    var useGpu by mutableStateOf(false)
        private set

    /**
     * Bumped whenever a model file appears or disappears. Reading the disk during
     * composition is not observable state, which is why deleting a model used to
     * leave the row sitting there.
     */
    var storageVersion by mutableStateOf(0)
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

    /** Which conversation [session] holds the context of. */
    private var sessionOwner: String? = null

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
        themeMode = store.themeMode
        hapticsEnabled = store.haptics

        // If the process died during the last load, the GPU delegate is the prime
        // suspect: it crashes natively on some drivers and cannot be caught.
        useGpu = store.useGpu && !store.lastLoadCrashed()
        if (store.lastLoadCrashed()) {
            store.setUseGpu(false)
            store.endRiskyLoad()
        }
        watchDownloads()
        target = store.spec
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

    /** Brings the download into view, e.g. when returning from its notification. */
    fun showDownload() {
        screen = Screen.Setup
    }

    fun openSettings() {
        settingsPage = SettingsPage.Root
        screen = Screen.Settings
    }

    fun openSettingsPage(page: SettingsPage) {
        settingsPage = page
    }

    /**
     * One step back, wherever we are. Settings sub-pages return to the settings
     * menu first, so Back never skips a level.
     */
    fun back() {
        when {
            screen == Screen.Settings && settingsPage != SettingsPage.Root ->
                settingsPage = SettingsPage.Root

            screen == Screen.Setup && stage is Stage.Downloading -> openSettings()

            else -> openList()
        }
    }

    fun open(id: String) {
        currentId = id
        dropped = 0
        screen = Screen.Chat
        // A chat pinned to another model needs that model loaded before it can talk.
        ensureEngineFor(modelFor(conversations.firstOrNull { it.id == id }))
    }

    fun newChat() {
        val fresh = Conversation(id = UUID.randomUUID().toString(), title = "New chat")
        conversations.add(0, fresh)
        currentId = fresh.id
        dropped = 0
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

    fun updateThemeMode(mode: ThemeMode) {
        store.setThemeMode(mode)
        themeMode = mode
    }

    fun setThinking(enabled: Boolean) {
        store.setThinking(enabled)
        thinkingEnabled = enabled
    }

    fun updateHaptics(enabled: Boolean) {
        store.setHaptics(enabled)
        hapticsEnabled = enabled
    }

    fun updateUseGpu(enabled: Boolean) {
        store.setUseGpu(enabled)
        useGpu = enabled
        if (stage is Stage.Ready || stage is Stage.Broken) {
            closeEngine()
            if (store.isReady(target)) loadEngine(target)
        }
    }

    fun updateSystemPrompt(text: String) {
        store.setSystemPrompt(text)
        systemPrompt = store.systemPrompt
    }

    /* ---------- model ---------- */

    /**
      * Picks the model new chats will use. If it isn't downloaded yet, go straight
      * to the download screen — tapping a model and having nothing visible happen
      * is the same as the app being broken.
      */
    fun selectModel(next: ModelSpec) {
        val alreadyCurrent = next.id == store.spec.id
        store.select(next)
        target = next

        if (store.isReady(next)) {
            if (!alreadyCurrent || stage !is Stage.Ready) {
                closeEngine()
                loadEngine(next)
            }
            if (screen == Screen.Setup) screen = Screen.List
        } else {
            closeEngine()
            stage = Stage.NeedsModel
            screen = Screen.Setup
        }
    }

    /** Pins the open chat to a model, downloading or loading it if needed. */
    fun setModelForCurrentChat(next: ModelSpec) {
        val convo = current ?: return
        replace(convo.id) { it.copy(modelId = next.id) }
        persist()
        ensureEngineFor(next)
    }

    private fun ensureEngineFor(wanted: ModelSpec) {
        target = wanted
        if (loadedId == wanted.id && stage is Stage.Ready) return
        if (!store.isReady(wanted)) {
            stage = Stage.NeedsModel
            return
        }
        closeEngine()
        loadEngine(wanted)
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
        stage = Stage.Downloading(0, target.approxBytes)
        // Handed to a foreground service so it keeps going when the screen locks.
        DownloadService.start(getApplication(), target.id)
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

                    is Download.Done -> {
                        storageVersion++
                        if (stage !is Stage.Ready) loadEngine(target)
                    }
                    null -> Unit
                }
            }
        }
    }

    fun retry() {
        if (store.isReady(target)) loadEngine(target) else stage = Stage.NeedsModel
    }

    fun deleteModel(s: ModelSpec = target) {
        if (s.id == loadedId) closeEngine()
        store.delete(s)
        storageVersion++
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
     * CPU by default. The GPU delegate is faster when it works, but on some drivers
     * it takes the whole process down with it — a native crash no `catch` can see,
     * which is why it is opt-in and why a breadcrumb is written around the attempt.
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

        if (!useGpu) return Pair(build(LlmInference.Backend.CPU), Backend.CPU)

        store.beginRiskyLoad()
        return try {
            val engine = Pair(build(LlmInference.Backend.GPU), Backend.GPU)
            store.endRiskyLoad()
            engine
        } catch (_: Throwable) {
            store.endRiskyLoad()
            Pair(build(LlmInference.Backend.CPU), Backend.CPU)
        }
    }

    private fun closeEngine() {
        generation++
        runCatching { session?.close() }
        runCatching { engine?.close() }
        session = null
        sessionOwner = null
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
        // The live session still remembers the answer we are discarding.
        runCatching { session?.close() }
        session = null
        sessionOwner = null
        replace(convo.id) { it.copy(messages = trimmed) }
        persist()
        generate(convo.id)
    }

    private fun generate(conversationId: String) {
        val llm = engine
        val convo = conversations.firstOrNull { it.id == conversationId }
        if (llm == null || convo == null || stage !is Stage.Ready) {
            finish(
                conversationId,
                "No model is loaded yet. Open Settings to download one.",
                isError = true
            )
            return
        }

        busy = true
        streaming = ""
        turnStartedAt = System.currentTimeMillis()
        val turn = ++generation

        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val s = sessionFor(llm, convo)
                    // Raw text only. The bundle carries its own prompt template and
                    // the engine applies it; adding our own wraps the model's markup
                    // in a second layer and it answers the wrong question.
                    s.addQueryChunk(convo.messages.last().text)
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
     * A session holds its own conversation state, so one is kept per chat and reused
     * across turns. When a chat is opened fresh — a new session, but an existing
     * history — the earlier turns are replayed once as ordinary prose, which the
     * template then wraps exactly once.
     */
    private fun sessionFor(llm: LlmInference, convo: Conversation): LlmInferenceSession {
        val existing = session
        if (existing != null && sessionOwner == convo.id && !overflowed(convo)) return existing

        runCatching { existing?.close() }
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.7f)
            .setTopK(40)
            .build()
        val fresh = LlmInferenceSession.createFromOptions(llm, options)
        session = fresh
        sessionOwner = convo.id

        val history = convo.messages.dropLast(1).filterNot { it.isError }
        val recent = trimToBudget(history, modelFor(convo))
        dropped = history.size - recent.size
        if (recent.isNotEmpty()) fresh.addQueryChunk(recap(recent))
        return fresh
    }

    /** True once a chat has grown past what its model's window can hold. */
    private fun overflowed(convo: Conversation): Boolean {
        val budget = (modelFor(convo).contextTokens * 0.55).toInt()
        val used = convo.messages.sumOf { estimateTokens(it.text) + 8 }
        return used > budget
    }

    private fun trimToBudget(history: List<Message>, model: ModelSpec): List<Message> {
        var budget = (model.contextTokens * 0.45).toInt() - estimateTokens(systemPrompt)
        val kept = ArrayDeque<Message>()
        for (message in history.asReversed()) {
            val cost = estimateTokens(message.text) + 8
            if (budget - cost < 0) break
            budget -= cost
            kept.addFirst(message)
        }
        return kept.toList()
    }

    /** Prior turns as plain prose — no role tags, nothing the tokenizer treats as markup. */
    private fun recap(history: List<Message>): String = buildString {
        append(systemPrompt).append("\n\n")
        append("Here is our conversation so far.\n")
        history.forEach { m ->
            append(if (m.fromUser) "Me: " else "You: ")
            append(m.text)
            append("\n")
        }
        append("\nContinue from here.\n")
    }

    /**
     * Keeps whatever the reader has already seen and walks away from the rest.
     *
     * The native call cannot be interrupted safely mid-flight, so rather than tear
     * the session down underneath it we stop listening: late tokens arrive with a
     * stale turn number and are discarded. The session itself is dropped, because it
     * now holds a reply the conversation does not.
     */
    fun stop() {
        if (!busy) return
        val conversationId = currentId ?: return
        val split = Split.of(streaming)
        val seconds = thoughtSeconds()
        generation++
        runCatching { session?.close() }
        session = null
        sessionOwner = null
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
                // A stopped or superseded turn: its tokens belong to a session we
                // have already walked away from.
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
