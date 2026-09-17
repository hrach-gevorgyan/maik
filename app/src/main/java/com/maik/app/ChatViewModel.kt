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
import com.google.ai.edge.litertlm.Conversation as LmConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message as LmMessage
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the engine is doing, independent of which screen you're looking at. */
sealed interface Stage {
    data object NeedsModel : Stage
    data class Downloading(val bytes: Long, val total: Long, val verifying: Boolean = false) : Stage {
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
    val spec: ModelSpec get() = preferred()

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
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var systemPrompt by mutableStateOf(DEFAULT_SYSTEM_PROMPT)
        private set
    var hapticsEnabled by mutableStateOf(true)
        private set
    var useGpu by mutableStateOf(false)
        private set

    /** Eases off when the phone gets warm. */
    var keepCool by mutableStateOf(true)
        private set

    /** True while Android reports the phone is throttling, so the chat can say so. */
    var phoneIsWarm by mutableStateOf(false)
        private set


    /** Set when a reply was cut short to let the phone cool down. */
    var stoppedForHeat by mutableStateOf(false)
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

    /** The model this screen asked to load. */
    private var loadingId: String? = null
        set(value) {
            field = value
            isLoadingModel = value != null
        }

    /** Whether any model is loading, for screens that must not act on models meanwhile. */
    var isLoadingModel by mutableStateOf(false)
        private set

    /** Bumped per load request, so only the newest one sets the stage. */
    private var loadToken = 0

    /** Where Back from setup or settings returns to. */
    private var returnTo = Screen.List

    /** The whole reply so far, including tokens not yet published to [streaming]. */
    private var liveReply = StringBuffer()

    /** The chat the running reply belongs to, which is not always the one on screen. */
    var generatingId by mutableStateOf<String?>(null)
        private set

    /** The longest reply the next conversation is allowed to write. */
    private val replyCap: Int get() = if (keepCool) SHORT_REPLY_TOKENS else LONG_REPLY_TOKENS

    /** Set by Stop: a cancelled runtime conversation doesn't answer again, so rebuild it. */
    private var sessionStale = false

    /** Bumped per turn so a stopped generation's late output is ignored. */
    private var generation = 0

    val current: Conversation?
        get() = conversations.firstOrNull { it.id == currentId }

    /** The model a chat is held with: whatever it pinned, else the current choice. */
    fun modelFor(convo: Conversation?): ModelSpec =
        convo?.modelId?.let { Models.byId(it) } ?: preferred()

    /** The chosen model, or one that's actually downloaded when the choice isn't. */
    private fun preferred(): ModelSpec {
        if (store.isReady(store.spec)) return store.spec
        val installed = store.installed()
        return Models.ALL.firstOrNull { it.id in installed } ?: store.spec
    }

    val visibleConversations: List<Conversation>
        get() {
            val q = query.trim()
            return filterConversations(conversations, q)
        }


    /** False until chat history has been read from disk; the splash screen waits for it. */
    var chatsLoaded by mutableStateOf(false)
        private set

    init {
        systemPrompt = store.systemPrompt
        themeMode = store.themeMode
        hapticsEnabled = store.haptics
        debugMode = store.debug
        keepCool = store.keepCool
        Thermal.watch(app.applicationContext)
        watchHeat()

        // The process died during the last load. The GPU is the prime suspect: it can
        // crash natively, and no error handling sees that.
        val crashedOnGpu = store.lastLoadCrashed() && store.lastCrashWasGpu()
        val crashedOnCpu = store.lastLoadCrashed() && !store.lastCrashWasGpu()
        if (store.lastLoadCrashed()) store.clearCrashMarker()
        if (crashedOnGpu) {
            store.setUseGpu(false)
            android.widget.Toast.makeText(app, text(R.string.chat_gpu_crashed), android.widget.Toast.LENGTH_LONG).show()
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

            // The phone closed maik while loading this model last time. Loading it again on
            // its own would likely do the same, so ask first.
            crashedOnCpu && store.isReady(preferred()) -> {
                target = preferred()
                stage = Stage.Broken(text(R.string.model_crashed_last_time, preferred().label), "", Fix.RETRY_LOAD)
            }

            store.isReady(preferred()) -> loadEngine(preferred())

            else -> {
                target = store.spec
                stage = Stage.NeedsModel
            }
        }
        watchDownloads()

        // History is parsed off the main thread; a long one would otherwise stall launch.
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { chats.load() }
            // Anything started in the moment before loading finished stays on top.
            val startedMeanwhile = conversations.map { it.id }.toSet()
            conversations.addAll(saved.filterNot { it.id in startedMeanwhile })
            // First launch, with nothing downloaded yet: start where the app can be set up.
            if (screen == Screen.List && stage is Stage.NeedsModel &&
                store.installed().isEmpty() && conversations.isEmpty()
            ) {
                screen = Screen.Setup
            }
            chatsLoaded = true
            if (startedMeanwhile.isNotEmpty()) persist()
        }
    }

