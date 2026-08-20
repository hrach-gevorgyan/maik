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

/** Prompt formats differ per family; the bundles ship tokenizers, not templates. */
enum class Template { CHATML, PHI, DEEPSEEK, ZEPHYR }

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
    val template: Template = Template.CHATML,
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
        template = Template.DEEPSEEK,
        reasoning = true
    )

    val PHI_4_MINI = ModelSpec(
        id = "phi-4-mini-q8",
        label = "Phi-4-mini",
        params = "3.8B · int8",
        blurb = "The most capable that will run here. A big download and a slower reply.",
        url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/" +
            "resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
        approxBytes = 3_910_050_199L,
        contextTokens = 4096,
        template = Template.PHI
    )

    val TINYLLAMA = ModelSpec(
        id = "tinyllama-1.1b-q8",
        label = "TinyLlama 1.1B",
        params = "1.1B · int8",
        blurb = "Older and plainer, but quick and undemanding.",
        url = "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/" +
            "resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
        approxBytes = 1_148_331_545L,
        contextTokens = 1280,
        template = Template.ZEPHYR
    )

    val ALL = listOf(DEEPSEEK_1_5B, TINYLLAMA, PHI_4_MINI)

    val DEFAULT = DEEPSEEK_1_5B

    /**
     * Not offered in the app: a 159 MB bundle used by the instrumented golden test,
     * which downloads it and runs a real generation on an emulator. Small enough to
     * make that check affordable on every push.
     */
    val GOLDEN_TEST_MODEL = ModelSpec(
        id = "smollm-135m-q8",
        label = "SmolLM 135M",
        params = "135M · int8",
        blurb = "Test fixture.",
        url = "https://huggingface.co/litert-community/SmolLM-135M-Instruct/" +
            "resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxBytes = 166_754_726L,
        contextTokens = 1280
    )

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

    // Dark is the design; following the system would hand most users the light
    // scheme on first launch, which is not what maik is drawn for.
    var themeMode: ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "DARK") }
            .getOrDefault(ThemeMode.DARK)
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
