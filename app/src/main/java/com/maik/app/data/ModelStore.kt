package com.maik.app.data

import android.content.Context
import android.os.Build
import com.maik.app.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** How the app should be painted. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * A model maik can run, as a LiteRT-LM `.litertlm` bundle.
 *
 * The runtime reads each bundle's own chat template and stop tokens, so maik never
 * formats a prompt itself. Every entry is ungated on Hugging Face and is the generic
 * build — the `-gpu`, `-web` and chip-specific variants only load in one place.
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val params: String,
    val blurb: String,
    val url: String,
    val approxBytes: Long,
    /**
     * Upper bound on prompt plus reply, in tokens. Kept modest on purpose: the
     * runtime decodes more slowly as this budget grows.
     */
    val contextTokens: Int,
    /** Below this much total RAM, the model is likely to be killed or crawl. Guidance only. */
    val minRamBytes: Long,
    /** SHA-256 of the file at [url], as Hugging Face lists it for the LFS object. */
    val sha256: String
) {
    val fileName: String get() = "$id.litertlm"
    val approxMb: Long get() = approxBytes / 1024 / 1024
}

object Models {
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b-it",
        label = "Gemma 4 E2B",
        params = "2B effective",
        blurb = "Google's model built for phones. The most capable here.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
            "resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        approxBytes = 2_588_147_712L,
        contextTokens = 2048,
        minRamBytes = 6L * 1024 * 1024 * 1024,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    )

    val LFM_2_5_1_2B = ModelSpec(
        id = "lfm2.5-1.2b-instruct-int4",
        label = "LFM2.5 1.2B",
        params = "1.2B · int4",
        blurb = "A third of the download, quick to answer, and easy on the battery.",
        url = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/" +
            "resolve/eb5e75a985a46b5d5707282539d985fdd34e2a10/LFM2.5-1.2B-Instruct_int4.litertlm",
        approxBytes = 736_015_744L,
        contextTokens = 2048,
        minRamBytes = 4L * 1024 * 1024 * 1024,
        sha256 = "a28b5c59ac204e2e51c1f98d2d6db6982f0e12da59a268fe498edcb33237e906"
    )

    val ALL = listOf(GEMMA_4_E2B, LFM_2_5_1_2B)

    val DEFAULT = GEMMA_4_E2B

    /**
     * A hard ceiling on what may be offered. Phi-4-mini at 3.7 GB ran the phone hot
     * enough to throttle, took over a minute per answer and then locked up. Gemma 4
     * E2B at 2.6 GB is the largest thing allowed through; anything near Phi is not.
     */
    const val MAX_SENSIBLE_BYTES = 2_700_000_000L

    fun byId(id: String?): ModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

const val DEFAULT_SYSTEM_PROMPT =
    "You are maik, a helpful assistant running entirely on the user's phone. " +
        "Answer clearly and concisely."

sealed interface Download {
    data class Progress(val bytes: Long, val total: Long) : Download
    data class Done(val file: File, val modelId: String) : Download
    data class Failed(val modelId: String, val reason: String, val cancelled: Boolean = false) : Download
}

