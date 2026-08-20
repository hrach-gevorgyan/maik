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
 * A model bundle maik can run — either LiteRT-LM (`.litertlm`) or the older
 * MediaPipe task format (`.task`).
 *
 * Every entry is ungated on Hugging Face: no token, no account, no license
 * click-through. Gemma 3, Llama and Gemma2 are all gated, which would put a
 * sign-in wall in front of first launch; the README explains how to use one anyway.
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
    val GEMMA4_E2B = ModelSpec(
        id = "gemma-4-e2b-it-gpu",
        label = "Gemma 4 E2B",
        params = "~2B effective",
        blurb = "Google's newest small model, and the sharpest of these. Balanced pace.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
            "resolve/main/gemma-4-E2B-it-gpu.litertlm",
        approxBytes = 2_008_000_000L,
        contextTokens = 4096,
        template = Template.GEMMA
    )

    val LFM_2_6B = ModelSpec(
        id = "lfm2.5-2.6b-int4",
        label = "LFM2.5 2.6B",
        params = "2.6B · int4",
        blurb = "Liquid AI, built for phones. More depth than the 1.2B, still quick.",
        url = "https://huggingface.co/litert-community/LFM2.5-2.6B/" +
            "resolve/main/LFM2.5-2.6B_int4.litertlm",
        approxBytes = 1_667_000_000L,
        contextTokens = 4096
    )

    val LFM_1_2B = ModelSpec(
        id = "lfm2.5-1.2b-int4-gpu",
        label = "LFM2.5 1.2B",
        params = "1.2B · int4 · GPU",
        blurb = "The fastest here, and the smallest download. Shallower on hard questions.",
        url = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/" +
            "resolve/main/LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
        approxBytes = 736_000_000L,
        contextTokens = 4096
    )

    /**
     * Kept as insurance. The three above are all LiteRT-LM bundles; if that format
     * misbehaves on a device, this older `.task` model is a known quantity.
     */
    val QWEN2_5_1_5B = ModelSpec(
        id = "qwen2.5-1.5b-instruct-q8-4k",
        label = "Qwen2.5 1.5B",
        params = "1.5B · int8",
        blurb = "Older and heavier. Try this one if the others fail to load at all.",
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/" +
            "resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        approxBytes = 1_598_000_000L,
        contextTokens = 4096
    )

    val ALL = listOf(GEMMA4_E2B, LFM_2_6B, LFM_1_2B, QWEN2_5_1_5B)
    val DEFAULT = GEMMA4_E2B

    fun byId(id: String?): ModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

sealed interface Download {
    data class Progress(val bytes: Long, val total: Long) : Download
    data class Done(val file: File) : Download
    data class Failed(val reason: String) : Download
}

class ModelStore(private val context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("maik", Context.MODE_PRIVATE)

    var spec: ModelSpec = Models.byId(prefs.getString("model", null))
        private set

    /** Whether reasoning models are allowed to think before answering. */
    var thinking: Boolean = prefs.getBoolean("thinking", true)
        private set

    fun select(next: ModelSpec) {
        spec = next
        prefs.edit().putString("model", next.id).apply()
    }

    fun setThinking(enabled: Boolean) {
        thinking = enabled
        prefs.edit().putBoolean("thinking", enabled).apply()
    }

    fun fileFor(s: ModelSpec = spec) = File(dir, s.fileName)

    fun isReady(s: ModelSpec = spec): Boolean = fileFor(s).let { it.exists() && it.length() > 0 }

    /** Every bundle already on disk, so the UI can show what's been paid for. */
    fun installed(): Set<String> = Models.ALL.filter { isReady(it) }.map { it.id }.toSet()

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
                emit(Download.Failed("HTTP ${conn.responseCode} fetching the model"))
                return@flow
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: s.approxBytes
            partial.delete()

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

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                emit(Download.Failed("Could not finalize the downloaded file"))
                return@flow
            }
            emit(Download.Done(target))
        } catch (e: Exception) {
            partial.delete()
            emit(Download.Failed(e.message ?: e::class.java.simpleName))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun delete(s: ModelSpec = spec) {
        fileFor(s).delete()
        File(dir, "${s.fileName}.part").delete()
    }
}
