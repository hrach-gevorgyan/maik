package com.maik.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Prompt formats differ per family; the bundles ship tokenizers, not templates. */
enum class Template { CHATML, GEMMA }

/**
 * A model bundle maik can run.
 *
 * Only *generic* bundles are listed. Vendors also publish `-gpu` variants that are
 * a little smaller, but those refuse to load on the CPU executor — and since GPU
 * initialisation can fail for driver reasons on any given device, a model that
 * cannot fall back is a model that sometimes simply doesn't work.
 *
 * Every entry is ungated on Hugging Face: no token, no account, no license
 * click-through. Gemma 3, Gemma 2 and Llama are all gated, which would put a
 * sign-in wall in front of first launch.
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val params: String,
    val blurb: String,
    val url: String,
    val approxBytes: Long,
    /** Context window the bundle was built with. */
    val contextTokens: Int,
    val template: Template = Template.CHATML,
    /** Known to emit `<think>` blocks. Parsing copes either way. */
    val reasoning: Boolean = false
) {
    /** The runtime picks its loader from the extension, so it has to be preserved. */
    val fileName: String get() = "$id.${url.substringAfterLast('.')}"
    val approxMb: Long get() = approxBytes / 1024 / 1024
}

object Models {
    val LFM_1_2B = ModelSpec(
        id = "lfm2.5-1.2b-int4",
        label = "LFM2.5 1.2B",
        params = "1.2B · int4",
        blurb = "Quickest to answer and the smallest download. Start here.",
        url = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/" +
            "resolve/main/LFM2.5-1.2B-Instruct_int4.litertlm",
        approxBytes = 736_000_000L,
        contextTokens = 4096
    )

    val LFM_2_6B = ModelSpec(
        id = "lfm2.5-2.6b-int4",
        label = "LFM2.5 2.6B",
        params = "2.6B · int4",
        blurb = "Twice the model for twice the wait. Better on anything involved.",
        url = "https://huggingface.co/litert-community/LFM2.5-2.6B/" +
            "resolve/main/LFM2.5-2.6B_int4.litertlm",
        approxBytes = 1_667_000_000L,
        contextTokens = 4096
    )

    val GEMMA4_E2B = ModelSpec(
        id = "gemma-4-e2b-it",
        label = "Gemma 4 E2B",
        params = "~2B effective",
        blurb = "Google's newest small model. The most capable here, and the largest.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
            "resolve/main/gemma-4-E2B-it.litertlm",
        approxBytes = 2_588_000_000L,
        contextTokens = 4096,
        template = Template.GEMMA
    )

    val ALL = listOf(LFM_1_2B, LFM_2_6B, GEMMA4_E2B)

    /** Smallest and fastest by default: the first run should not cost 2.5 GB. */
    val DEFAULT = LFM_1_2B

    fun byId(id: String?): ModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

const val DEFAULT_SYSTEM_PROMPT =
    "You are maik, a helpful assistant running entirely on the user's phone. " +
        "Answer clearly and concisely."

sealed interface Download {
    data class Progress(val bytes: Long, val total: Long) : Download
    data class Done(val file: File) : Download
    data class Failed(val reason: String) : Download
}

class ModelStore(context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("maik", Context.MODE_PRIVATE)

    var spec: ModelSpec = Models.byId(prefs.getString("model", null))
        private set

    /** Whether reasoning models are allowed to think before answering. */
    var thinking: Boolean = prefs.getBoolean("thinking", true)
        private set

    var systemPrompt: String = prefs.getString("system", DEFAULT_SYSTEM_PROMPT)
        ?: DEFAULT_SYSTEM_PROMPT
        private set

    fun select(next: ModelSpec) {
        spec = next
        prefs.edit().putString("model", next.id).apply()
    }

    fun setThinking(enabled: Boolean) {
        thinking = enabled
        prefs.edit().putBoolean("thinking", enabled).apply()
    }

    fun setSystemPrompt(text: String) {
        systemPrompt = text.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
        prefs.edit().putString("system", systemPrompt).apply()
    }

    fun fileFor(s: ModelSpec = spec): File = File(dir, s.fileName)

    fun isReady(s: ModelSpec = spec): Boolean =
        fileFor(s).let { it.exists() && it.length() > MIN_PLAUSIBLE_BYTES }

    /** Every bundle already on disk, so the UI can show what's been paid for. */
    fun installed(): Set<String> = Models.ALL.filter { isReady(it) }.map { it.id }.toSet()

    fun bytesOnDisk(): Long = Models.ALL.sumOf { fileFor(it).length() }

    /**
     * Streams to a `.part` file and renames only on success, so an interrupted
     * download can never masquerade as a usable model.
     */
    fun download(s: ModelSpec = spec): Flow<Download> = flow {
        val target = fileFor(s)
        val partial = File(dir, "${s.fileName}.part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            conn.connect()

            if (conn.responseCode !in 200..299) {
                emit(Download.Failed("The server answered ${conn.responseCode}."))
                return@flow
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: s.approxBytes
            partial.delete()

            if (!hasRoomFor(total)) {
                emit(Download.Failed("Not enough free space — this needs ${total / 1024 / 1024} MB."))
                return@flow
            }

            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var copied = 0L
                    var lastEmit = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // Emitting per 64 KB chunk would just thrash recomposition.
                        if (copied - lastEmit > 4_000_000 || copied == total) {
                            lastEmit = copied
                            emit(Download.Progress(copied, total))
                        }
                    }
                }
            }

            // A truncated download is the likeliest failure on a phone, and it looks
            // exactly like a corrupt model later on. Catch it here instead.
            if (partial.length() < total * 0.99) {
                partial.delete()
                emit(Download.Failed("The download ended early. Check your connection and retry."))
                return@flow
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                emit(Download.Failed("Could not save the downloaded file."))
                return@flow
            }
            emit(Download.Done(target))
        } catch (e: Exception) {
            partial.delete()
            emit(Download.Failed(humanise(e)))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun delete(s: ModelSpec = spec) {
        fileFor(s).delete()
        File(dir, "${s.fileName}.part").delete()
    }

    fun deleteAllModels() = Models.ALL.forEach { delete(it) }

    private fun hasRoomFor(bytes: Long): Boolean =
        runCatching { dir.usableSpace > bytes + 128L * 1024 * 1024 }.getOrDefault(true)

    private fun humanise(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No connection."
        is java.net.SocketTimeoutException -> "The connection timed out."
        is java.io.IOException -> e.message ?: "The connection dropped."
        else -> e.message ?: e::class.java.simpleName
    }

    private companion object {
        /** Anything smaller than this is a stub or an error page, not a model. */
        const val MIN_PLAUSIBLE_BYTES = 50L * 1024 * 1024
    }
}
