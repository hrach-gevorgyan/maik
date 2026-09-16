package com.maik.app.engine

import com.google.ai.edge.litertlm.Backend as LmBackend
import com.google.ai.edge.litertlm.Conversation as LmConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.maik.app.*
import com.maik.app.data.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*
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
    private var loadedGpu: Boolean? = null

    @Volatile
    var loadedId: String? = null
        private set

    @Volatile
    var backend: Backend = Backend.NONE
        private set

    /**
     * Loads [spec], replacing whatever was loaded. Returns at once if it already is.
     *
     * The work runs in [scope], so a caller that goes away mid-load does not abandon a
     * half-built engine: the load finishes and the model stays warm for next time.
     */
    suspend fun load(store: ModelStore, spec: ModelSpec, preferGpu: Boolean): Backend =
        scope.async(lifecycle) {
            if (engine != null && loadedId == spec.id && loadedGpu == preferGpu) {
                return@async backend
            }
            closeNow()
            val (opened, used) = open(store, spec, preferGpu)
            engine = opened
            loadedId = spec.id
            loadedGpu = preferGpu
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

    /** Closes the engine only if [id] is what's loaded, so a newer model is left alone. */
    suspend fun unload(id: String) = withContext(lifecycle) { if (loadedId == id) closeNow() }

    private fun closeNow() {
        runCatching { engine?.close() }
        engine = null
        loadedId = null
        loadedGpu = null
        backend = Backend.NONE
    }

    /**
     * CPU unless the GPU is preferred. The GPU can crash natively on some drivers —
     * a crash no `catch` can see — so a breadcrumb is written around the attempt, and
     * finding it at the next launch turns the GPU off.
     */
    private fun open(store: ModelStore, spec: ModelSpec, preferGpu: Boolean): Pair<Engine, Backend> {
        fun build(backend: LmBackend): Engine {
            val built = Engine(
                EngineConfig(
                    modelPath = store.fileFor(spec).absolutePath,
                    backend = backend,
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

        if (!preferGpu) return Pair(build(LmBackend.CPU()), Backend.CPU)

        store.beginRiskyLoad()
        return try {
            val gpu = build(LmBackend.GPU())
            store.endRiskyLoad()
            Pair(gpu, Backend.GPU)
        } catch (e: Exception) {
            store.endRiskyLoad()
            try {
                Pair(build(LmBackend.CPU()), Backend.CPU)
            } catch (cpu: Throwable) {
                cpu.addSuppressed(e)
                throw cpu
            }
        }
    }
}
