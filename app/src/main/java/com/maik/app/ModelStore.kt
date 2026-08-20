package com.maik.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A LiteRT `.task` bundle MediaPipe can run.
 *
 * Every entry here is ungated on Hugging Face — no token, no license click-through,
 * no account. Gemma 3 and Llama 3.2 are gated, which would put a sign-in wall in
 * front of first launch; see the README for how to point at one anyway.
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val params: String,
    val blurb: String,
    val url: String,
    val approxBytes: Long
) {
    val fileName: String get() = "$id.task"
    val approxMb: Long get() = approxBytes / 1024 / 1024
}

object Models {
    val QWEN_1_5B = ModelSpec(
        id = "qwen2.5-1.5b-instruct-q8",
        label = "Qwen2.5 1.5B",
        params = "1.5B · int8",
        blurb = "Holds a thread, follows instructions. Recommended.",
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/" +
            "resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxBytes = 1_597_913_616L
    )

    val QWEN_0_5B = ModelSpec(
        id = "qwen2.5-0.5b-instruct-q8",
        label = "Qwen2.5 0.5B",
        params = "0.5B · int8",
        blurb = "Fast and small. Noticeably dumber.",
        url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
            "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxBytes = 546_832_384L
    )

    val ALL = listOf(QWEN_1_5B, QWEN_0_5B)
    val DEFAULT = QWEN_1_5B

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

    fun select(next: ModelSpec) {
        spec = next
        prefs.edit().putString("model", next.id).apply()
    }

    fun fileFor(s: ModelSpec = spec) = File(dir, s.fileName)

    fun isReady(s: ModelSpec = spec): Boolean = fileFor(s).let { it.exists() && it.length() > 0 }

    /** Every model bundle currently on disk, so the UI can show what's already paid for. */
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