    /* ---------- navigation ---------- */

    fun openList() {
        screen = Screen.List
        currentId = null
    }

    /** Brings the download into view, e.g. when returning from its notification. */
    fun showDownload() {
        setupOpenedFromSettings = false
        rememberReturn()
        screen = Screen.Setup
    }

    private fun rememberReturn() {
        if (screen == Screen.Chat || screen == Screen.List) {
            returnTo = if (currentId != null && screen == Screen.Chat) Screen.Chat else Screen.List
        }
    }

    /** Download screens opened from a settings page go back to that page. */
    private var setupOpenedFromSettings = false

    /** Opens the download screen for a model, without changing what new chats use. */
    fun openDownload(model: ModelSpec) {
        setupOpenedFromSettings = screen == Screen.Settings
        if (!(stage is Stage.Downloading && target.id == model.id)) {
            target = model
            if (!store.isReady(model)) stage = Stage.NeedsModel
        }
        rememberReturn()
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
        modelsOpenedFromSetup = false
        rememberReturn()
        settingsPage = SettingsPage.Root
        screen = Screen.Settings
    }

    /** Straight to the model list, for "choose a different model" links. */
    fun openModels() {
        modelsOpenedFromSetup = screen == Screen.Setup
        rememberReturn()
        settingsPage = SettingsPage.Models
        screen = Screen.Settings
    }

    /** Models reached from the download screen goes back there, not into Settings. */
    private var modelsOpenedFromSetup = false

    /** Back out of the Models page, to wherever it was opened from. */
    fun leaveModels() {
        if (modelsOpenedFromSetup) {
            modelsOpenedFromSetup = false
            screen = Screen.Setup
        } else {
            settingsPage = SettingsPage.Root
        }
    }

    fun openSettingsPage(page: SettingsPage) {
        settingsPage = page
    }

    /** One step back. Settings pages return to the settings menu first. */
    fun back() {
        when {
            pendingSwitch != null -> pendingSwitch = null
            screen == Screen.Settings && settingsPage == SettingsPage.Models -> leaveModels()

            screen == Screen.Settings && settingsPage != SettingsPage.Root ->
                settingsPage = SettingsPage.Root

            screen == Screen.Setup && setupOpenedFromSettings -> {
                setupOpenedFromSettings = false
                screen = Screen.Settings
            }

            else -> when (backTarget(screen, returnTo, currentId)) {
                Screen.Chat -> screen = Screen.Chat
                else -> openList()
            }
        }
    }

    fun open(id: String) {
        stoppedForHeat = false
        if (busy && id != generatingId) stop()
        val convo = conversations.firstOrNull { it.id == id } ?: return
        currentId = id
        dropped = 0
        screen = Screen.Chat

        val wanted = modelFor(convo)
        val loaded = LocalEngine.loadedId?.let { loadedId -> Models.ALL.firstOrNull { it.id == loadedId } }

        // Mid-load, don't claim the old engine is ready; just follow the chat's model.
        if (loadingId != null) {
            ensureEngineFor(wanted)
            return
        }
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
    /** Closing the question without answering it leaves the chat exactly as it was. */
    fun dismissSwitch() {
        pendingSwitch = null
    }

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
        stoppedForHeat = false
        if (busy) stop()
        val model = preferred()
        val fresh = Conversation(id = UUID.randomUUID().toString(), title = text(R.string.chat_new_chat_title), modelId = model.id)
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
        if (sessionOwner == id) viewModelScope.launch { endSession() }
        conversations.removeAll { it.id == id }
        persist()
    }

