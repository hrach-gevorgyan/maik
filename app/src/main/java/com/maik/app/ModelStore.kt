package com.maik.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.RandomAccessFile

/** How the app should be painted. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * A model bundle maik can run.
 *
 * **Only `.task` bundles work.** They are ZIP archives holding `METADATA`,
 * `TF_LITE_PREFILL_DECODE` and `TOKENIZER_MODEL` — that last entry is the
 * SentencePiece tokenizer the runtime demands. LiteRT-LM `.litertlm` files carry no
 * such member and fail with "SentencePiece tokenizer not found", which is exactly
 * what shipped in 1.1.0 and 1.2.0.
 *
 * Every entry here is ungated on Hugging Face and has had its ZIP directory
 * inspected. Every Gemma and Llama repo is gated and cannot be used at all.
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val params: String,
    val blurb: String,
    val url: String,
    val approxBytes: Long,
    /** Context window the bundle was built with; must match its `ekv` figure. */
    val contextTokens: Int,
    /** Emits `<think>` blocks before answering. Parsing copes either way. */
    val reasoning: Boolean = false
) {
    val fileName: String get() = "$id.task"
    val approxMb: Long get() = approxBytes / 1024 / 1024
}

object Models {
    val DEEPSEEK_1_5B = ModelSpec(
        id = "deepseek-r1-distill-1.5b-q8",
        label = "DeepSeek-R1 1.5B",
        params = "1.5B · int8",
        blurb = "Works through a problem before answering, and shows you the working.",
        url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/" +
            "resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.task",
        approxBytes = 1_834_078_546L,
        contextTokens = 4096,
        reasoning = true
    )

    /**
     * A backup in the same size class, for when DeepSeek misbehaves. It does not
     * reason first, so its first word arrives sooner.
     */
    val QWEN_1_5B = ModelSpec(
        id = "qwen2.5-1.5b-instruct-q8",
        label = "Qwen2.5 1.5B",
        params = "1.5B · int8",
        blurb = "Answers straight away instead of thinking first. A steady fallback.",
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/" +
            "resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        approxBytes = 1_598_556_720L,
        contextTokens = 4096
    )

    /**
     * Two models, both around 1.5B, because that is the size a phone actually runs.
     *
     * TinyLlama returned empty replies on device. Phi-4-mini at 3.8B ran the phone
     * hot enough to throttle, took over a minute per answer and then locked up.
     * Nothing that size gets offered again — see [MAX_SENSIBLE_BYTES].
     */
    val ALL = listOf(DEEPSEEK_1_5B, QWEN_1_5B)

    val DEFAULT = DEEPSEEK_1_5B

    /**
     * A hard ceiling on what may be offered. Phi-4-mini at 3.7 GB was unusable on
     * real hardware; anything approaching that is a bad recommendation, not a
     * powerful one.
     */
    const val MAX_SENSIBLE_BYTES = 2_100_000_000L

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

    var thinking: Boolean = prefs.getBoolean("thinking", true)
        private set

    var haptics: Boolean = prefs.getBoolean("haptics", true)
        private set

    /** The GPU delegate can hard-crash on some drivers, so it is opt-in. */
    var useGpu: Boolean = prefs.getBoolean("gpu", false)
        private set

    // Dark is the design; following the system would hand most users the light
    // scheme on first launch, which is not what maik is drawn for.
    var themeMode: ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "LIGHT") }
            .getOrDefault(ThemeMode.LIGHT)
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

    fun setHaptics(enabled: Boolean) {
        haptics = enabled
        prefs.edit().putBoolean("haptics", enabled).apply()
    }

    fun setUseGpu(enabled: Boolean) {
        useGpu = enabled
        prefs.edit().putBoolean("gpu", enabled).apply()
    }

    /**
     * A native crash cannot be caught, so leave a note on disk before risking one
     * and clear it on success. Finding the note at startup means the last attempt
     * took the whole process down, and the GPU is not to be trusted here.
     */
    fun beginRiskyLoad() = prefs.edit().putBoolean("loading", true).commit()

    fun endRiskyLoad() = prefs.edit().putBoolean("loading", false).commit()

    fun lastLoadCrashed(): Boolean = prefs.getBoolean("loading", false)

    fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setSystemPrompt(text: String) {
        systemPrompt = text.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
        prefs.edit().putString("system", systemPrompt).apply()
    }

    fun fileFor(s: ModelSpec = spec): File = File(dir, s.fileName)

    fun isReady(s: ModelSpec = spec): Boolean =
        fileFor(s).let { it.exists() && it.length() > MIN_PLAUSIBLE_BYTES }

    fun installed(): Set<String> = Models.ALL.filter { isReady(it) }.map { it.id }.toSet()

    fun bytesOnDisk(): Long = Models.ALL.sumOf { fileFor(it).length() }

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
                emit(
                    Download.Failed(
                        "Not enough free space — this needs ${total / 1024 / 1024} MB."
                    )
                )
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
                        if (copied - lastEmit > 4_000_000 || copied == total) {
                            lastEmit = copied
                            emit(Download.Progress(copied, total))
                        }
                    }
                }
            }

            if (partial.length() < total * 0.99) {
                partial.delete()
                emit(Download.Failed("The download ended early. Check your connection and retry."))
                return@flow
            }

            // Prove the bundle is loadable *now*, while the user is still on the
            // download screen and a retry is obvious — rather than at first use,
            // where it surfaces as an unreadable engine error.
            validate(partial)?.let { problem ->
                partial.delete()
                emit(Download.Failed(problem))
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
        const val MIN_PLAUSIBLE_BYTES = 20L * 1024 * 1024

        /**
          * Returns a human-readable problem, or null when the bundle looks loadable.
          *
          * These archives begin with four bytes before the ZIP header, which
          * `java.util.zip.ZipFile` may refuse even though the runtime reads them
          * happily. So rather than parse the container, scan the tail — the central
          * directory lists every member by name and always sits at the end.
          */
        fun validate(file: File): String? = try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val window = minOf(length, 1L shl 20).toInt()
                raf.seek(length - window)
                val buffer = ByteArray(window)
                raf.readFully(buffer)
                val tail = String(buffer, Charsets.ISO_8859_1)
                when {
                    !tail.contains(REQUIRED_ENTRY) ->
                        "That file isn't a usable model — it has no tokenizer inside."

                    !tail.contains(WEIGHTS_ENTRY) ->
                        "That file isn't a usable model — the weights are missing."

                    else -> null
                }
            }
        } catch (_: Exception) {
            "The downloaded file could not be read."
        }

        const val REQUIRED_ENTRY = "TOKENIZER_MODEL"
        const val WEIGHTS_ENTRY = "TF_LITE_PREFILL_DECODE"
    }
}
