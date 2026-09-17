package com.maik.app.engine

import com.google.ai.edge.litertlm.Backend as LmBackend
import com.google.ai.edge.litertlm.Conversation as LmConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.maik.app.*
import com.maik.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * The one loaded model, owned by the process rather than by a screen.
 *
 * A 2.5 GB engine takes seconds to load, so it must survive the Activity and
 * ViewModel being recreated, and there must never be two of them. Every native
 * lifecycle call — create, initialise, open a conversation, close — runs on a single
 * thread, so loads cannot overlap and nothing is closed underneath another call.
 */
object LocalEngine {

    /** Serialises every native lifecycle call. */
    private val lifecycle = Dispatchers.IO.limitedParallelism(1)

    /** Work that must finish even if the screen that started it goes away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Writes chat history one save at a time, in order. */
    val saves = Dispatchers.IO.limitedParallelism(1)

    private var engine: Engine? = null
    @Volatile private var loadedGpu: Boolean? = null
    @Volatile private var loadedCool: Boolean? = null

    @Volatile
    var loadedId: String? = null
        private set

    @Volatile
    var backend: Backend = Backend.NONE
        private set

    /** True while a reply is being written, so nothing releases the engine underneath it. */
    @Volatile
    var generating: Boolean = false

    /**
     * Gives the model back to the system when memory is short.
     *
     * A loaded engine is 2.6 GB of resident memory that maik keeps on purpose, so the
     * next question is answered at once. That is a fine trade while maik is on screen
     * and an unreasonable one once the user has moved to the camera: the system's only
     * other way to reclaim it is to kill the process. Reloading costs seconds; being
     * killed costs the conversation's context as well.
     */
    /**
     * Told before the engine is closed, so whoever holds an open conversation can let
     * go of it first. Closing a conversation whose engine is already gone is a native
     * crash, not an exception.
     */
    @Volatile
    var onRelease: (() -> Unit)? = null

    suspend fun releaseForMemory(): Boolean = withContext(lifecycle) {
        if (engine == null || generating) return@withContext false
        onRelease?.invoke()
        closeNow()
        true
    }

    /**
     * Loads [spec], replacing whatever was loaded. Returns at once if it already is.
     *
     * The work runs in [scope], so a caller that goes away mid-load does not abandon a
     * half-built engine: the load finishes and the model stays warm for next time.
     */
    suspend fun load(store: ModelStore, spec: ModelSpec, preferGpu: Boolean, keepCool: Boolean = true): Backend =
        scope.async(lifecycle) {
            if (engine != null && loadedId == spec.id && loadedGpu == preferGpu && loadedCool == keepCool) {
                return@async backend
            }
            closeNow()
            val (opened, used) = open(store, spec, preferGpu, keepCool)
            engine = opened
            loadedId = spec.id
            loadedGpu = preferGpu
            loadedCool = keepCool
            backend = used
            used
        }.await()

    suspend fun conversation(config: ConversationConfig): LmConversation =
        withContext(lifecycle) {
            val current = engine ?: error("No model is loaded.")
            current.createConversation(config)
        }

    suspend fun closeConversation(conversation: LmConversation) =
        withContext(lifecycle) { runCatching { conversation.close() } }

    suspend fun close() = withContext(lifecycle) { closeNow() }

    /** Whether the loaded engine was built with these settings. */
    fun builtWith(preferGpu: Boolean, keepCool: Boolean): Boolean =
        loadedGpu == preferGpu && loadedCool == keepCool

    /** Closes the engine only if [id] is what's loaded, so a newer model is left alone. */
    suspend fun unload(id: String) = withContext(lifecycle) { if (loadedId == id) closeNow() }

    private fun closeNow() {
        runCatching { engine?.close() }
        engine = null
        loadedId = null
        loadedGpu = null
        loadedCool = null
        backend = Backend.NONE
    }

    /**
     * CPU unless the GPU is preferred. The GPU can crash natively on some drivers —
     * a crash no `catch` can see — so a breadcrumb is written around the attempt, and
     * finding it at the next launch turns the GPU off.
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun open(store: ModelStore, spec: ModelSpec, preferGpu: Boolean, keepCool: Boolean): Pair<Engine, Backend> {
        // Anything the model thinks to itself must not take up room in its memory of
        // the chat: that room is re-read for every word it writes.
        runCatching { com.google.ai.edge.litertlm.ExperimentalFlags.filterChannelContentFromKvCache = true }

        // Every busy core is heat. Half of them answers a little slower and keeps the
        // phone comfortable to hold, which matters more than tokens per second.
        val cpu = LmBackend.CPU(Thermal.threadsFor(keepCool))
        fun build(backend: LmBackend): Engine {
            val built = Engine(
                EngineConfig(
                    modelPath = store.fileFor(spec).absolutePath,
                    backend = backend,
                    // The runtime only loads the image encoder when a photo is sent, so
                    // enabling it costs nothing for text-only chats. CPU: it runs once
                    // per photo, and the processor handles that without the GPU's heat.
                    visionBackend = if (spec.vision) LmBackend.CPU() else null,
                    maxNumImages = if (spec.vision) 1 else null,
                    maxNumTokens = spec.contextTokens,
                    // Kept in files, not cache: the system clears cache under pressure,
                    // and losing it turns every later load back into a cold one.
                    cacheDir = store.cacheFor(spec).absolutePath
                )
            )
            try {
                built.initialize()
            } catch (t: Throwable) {
                runCatching { built.close() }
                throw t
            }
            return built
        }

        // Android kills a process that runs out of memory without an exception, so the
        // marker covers the CPU load too: otherwise a model too big for the phone would
        // crash the app again on every launch.
        if (!preferGpu) {
            store.beginRiskyLoad(gpu = false)
            try {
                return Pair(build(cpu), Backend.CPU)
            } finally {
                store.endRiskyLoad()
            }
        }

        store.beginRiskyLoad(gpu = true)
        return try {
            val gpu = build(LmBackend.GPU())
            store.endRiskyLoad()
            Pair(gpu, Backend.GPU)
        } catch (e: Exception) {
            store.beginRiskyLoad(gpu = false)
            try {
                Pair(build(cpu), Backend.CPU)
            } catch (cpu: Throwable) {
                cpu.addSuppressed(e)
                throw cpu
            } finally {
                store.endRiskyLoad()
            }
        }
    }
}
