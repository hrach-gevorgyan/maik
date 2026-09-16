package com.maik.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.ai.edge.litertlm.Conversation as LmConversation
import com.google.ai.edge.litertlm.Message as LmMessage

/** What the engine is doing, independent of which screen you're looking at. */
sealed interface Stage {
    data object NeedsModel : Stage
    data class Downloading(val bytes: Long, val total: Long) : Stage {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }

    data object Loading : Stage
    data object Ready : Stage

    /** [detail] is the raw engine message, kept for the expandable section. */
    data class Broken(val summary: String, val detail: String, val fix: Fix) : Stage
}

/** The one action that can actually cure a [Stage.Broken]. */
enum class Fix { RESUME_DOWNLOAD, REDOWNLOAD, RETRY_LOAD }

enum class Screen { List, Chat, Settings, Setup }

/** Settings is a menu of pages, not one long scroll. */
enum class SettingsPage { Root, Models, Appearance, Behaviour, Instructions, Storage, About }

/** Which compute unit the loaded engine ended up on. */
enum class Backend { GPU, CPU, NONE }

/** An opened chat that was started with a different model from the one loaded. */
data class ModelSwitch(val chatId: String, val chatModel: ModelSpec, val loaded: ModelSpec)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app.applicationContext)
    private val chats = ChatStore(app.applicationContext)

    val conversations = mutableStateListOf<Conversation>()

    /** The model new chats use. */
    val spec: ModelSpec get() = store.spec

    /** The model the engine is loading or has loaded — what the setup screen offers. */
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

    /** Shows the speed line under replies. Off unless asked for. */
    var debugMode by mutableStateOf(false)
        private set

    /** Bumped whenever a model file appears or disappears, so lists re-read the disk. */
    var storageVersion by mutableStateOf(0)
        private set

    /** Set when a download completes, so the setup screen can confirm it. */
    var justInstalled by mutableStateOf(false)
        private set

    /** The reply as it streams in, published a few times a second rather than per token. */
    var streaming by mutableStateOf("")
        private set

    /** How many old messages fell outside the context window on the last turn. */
    var dropped by mutableStateOf(0)
        private set

    /** When the current load began, so the status strip can show how long it's taking. */
    var loadStartedAt by mutableStateOf(0L)
        private set

    /** Asks which model to use when an opened chat was held with another one. */
    var pendingSwitch by mutableStateOf<ModelSwitch?>(null)
        private set

    /** The runtime conversation holding the open chat's context between turns. */
    private var session: LmConversation? = null
    private var sessionOwner: String? = null

    private var job: Job? = null

    /** The model this screen asked to load, so a superseded load is ignored. */
    private var loadingId: String? = null

    /** Bumped per turn so a stopped generation's late output is ignored. */
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
        systemPrompt = store.systemPrompt
        themeMode = store.themeMode
        hapticsEnabled = store.haptics
        debugMode = store.debug

        // The process died during the last load. The GPU is the prime suspect: it can
        // crash natively, and no error handling sees that.
        if (store.lastLoadCrashed()) {
            store.setUseGpu(false)
            store.endRiskyLoad()
        }
        useGpu = store.useGpu

        val warm = LocalEngine.loadedId?.let { id -> Models.ALL.firstOrNull { it.id == id } }
        val downloadingId = DownloadBus.modelId.value
        when {
            // The model is still loaded from before this screen was recreated.
            warm != null -> {
                target = warm
                backend = LocalEngine.backend
                stage = Stage.Ready
            }

            DownloadBus.running.value && downloadingId != null -> {
                target = Models.byId(downloadingId)
                val progress = DownloadBus.progress.value
                stage = Stage.Downloading(progress?.bytes ?: 0, progress?.total ?: target.approxBytes)
            }

            store.isReady() -> loadEngine(store.spec)

            else -> {
                target = store.spec
                stage = Stage.NeedsModel
            }
        }
        // First launch, with nothing downloaded yet: start where the app can be set up.
        if (stage is Stage.NeedsModel && store.installed().isEmpty() && conversations.isEmpty()) {
            screen = Screen.Setup
        }
        watchDownloads()
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

    /** Opens the download screen for a model, without changing what new chats use. */
    fun openDownload(model: ModelSpec) {
        if (!(stage is Stage.Downloading && target.id == model.id)) {
            target = model
            if (!store.isReady(model)) stage = Stage.NeedsModel
        }
        screen = Screen.Setup
    }

    /** Total memory on this phone, for warning before a model that won't fit. */
    fun totalRamBytes(): Long {
        val manager = getApplication<Application>()
            .getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 0
        return android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.totalMem
    }

    /** Dismisses the "ready" confirmation and gets on with it. */
    fun acknowledgeInstall() {
        justInstalled = false
        if (currentId == null) newChat() else screen = Screen.Chat
    }

    fun openSettings() {
        settingsPage = SettingsPage.Root
        screen = Screen.Settings
    }

    /** Straight to the model list, for "choose a different model" links. */
    fun openModels() {
        settingsPage = SettingsPage.Models
        screen = Screen.Settings
    }

    fun openSettingsPage(page: SettingsPage) {
        settingsPage = page
    }

    /** One step back. Settings pages return to the settings menu first. */
    fun back() {
        when {
            pendingSwitch != null -> pendingSwitch = null
            screen == Screen.Settings && settingsPage != SettingsPage.Root ->
                settingsPage = SettingsPage.Root

            screen == Screen.Setup && stage is Stage.Downloading -> openSettings()
            else -> openList()
        }
    }

    fun open(id: String) {
        if (busy) stop()
        val convo = conversations.firstOrNull { it.id == id } ?: return
        currentId = id
        dropped = 0
        screen = Screen.Chat

        val wanted = modelFor(convo)
        val loaded = LocalEngine.loadedId?.let { loadedId -> Models.ALL.firstOrNull { it.id == loadedId } }

        // A chat held with a different model than the loaded one: ask, don't guess.
        if (convo.messages.isNotEmpty() && loaded != null && loaded.id != wanted.id) {
            target = loaded
            backend = LocalEngine.backend
            stage = Stage.Ready
            pendingSwitch = ModelSwitch(id, wanted, loaded)
            return
        }
        ensureEngineFor(wanted)
    }

    /** Answers [pendingSwitch]: load the chat's own model, or carry on with the loaded one. */
    fun resolveSwitch(useChatModel: Boolean) {
        val choice = pendingSwitch ?: return
        pendingSwitch = null
        if (useChatModel) {
            ensureEngineFor(choice.chatModel)
        } else {
            replace(choice.chatId) { it.copy(modelId = choice.loaded.id) }
            persist()
        }
    }

    fun newChat() {
        if (busy) stop()
        val model = LocalEngine.loadedId?.let { id -> Models.ALL.firstOrNull { it.id == id } } ?: store.spec
        val fresh = Conversation(id = UUID.randomUUID().toString(), title = "New chat", modelId = model.id)
        conversations.add(0, fresh)
        currentId = fresh.id
        dropped = 0
        query = ""
        screen = Screen.Chat
        ensureEngineFor(model)
    }

    fun delete(id: String) {
        if (currentId == id) {
            if (busy) stop()
            currentId = null
            screen = Screen.List
        }
        if (sessionOwner == id) viewModelScope.launch { dropSession() }
        conversations.removeAll { it.id == id }
        persist()
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().ifEmpty { return }
        replace(id) { it.copy(title = clean) }
        persist()
    }

    fun deleteAll() {
        if (busy) stop()
        viewModelScope.launch { dropSession() }
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

    fun updateHaptics(enabled: Boolean) {
        store.setHaptics(enabled)
        hapticsEnabled = enabled
    }

    fun updateDebug(enabled: Boolean) {
        store.setDebug(enabled)
        debugMode = enabled
    }

    fun updateUseGpu(enabled: Boolean) {
        store.setUseGpu(enabled)
        useGpu = enabled
        // The engine is rebuilt on the new backend; the loader drops the old one first.
        if (store.isReady(target) && (stage is Stage.Ready || stage is Stage.Broken || stage is Stage.Loading)) {
            loadingId = null
            loadEngine(target)
        }
    }

    fun updateSystemPrompt(text: String) {
        store.setSystemPrompt(text)
        systemPrompt = store.systemPrompt
        // The instruction is fixed when a conversation starts; start a new one next turn.
        viewModelScope.launch { dropSession() }
    }

    /* ---------- model ---------- */

    /** Picks the model new chats use, and goes straight to the download if it's missing. */
    fun selectModel(next: ModelSpec) {
        store.select(next)
        target = next
        if (store.isReady(next)) {
            ensureEngineFor(next)
            if (screen == Screen.Setup) screen = Screen.List
        } else {
            stage = Stage.NeedsModel
            screen = Screen.Setup
        }
    }

    /** Pins the open chat to a model, loading it if needed. */
    fun setModelForCurrentChat(next: ModelSpec) {
        val convo = current ?: return
        replace(convo.id) { it.copy(modelId = next.id) }
        persist()
        ensureEngineFor(next)
    }

    private fun ensureEngineFor(wanted: ModelSpec) {
        target = wanted
        when {
            LocalEngine.loadedId == wanted.id && loadingId == null -> {
                backend = LocalEngine.backend
                stage = Stage.Ready
            }

            loadingId == wanted.id -> stage = Stage.Loading
            !store.isReady(wanted) -> stage = Stage.NeedsModel
            else -> loadEngine(wanted)
        }
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
        stage = Stage.Downloading(store.partialBytes(target), target.approxBytes)
        // A foreground service, so it keeps going when the screen locks.
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
            DownloadBus.progress.collect { progress ->
                if (progress != null && DownloadBus.running.value) {
                    stage = Stage.Downloading(progress.bytes, progress.total)
                }
            }
        }
        viewModelScope.launch {
            DownloadBus.events.collect { event ->
                when (event) {
                    is Download.Done -> {
                        storageVersion++
                        // Only the model this screen is waiting for; a stale event must
                        // never start a second load.
                        if (event.modelId == target.id) {
                            // Only confirm on the download screen; in a chat the status
                            // strip simply disappears once the model is ready.
                            justInstalled = screen == Screen.Setup
                            loadEngine(target)
                        }
                    }

                    is Download.Failed -> stage =
                        if (event.cancelled) Stage.NeedsModel
                        else Stage.Broken(event.reason, "", Fix.RESUME_DOWNLOAD)

                    is Download.Progress -> Unit
                }
            }
        }
    }

    fun retry() {
        when (val broken = stage) {
            is Stage.Broken -> when (broken.fix) {
                Fix.RETRY_LOAD -> loadEngine(target)
                Fix.RESUME_DOWNLOAD -> startDownload()
                Fix.REDOWNLOAD -> {
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) { store.delete(target) }
                        storageVersion++
                        startDownload()
                    }
                }
            }

            else -> if (store.isReady(target)) loadEngine(target) else stage = Stage.NeedsModel
        }
    }

    /** Deletes a model's file, unloading it first if it is the one in use. */
    fun deleteModel(s: ModelSpec = target) {
        if (loadingId == s.id) return
        viewModelScope.launch {
            if (LocalEngine.loadedId == s.id) {
                if (busy) stop()
                job?.join()
                dropSession()
                LocalEngine.close()
                backend = Backend.NONE
            }
            withContext(Dispatchers.IO) { store.delete(s) }
            storageVersion++
            if (s.id == target.id) stage = Stage.NeedsModel
        }
    }

    private fun loadEngine(model: ModelSpec) {
        if (loadingId == model.id) return
        loadingId = model.id
        target = model
        loadStartedAt = SystemClock.elapsedRealtime()
        stage = Stage.Loading
        val previous = job
        viewModelScope.launch {
            val result = runCatching {
                previous?.join()
                // The conversation belongs to the engine being replaced.
                dropSession()
                LocalEngine.load(store, model, useGpu)
            }
            // A newer request replaced this one; that load will set the stage.
            if (loadingId != model.id) return@launch
            loadingId = null
            result
                .onSuccess {
                    backend = it
                    stage = Stage.Ready
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    backend = Backend.NONE
                    stage = brokenFor(model, e)
                }
        }
    }

    /** Only a file that fails its check is a bad download; everything else is not. */
    private suspend fun brokenFor(model: ModelSpec, e: Throwable): Stage.Broken {
        val detail = e.message ?: e::class.java.simpleName
        val damaged = withContext(Dispatchers.IO) { store.check(model) } != null
        return when {
            damaged -> Stage.Broken(
                "The ${model.label} file is damaged. Downloading it again should fix this.",
                detail,
                Fix.REDOWNLOAD
            )

            e is OutOfMemoryError || detail.contains("memory", ignoreCase = true) -> Stage.Broken(
                "There isn't enough free memory to load ${model.label}. " +
                    "Close other apps, or pick a smaller model.",
                detail,
                Fix.RETRY_LOAD
            )

            else -> Stage.Broken("${model.label} could not be loaded.", detail, Fix.RETRY_LOAD)
        }
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
                modelId = it.modelId ?: target.id,
                messages = it.messages + Message(prompt, fromUser = true),
                updatedAt = System.currentTimeMillis()
            )
        }
        bumpToTop(convo.id)
        persist()
        generate(convo.id, freshSession = false)
    }

    /** Drops the last reply and asks again from the same point. */
    fun regenerate() {
        val convo = current ?: return
        if (busy) return
        val trimmed = convo.messages.dropLastWhile { !it.fromUser }
        if (trimmed.isEmpty()) return
        replace(convo.id) { it.copy(messages = trimmed) }
        persist()
        // The live conversation still holds the answer being thrown away.
        generate(convo.id, freshSession = true)
    }

    private fun generate(conversationId: String, freshSession: Boolean) {
        val convo = conversations.firstOrNull { it.id == conversationId } ?: return
        val model = modelFor(convo)
        if (stage !is Stage.Ready || LocalEngine.loadedId != model.id) {
            finish(conversationId, "${model.label} isn't loaded yet.", isError = true)
            return
        }

        busy = true
        streaming = ""
        val turn = ++generation
        val prompt = convo.messages.last().text
        val previous = job

        job = viewModelScope.launch {
            val reply = StringBuilder()
            val started = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            var chunks = 0
            var lastPublish = 0L
            try {
                // A stopped reply may still be winding down in the runtime.
                previous?.join()
                if (freshSession) dropSession()
                val conversation = sessionFor(convo, model)

                withContext(Dispatchers.Default) {
                    conversation.sendMessageAsync(prompt).collect { chunk ->
                        val piece = textOf(chunk)
                        if (piece.isEmpty()) return@collect
                        val now = SystemClock.elapsedRealtime()
                        if (firstTokenAt == 0L) firstTokenAt = now
                        chunks++
                        reply.append(piece)
                        // Publishing per token re-lays-out the whole reply dozens of
                        // times a second. A few times a second reads the same.
                        if (now - lastPublish >= PUBLISH_EVERY_MS) {
                            lastPublish = now
                            val snapshot = reply.toString()
                            withContext(Dispatchers.Main) {
                                if (turn == generation) streaming = snapshot
                            }
                        }
                    }
                }

                if (turn == generation) {
                    finish(
                        conversationId,
                        reply.toString().trim().ifEmpty { "…" },
                        isError = false,
                        stats = statsFor(conversation, started, firstTokenAt, chunks)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (turn == generation) {
                    dropSession()
                    finish(conversationId, e.message ?: e::class.java.simpleName, isError = true)
                }
            }
        }
    }

    /**
     * Stops the reply where it is and keeps what was already written. The conversation
     * is kept too: rebuilding it would make the model re-read the whole chat.
     */
    fun stop() {
        if (!busy) return
        val conversationId = currentId ?: return
        val partial = streaming.trim()
        generation++
        runCatching { session?.cancelProcess() }
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

    /**
     * One runtime conversation for the open chat, reused across turns so the model
     * keeps its context instead of re-reading the chat. It is rebuilt only for a
     * different chat, or once this one is close to filling the window.
     */
    private suspend fun sessionFor(convo: Conversation, model: ModelSpec): LmConversation {
        session?.let { existing ->
            if (sessionOwner == convo.id && !overflowed(existing, model)) return existing
        }
        dropSession()

        val history = convo.messages.dropLast(1).filterNot { it.isError }
        val recent = trimToBudget(history, model)
        dropped = history.size - recent.size

        val fresh = LocalEngine.conversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = recent.map {
                    if (it.fromUser) LmMessage.user(it.text) else LmMessage.model(it.text)
                },
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.7,
                    seed = (System.nanoTime() and 0x7fffffff).toInt()
                ),
                thinkingConfig = ThinkingConfig(false)
            )
        )
        session = fresh
        sessionOwner = convo.id
        return fresh
    }

    /** Closes the open conversation. Its fields are cleared before any suspension. */
    private suspend fun dropSession() {
        val old = session ?: return
        session = null
        sessionOwner = null
        LocalEngine.closeConversation(old)
    }

    /** True once the conversation is close to filling its model's window. */
    private fun overflowed(existing: LmConversation, model: ModelSpec): Boolean {
        val used = runCatching { existing.getTokenCount() }.getOrDefault(0)
        return used > (model.contextTokens * 0.8).toInt()
    }

    /**
     * The newest turns that fit in about a third of the window, leaving the rest for
     * the reply and for the conversation to grow before it has to be rebuilt.
     */
    private fun trimToBudget(history: List<Message>, model: ModelSpec): List<Message> {
        var budget = (model.contextTokens * 0.3).toInt() - estimateTokens(systemPrompt)
        val kept = ArrayDeque<Message>()
        for (message in history.asReversed()) {
            val cost = estimateTokens(message.text) + 8
            if (budget - cost < 0) break
            budget -= cost
            kept.addFirst(message)
        }
        // A conversation cannot open on the model's turn.
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeFirst()
        return kept.toList()
    }

    private fun textOf(message: LmMessage): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** "0.4s to first word · 38 tok/s · GPU", preferring the runtime's own measurements. */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun statsFor(conversation: LmConversation, started: Long, firstTokenAt: Long, chunks: Int): String {
        val ended = SystemClock.elapsedRealtime()
        val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
        val ttft = info?.timeToFirstTokenInSecond?.takeIf { it > 0 }
            ?: if (firstTokenAt > 0) (firstTokenAt - started) / 1000.0 else 0.0
        val rate = info?.lastDecodeTokensPerSecond?.takeIf { it > 0 }
            ?: if (firstTokenAt in 1 until ended) chunks * 1000.0 / (ended - firstTokenAt) else 0.0
        return String.format(Locale.US, "%.1fs to first word · %.0f tok/s · %s", ttft, rate, backend.name)
    }

    private fun finish(conversationId: String, text: String, isError: Boolean, stats: String? = null) {
        streaming = ""
        busy = false
        replace(conversationId) {
            it.copy(
                messages = it.messages + Message(text, fromUser = false, isError = isError, stats = stats),
                updatedAt = System.currentTimeMillis()
            )
        }
        persist()
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

    /** Saves off the main thread, in order, and outlives this screen. */
    private fun persist() {
        val snapshot = conversations.toList()
        LocalEngine.scope.launch(LocalEngine.saves) { chats.save(snapshot) }
    }

    override fun onCleared() {
        super.onCleared()
        // The engine stays loaded for the next screen. Only the conversation goes.
        val old = session ?: return
        if (busy) runCatching { old.cancelProcess() }
        session = null
        sessionOwner = null
        LocalEngine.scope.launch { LocalEngine.closeConversation(old) }
    }

    companion object {
        private const val PUBLISH_EVERY_MS = 60L

        /** Rough for English, deliberately pessimistic so the history under-fills. */
        fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 1
    }
}