    /** Pins or unpins a chat, so it stays at the top of the list. */
    fun togglePin(id: String) {
        replace(id) { it.copy(pinned = !it.pinned) }
        persist()
    }

    /** Removes one message from the open chat; the next turn rebuilds the context. */
    fun deleteMessage(index: Int, at: Long) {
        val convo = current ?: return
        if (busy || convo.messages.getOrNull(index)?.at != at) return
        // Your message and the reply it got go together; a reply with no question above it
        // would confuse both you and the model.
        val removeUpTo = if (convo.messages[index].fromUser) {
            val next = convo.messages.withIndex().drop(index + 1).firstOrNull { it.value.fromUser }?.index
            next ?: convo.messages.size
        } else {
            index + 1
        }
        replace(convo.id) { it.copy(messages = it.messages.filterIndexed { i, _ -> i !in index until removeUpTo }) }
        persist()
        viewModelScope.launch { endSession() }
    }

    /**
     * Replaces one of your messages and asks again from there. Everything after it
     * goes, because it was an answer to the old wording.
     */
    fun editAndResend(index: Int, at: Long, text: String) {
        val convo = current ?: return
        val clean = text.trim()
        if (busy || clean.isEmpty() || convo.messages.getOrNull(index)?.at != at) return
        if (!convo.messages[index].fromUser) return
        val kept = convo.messages.take(index) + convo.messages[index].copy(text = clean)
        replace(convo.id) {
            it.copy(
                title = if (index == 0) Conversation.titleFrom(clean) else it.title,
                messages = kept,
                updatedAt = System.currentTimeMillis()
            )
        }
        bumpToTop(convo.id)
        persist()
        generate(convo.id, freshSession = true)
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().ifEmpty { return }
        replace(id) { it.copy(title = clean) }
        persist()
    }

    fun deleteAll() {
        if (busy) stop()
        viewModelScope.launch { endSession() }
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

    fun updateKeepCool(enabled: Boolean) {
        store.setKeepCool(enabled)
        keepCool = enabled
        // Thread count is fixed when the engine is built and the reply cap when the
        // conversation is, so both are rebuilt.
        if (store.isReady(target) && (stage is Stage.Ready || stage is Stage.Broken || stage is Stage.Loading)) {
            viewModelScope.launch { endSession() }
            loadEngine(target, force = true)
        }
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
            loadEngine(target, force = true)
        }
    }

