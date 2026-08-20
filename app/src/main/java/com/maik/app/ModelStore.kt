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
 * A LiteRT `.task` bundle that MediaPipe can run.
 *
 * Both defaults are ungated on Hugging Face — they download anonymously, with no
 * token and no license click-through. Gemma 3 1B is the better model but its repo
 * is gated, which would force a sign-in step on every user; see the README.
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val url: String,
    val approxBytes: Long
) {
    val fileName: String get() = "$id.task"
}

object Models {
    val QWEN_0_5B = ModelSpec(
        id = "qwen2.5-0.5b-instruct-q8",
        label = "Qwen2.5 0.5B Instruct · int8",
        url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
            "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxBytes = 546_832_384L
    )

    /** Slower and heavier, but noticeably sharper. Swap DEFAULT to use it. */
    val QWEN_1_5B = ModelSpec(
        id = "qwen2.5-1.5b-instruct-q8",
        label = "Qwen2.5 1.5B Instruct · int8",
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/" +
            "resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxBytes = 1_597_913_616L
    )

    val DEFAULT = QWEN_0_5B
}

sealed interface Download {
    data class Progress(val bytes: Long, val total: Long) : Download {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }

    data class Done(val file: File) : Download
    data class Failed(val reason: String) : Download
}

class ModelStore(private val context: Context, val spec: ModelSpec = Models.DEFAULT) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    val file = File(dir, spec.fileName)

    fun isReady(): Boolean = file.exists() && file.length() > 0

    /**
     * Streams the bundle to a `.part` file and only renames on success, so an
     * interrupted download can never masquerade as a usable model.
     */
    fun download(): Flow<Download> = flow {
        val partial = File(dir, "${spec.fileName}.part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            conn.connect()

            if (conn.responseCode !in 200..299) {
                emit(Download.Failed("HTTP ${conn.responseCode} fetching the model"))
                return@flow
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: spec.approxBytes
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
                        // Emitting on every 64 KB chunk would just thrash recomposition.
                        if (copied - lastEmit > 2_000_000 || copied == total) {
                            lastEmit = copied
                            emit(Download.Progress(copied, total))
                        }
                    }
                }
            }

            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) {
                emit(Download.Failed("Could not finalize the downloaded file"))
                return@flow
            }
            emit(Download.Done(file))
        } catch (e: Exception) {
            partial.delete()
            emit(Download.Failed(e.message ?: e::class.java.simpleName))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun delete() {
        file.delete()
        File(dir, "${spec.fileName}.part").delete()
    }
}