class ModelStore(context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val cacheRoot = File(context.filesDir, "litertlm-cache")
    private val prefs = context.getSharedPreferences("maik", Context.MODE_PRIVATE)

    var spec: ModelSpec = Models.byId(prefs.getString("model", null))
        private set

    var haptics: Boolean = prefs.getBoolean("haptics", true)
        private set

    /** Shows speed figures under replies. */
    var debug: Boolean = prefs.getBoolean("debug", false)
        private set

    /**
     * On by default on chipsets where the GPU is known to run these models well,
     * because it reads the prompt many times faster. Off everywhere else.
     */
    var useGpu: Boolean =
        if (prefs.contains("gpu")) prefs.getBoolean("gpu", false) else gpuByDefault()
        private set

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

    fun setHaptics(enabled: Boolean) {
        haptics = enabled
        prefs.edit().putBoolean("haptics", enabled).apply()
    }

    fun setDebug(enabled: Boolean) {
        debug = enabled
        prefs.edit().putBoolean("debug", enabled).apply()
    }

    fun setUseGpu(enabled: Boolean) {
        useGpu = enabled
        prefs.edit().putBoolean("gpu", enabled).apply()
    }

    /**
     * A native crash cannot be caught, so leave a note on disk before risking one and
     * clear it on success. Finding the note at startup means the last attempt took the
     * whole process down.
     */
    fun beginRiskyLoad() = prefs.edit().putBoolean("loading", true).commit()

    fun endRiskyLoad() = prefs.edit().putBoolean("loading", false).commit()

    /** Clears the note without blocking; for startup, on the main thread. */
    fun clearCrashMarker() = prefs.edit().putBoolean("loading", false).apply()

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

    private fun partFor(s: ModelSpec): File = File(dir, "${s.fileName}.part")

    /** Where the runtime keeps its prepared copy of a model, so later loads are quick. */
    fun cacheFor(s: ModelSpec): File = File(cacheRoot, s.id).apply { mkdirs() }

    fun isReady(s: ModelSpec = spec): Boolean =
        fileFor(s).let { it.exists() && it.length() > MIN_PLAUSIBLE_BYTES }

    /** Bytes already fetched by an interrupted download. */
    fun partialBytes(s: ModelSpec): Long = partFor(s).let { if (it.exists()) it.length() else 0L }

    /** A readable problem with a downloaded model file, or null when it looks sound. */
    fun check(s: ModelSpec): String? = fileFor(s).let { if (it.exists()) validate(it) else null }

    fun installed(): Set<String> = Models.ALL.filter { isReady(it) }.map { it.id }.toSet()

    /** Everything a model takes: the file, any partial download, and the runtime's cache. */
    fun bytesOnDisk(): Long = Models.ALL.sumOf { s ->
        fileFor(s).length() + partFor(s).length() +
            File(cacheRoot, s.id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Downloads a model, resuming from an interrupted `.part` file when there is one.
     * Nothing counts as installed until the file is complete and has the right header.
     */
    fun download(s: ModelSpec = spec): Flow<Download> = flow {
        val target = fileFor(s)
        val partial = partFor(s)
        fun failed(reason: String) = Download.Failed(s.id, reason)
        try {
            // At most one restart: a partial the server can't continue is thrown away once.
            for (attempt in 0..1) {
                val have = partialBytes(s)
                val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                try {
                    conn.connect()
                    val code = conn.responseCode
                    if (code !in 200..299 && code != 416) {
                        emit(failed("The server answered $code."))
                        return@flow
                    }
                    val outcome = ResumePlan.decide(
                        have, code, conn.contentLengthLong, s.approxBytes,
                        ResumePlan.parseContentRangeStart(conn.getHeaderField("Content-Range"))
                    )
                    when (outcome) {
                        ResumePlan.Outcome.RestartFromZero -> {
                            partial.delete()
                            if (attempt == 0) continue
                            emit(failed("The server couldn't continue the download. Try again."))
                            return@flow
                        }

                        ResumePlan.Outcome.AlreadyComplete -> Unit

                        is ResumePlan.Outcome.Copy -> {
                            if (!outcome.resuming) partial.delete()
                            val remaining = outcome.total - outcome.start
                            if (!hasRoomFor(remaining, s)) {
                                emit(failed("Not enough free space — this needs ${remaining / 1024 / 1024} MB more."))
                                return@flow
                            }
                            emit(Download.Progress(outcome.start, outcome.total))
                            conn.inputStream.use { input ->
                                FileOutputStream(partial, outcome.resuming).use { output ->
                                    copyStream(input, output, outcome.start) { copied ->
                                        emit(Download.Progress(copied, outcome.total))
                                    }
                                }
                            }
                            if (partial.length() < outcome.total) {
                                emit(failed("The download was interrupted. It will continue from where it stopped."))
                                return@flow
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                break
            }

            // A pinned URL plus the checksum means a resumed file can't be two files spliced.
            if (!sha256Of(partial).equals(s.sha256, ignoreCase = true)) {
                partial.delete()
                emit(failed("The download was corrupted. Try again."))
                return@flow
            }
            validate(partial)?.let { problem ->
                partial.delete()
                emit(failed(problem))
                return@flow
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                emit(failed("Could not save the downloaded file."))
                return@flow
            }
            emit(Download.Done(target, s.id))
        } catch (e: CancellationException) {
            // Cancelled on purpose: keep the partial file so the next attempt resumes.
            throw e
        } catch (e: Exception) {
            emit(failed(humanise(e)))
        }
    }.flowOn(Dispatchers.IO)

    fun delete(s: ModelSpec = spec) {
        fileFor(s).delete()
        partFor(s).delete()
        File(cacheRoot, s.id).deleteRecursively()
    }

    /** Room for the rest of the file plus the runtime's prepared copy of it. */
    private fun hasRoomFor(bytes: Long, s: ModelSpec): Boolean =
        runCatching { dir.usableSpace > bytes + maxOf(512L shl 20, s.approxBytes / 4) }.getOrDefault(false)

    private fun humanise(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No connection. The download will continue when you retry."
        is java.net.SocketTimeoutException -> "The connection timed out. Retry to continue."
        is java.io.IOException -> "The connection dropped. Retry to continue from where it stopped."
        else -> e.message ?: e::class.java.simpleName
    }

    internal companion object {
        /** Anything smaller than this is a stub or an error page, not a model. */
        const val MIN_PLAUSIBLE_BYTES = 20L * 1024 * 1024

        /** Every LiteRT-LM bundle opens with these eight ASCII bytes. */
        const val MAGIC = "LITERTLM"

        /** Snapdragon 8 Gen 3 and newer flagship chips. */
        val GPU_CHIPS = listOf("SM8650", "SM8635", "SM8750", "SM8735", "SM8850")

        fun gpuByDefault(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                GPU_CHIPS.any { Build.SOC_MODEL.uppercase().startsWith(it) }

        fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Copies [input] to [output], reporting the running total (counted from [start])
         * every few megabytes and always once at the end.
         */
        suspend fun copyStream(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            start: Long,
            onProgress: suspend (Long) -> Unit
        ) {
            val buffer = ByteArray(1 shl 16)
            var copied = start
            var lastEmit = start
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                copied += read
                if (copied - lastEmit > 4_000_000) {
                    lastEmit = copied
                    onProgress(copied)
                }
            }
            onProgress(copied)
        }

        fun validate(file: File): String? = try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(MAGIC.length)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) == MAGIC) null
                else "That file isn't a usable model."
            }
        } catch (_: Exception) {
            "The downloaded file could not be read."
        }
    }
}