    fun updateSystemPrompt(text: String) {
        store.setSystemPrompt(text)
        systemPrompt = store.systemPrompt
        // The instruction is fixed when a conversation starts; start a new one next turn.
        viewModelScope.launch { endSession() }
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
            LocalEngine.loadedId == wanted.id && loadingId == null &&
                LocalEngine.builtWith(useGpu, keepCool) -> {
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
        val other = DownloadBus.modelId.value
        if (DownloadBus.running.value && other != null && other != target.id) {
            stage = Stage.Broken(
                text(R.string.model_still_downloading, Models.byId(other).label),
                "",
                Fix.RESUME_DOWNLOAD
            )
            return
        }
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

    /**
     * Watches the phone's own thermal reading. A phone that is throttling is already
     * uncomfortable to hold, and a long reply is the one thing maik can give back.
     */
    private fun watchHeat() {
        viewModelScope.launch {
            Thermal.status.collect { status ->
                phoneIsWarm = Thermal.isWarm(status)
                if (keepCool && Thermal.isHot(status) && busy) {
                    stoppedForHeat = true
                    stop()
                }
            }
        }
    }

    fun dismissHeatNotice() {
        stoppedForHeat = false
    }

    private fun watchDownloads() {
        viewModelScope.launch {
            DownloadBus.progress.collect { progress ->
                if (progress != null && DownloadBus.running.value && DownloadBus.modelId.value == target.id) {
                    stage = Stage.Downloading(progress.bytes, progress.total, progress.verifying)
                }
            }
        }
        viewModelScope.launch {
            DownloadBus.events.collect { event ->
                if (event is Download.Done) storageVersion++
                // Only the model this screen is waiting for; an event for another
                // model must never knock over a chat that's using this one.
                val (next, effect) = reduceDownload(stage, target.id, event, screen == Screen.Setup)
                stage = next
                when (effect) {
                    Effect.None -> Unit
                    // Only confirm on the download screen; in a chat the status strip
                    // simply disappears once the model is ready.
                    Effect.ConfirmAndLoad -> {
                        justInstalled = true
                        loadEngine(target)
                    }

                    Effect.LoadTarget -> ensureEngineFor(target)
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
        // Never while any load runs: closing engines underneath it would break it.
        if (loadingId != null) return
        viewModelScope.launch {
            if (LocalEngine.loadedId == s.id) {
                endSession()
                LocalEngine.unload(s.id)
                backend = Backend.NONE
            }
            val freed = withContext(Dispatchers.IO) {
                val before = store.bytesFor(s)
                store.delete(s)
                before
            }
            storageVersion++
            // Deleting gigabytes deserves a word back, not silence.
            android.widget.Toast.makeText(
                getApplication(),
                text(R.string.settings_model_deleted, s.label, sizeText(freed)),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            if (s.id == target.id) stage = Stage.NeedsModel
        }
    }

    private fun loadEngine(model: ModelSpec, force: Boolean = false) {
        if (!force && loadingId == model.id) return
        val token = ++loadToken
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
                LocalEngine.load(store, model, useGpu, keepCool)
            }
            // A newer request replaced this one; that load will set the stage.
            if (token != loadToken) return@launch
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
                text(R.string.model_file_damaged, model.label),
                detail,
                Fix.REDOWNLOAD
            )

            e is OutOfMemoryError || detail.contains("memory", ignoreCase = true) -> Stage.Broken(
                text(R.string.model_not_enough_memory, model.label),
                detail,
                Fix.RETRY_LOAD
            )

            else -> Stage.Broken(text(R.string.model_could_not_be_loaded, model.label), detail, Fix.RETRY_LOAD)
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

    /** Answers the last message when it was never answered, e.g. after Android closed maik. */
    fun answerLast() {
        val convo = current ?: return
        if (busy || convo.messages.lastOrNull()?.fromUser != true) return
        generate(convo.id, freshSession = true)
    }

    /** Asks the model to carry on from a reply that hit the length limit. */
    fun continueReply() {
        send(text(R.string.chat_continue_prompt))
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
            finish(conversationId, text(R.string.model_not_loaded_yet, model.label), isError = true)
            return
        }
        // Status only changes on a transition, so a phone that is already throttling
        // would never trigger the stop. Check before adding more heat.
        if (keepCool && Thermal.isHot()) {
            finish(conversationId, text(R.string.chat_too_hot_to_start), isError = true)
            return
        }

        busy = true
        streaming = ""
        stoppedForHeat = false
        generatingId = conversationId
        val turn = ++generation
        val prompt = convo.messages.last().text
        val previous = job

        // A fresh buffer per turn: a stopped reply still winding down can only ever
        // append to its own.
        val reply = StringBuffer()
        liveReply = reply
        job = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            var chunks = 0
            var lastPublish = 0L
            try {
                // A stopped reply may still be winding down in the runtime.
                previous?.join()
                if (freshSession || sessionStale) {
                    sessionStale = false
                    dropSession()
                }
                val conversation = sessionFor(convo, model)
                // Stopped before the conversation existed: don't start a hidden reply.
                if (turn != generation) return@launch

                // Not cancellable: the native call must wind down before anything can
                // close the conversation. stop() ends it early through cancelProcess().
                withContext(Dispatchers.Default + NonCancellable) {
                    conversation.sendMessageAsync(prompt).collect { chunk ->
                        val piece = textOf(chunk)
                        if (piece.isEmpty()) return@collect
                        val now = SystemClock.elapsedRealtime()
                        if (firstTokenAt == 0L) firstTokenAt = now
                        chunks++
                        if (turn != generation) return@collect
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
                        stats = statsFor(conversation, started, firstTokenAt, chunks),
                        truncated = chunks >= replyCap - TRUNCATION_SLACK
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (turn == generation) {
                    dropSession()
                    android.util.Log.w("maik", "Reply failed", e)
                    finish(conversationId, text(R.string.chat_reply_failed), isError = true)
                }
            }
        }
    }

    /**
     * Stops the reply where it is and keeps what was already written. The runtime won't
     * answer again on a cancelled conversation, so the next turn rebuilds it from the chat.
     */
    fun stop() {
        if (!busy) return
        val conversationId = generatingId ?: currentId ?: return
        val partial = liveReply.toString().trim()
        generatingId = null
        generation++
        runCatching { session?.cancelProcess() }
        sessionStale = true
        job?.cancel()
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
        val recent = ContextBudget.recentTurns(history, model.contextTokens, systemPrompt)
        dropped = history.size - recent.size

        val fresh = withContext(NonCancellable) {
            LocalEngine.conversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = recent.map {
                    if (it.fromUser) LmMessage.user(it.text) else LmMessage.model(it.text)
                },
                // Writing a word costs far more energy than reading one, so the
                // cheapest answer is a shorter one. Long enough for a few paragraphs,
                // short enough that a looping model gives up rather than cooking.
                maxOutputToken = replyCap,
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.7,
                    seed = (System.nanoTime() and 0x7fffffff).toInt()
                ),
                thinkingConfig = ThinkingConfig(false)
            )
        )
        }
        session = fresh
        sessionOwner = convo.id
        return fresh
    }

    /** Stops any reply, waits for the runtime to let go, then closes the conversation. */
    private suspend fun endSession() {
        if (busy) stop()
        job?.join()
        dropSession()
    }

    /** Closes the open conversation. Its fields are cleared before any suspension. */
    private suspend fun dropSession() {
        val old = session ?: return
        session = null
        sessionOwner = null
        LocalEngine.closeConversation(old)
    }

    /** True once the conversation is close to filling its model's window. */
    private fun overflowed(existing: LmConversation, model: ModelSpec): Boolean =
        ContextBudget.isFull(runCatching { existing.getTokenCount() }.getOrDefault(0), model.contextTokens)

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
        return text(
            R.string.chat_speed_line, ttft, rate,
            text(if (backend == Backend.GPU) R.string.settings_gpu else R.string.settings_cpu)
        )
    }

    private fun finish(
        conversationId: String,
        text: String,
        isError: Boolean,
        stats: String? = null,
        truncated: Boolean = false
    ) {
        streaming = ""
        busy = false
        generatingId = null
        replace(conversationId) {
            it.copy(
                messages = it.messages + Message(text, fromUser = false, isError = isError, stats = stats, truncated = truncated),
                updatedAt = System.currentTimeMillis()
            )
        }
        persist()
    }

    /* ---------- plumbing ---------- */

    /** A translated string. The view model has no composable scope, so it asks Android. */
    private fun text(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** "2.6 GB", in the phone's own units and number format. */
    fun sizeText(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(getApplication(), bytes)

    /** Bytes already fetched of an unfinished download of [model]. */
    fun partialBytes(model: ModelSpec): Long = store.partialBytes(model)

    /** Discards an unfinished download, freeing its space. */
    fun removePartialDownload(model: ModelSpec) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.removePartial(model) }
            storageVersion++
        }
    }

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
        // Saving before history has loaded would replace it with whatever is in memory.
        if (!chatsLoaded) return
        val snapshot = conversations.toList()
        LocalEngine.scope.launch(LocalEngine.saves) { chats.save(snapshot) }
    }

    override fun onCleared() {
        super.onCleared()
        // Whatever was written so far is kept, exactly as if Stop had been tapped.
        if (busy) {
            val partial = liveReply.toString().trim()
            val owner = generatingId
            if (partial.isNotEmpty() && owner != null) {
                replace(owner) {
                    it.copy(messages = it.messages + Message(partial, fromUser = false), updatedAt = System.currentTimeMillis())
                }
                persist()
            }
        }
        // The engine stays loaded for the next screen. Only the conversation goes.
        val old = session ?: return
        session = null
        sessionOwner = null
        val running = job
        if (busy) runCatching { old.cancelProcess() }
        LocalEngine.scope.launch {
            // Closing while the runtime still decodes would free memory it's using.
            running?.join()
            LocalEngine.closeConversation(old)
        }
    }

    companion object {
        private const val PUBLISH_EVERY_MS = 60L

        /** Roughly two or three paragraphs: what an answer on a phone should be. */
        private const val SHORT_REPLY_TOKENS = 384

        private const val LONG_REPLY_TOKENS = 768

        /** Pieces arrive a token or two at a time; this close to the cap, it was the cap. */
        private const val TRUNCATION_SLACK = 4
    }
}
