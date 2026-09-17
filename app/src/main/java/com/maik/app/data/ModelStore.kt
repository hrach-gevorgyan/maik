package com.maik.app.data

import android.content.Context
import android.os.Build
import com.maik.app.*
import com.maik.app.R
import com.maik.app.engine.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** One line about the model, as a resource so it can be translated. */
    @androidx.annotation.StringRes val blurbRes: Int,
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
    val sha256: String,
    /** True for models big enough that the phone warms up noticeably while they answer. */
    val heavy: Boolean = false,
    /** The bundle carries an image encoder, so photos can be part of a question. */
    val vision: Boolean = false
) {
    val fileName: String get() = "$id.litertlm"
}

object Models {
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b-it",
        label = "Gemma 4 E2B",
        params = "2B effective",
        blurbRes = R.string.model_blurb_gemma,
        heavy = true,
        vision = true,
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
        blurbRes = R.string.model_blurb_lfm,
        url = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/" +
            "resolve/eb5e75a985a46b5d5707282539d985fdd34e2a10/LFM2.5-1.2B-Instruct_int4.litertlm",
        approxBytes = 736_015_744L,
        contextTokens = 2048,
        minRamBytes = 4L * 1024 * 1024 * 1024,
        sha256 = "a28b5c59ac204e2e51c1f98d2d6db6982f0e12da59a268fe498edcb33237e906"
    )

    val ALL = listOf(GEMMA_4_E2B, LFM_2_5_1_2B)

    /**
     * Gemma, because answer quality is the point. It is the warmer of the two — it
     * re-reads 2.6 GB for every word it writes — so the CPU default, the capped reply
     * length and the thermal pacing exist largely to make Gemma comfortable to use.
     */
    val DEFAULT = GEMMA_4_E2B

    /**
     * A hard ceiling on what may be offered. Phi-4-mini at 3.7 GB ran the phone hot
     * enough to throttle, took over a minute per answer and then locked up. Gemma 4
     * E2B at 2.6 GB is the largest thing allowed through; anything near Phi is not.
     */
    const val MAX_SENSIBLE_BYTES = 2_700_000_000L

    fun byId(id: String?): ModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * What the model is told before every conversation.
 *
 * The important part is the second half. Trained on assistants that can search and
 * book things, a small model will happily offer to "check availability" and ask for
 * dates it can do nothing with. Saying plainly that there is no connection and no
 * tools turns three wasted turns into one honest answer.
 */
const val DEFAULT_SYSTEM_PROMPT =
    "You are maik, an assistant running entirely on the user's phone, offline.\n\n" +
        "You have no internet, no search, no apps, no location and no live data. You " +
        "cannot look anything up, check prices or availability, book or order anything, " +
        "send messages or open links. Never offer to do those things and never ask for " +
        "details you could only use by doing them.\n\n" +
        "Be useful and be interesting. Commit to an answer in the first reply: give the " +
        "specifics you are confident about, and where the exact figure or name would " +
        "need looking up, give the shape of the answer instead — how it usually works, " +
        "what it roughly costs, what to watch out for. Have an opinion when one is " +
        "asked for. Enjoy the questions that are meant to be fun.\n\n" +
        "Flag uncertainty in passing, not as a paragraph: one short clause is enough, " +
        "and never open with a disclaimer. Do not invent an exact command, price, " +
        "timetable or quotation to look thorough — say that part needs checking and " +
        "answer the rest. Keep it concise."

sealed interface Download {
    /** [verifying] is true while the finished file is checked, which takes a while. */
    data class Progress(val bytes: Long, val total: Long, val verifying: Boolean = false) : Download
    data class Done(val file: File, val modelId: String) : Download
    data class Failed(val modelId: String, val reason: String, val cancelled: Boolean = false) : Download
}

class ModelStore(context: Context) {

    private val app = context.applicationContext
    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

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
     * Decodes with half the cores and stops a reply once the phone is throttling.
     * On by default: a phone that is too hot to hold is worse than a slower answer.
     */
    var keepCool: Boolean = prefs.getBoolean("cool", true)
        private set

    /**
     * Off by default, even on chips whose GPU is fast.
     *
     * The GPU reads a prompt several times quicker, but writing the reply — which is
     * where nearly all the time goes — is limited by memory speed, not arithmetic.
     * Measurements on this class of phone put GPU decoding at roughly 1.4x the energy
     * per word of the CPU, and its heat lands in a smaller part of the chip.
     */
    var useGpu: Boolean = prefs.getBoolean("gpu", false)
        private set

    var themeMode: ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        private set

    /**
     * The instructions sent before every conversation.
     *
     * Alongside the text, maik stores the default it was based on. If the two match,
     * the words are maik's own and a new version is free to improve them; if they
     * differ, the user wrote this and it is left exactly as typed. Prompts saved
     * before 3.0 carry no base and are treated as maik's, which is true of every
     * install that never opened that setting.
     */
    var systemPrompt: String = prefs.getString("system", null)
        .let { saved ->
            val base = prefs.getString("system_base", null)
            val maiksOwnWords = saved == null ||
                saved == base ||
                // Written before 3.0, when the base was not recorded: only maik's own
                // wording of the day may be replaced, never something typed by hand.
                (base == null && saved in PRE_3_0_DEFAULTS)
            if (maiksOwnWords) DEFAULT_SYSTEM_PROMPT else saved
        }
        private set

    fun select(next: ModelSpec) {
        spec = next
        prefs.edit().putString("model", next.id).apply()
    }

    fun setHaptics(enabled: Boolean) {
        haptics = enabled
        prefs.edit().putBoolean("haptics", enabled).apply()
    }

    /** Short answers by default: quicker, cooler, and what a phone question usually needs. */
    var shortAnswers: Boolean = prefs.getBoolean("short", true)
        private set

    fun setShortAnswers(enabled: Boolean) {
        shortAnswers = enabled
        prefs.edit().putBoolean("short", enabled).apply()
    }

    fun setKeepCool(enabled: Boolean) {
        keepCool = enabled
        prefs.edit().putBoolean("cool", enabled).apply()
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
    fun beginRiskyLoad(gpu: Boolean) =
        prefs.edit().putBoolean("loading", true).putBoolean("loading_gpu", gpu).commit()

    fun endRiskyLoad() = prefs.edit().putBoolean("loading", false).commit()

    /** Clears the note without blocking; for startup, on the main thread. */
    fun clearCrashMarker() = prefs.edit().putBoolean("loading", false).apply()

    fun lastLoadCrashed(): Boolean = prefs.getBoolean("loading", false)

    /** Whether the load that crashed was trying the GPU. */
    fun lastCrashWasGpu(): Boolean = prefs.getBoolean("loading_gpu", true)

    fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setSystemPrompt(text: String) {
        systemPrompt = text.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
        prefs.edit()
            .putString("system", systemPrompt)
            .putString("system_base", DEFAULT_SYSTEM_PROMPT)
            .apply()
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
    fun check(s: ModelSpec): String? = fileFor(s).let {
        if (it.exists()) validate(it)?.let { problem -> text(problem) } else null
    }

    fun installed(): Set<String> = Models.ALL.filter { isReady(it) }.map { it.id }.toSet()

    /** Everything a model takes: the file, any partial download, and the runtime's cache. */
    fun bytesOnDisk(): Long = Models.ALL.sumOf { bytesFor(it) }

    /**
     * Downloads a model, resuming from an interrupted `.part` file when there is one.
     * Nothing counts as installed until the file is complete and has the right header.
     */
    fun download(s: ModelSpec = spec): Flow<Download> = flow {
        val target = fileFor(s)
        val partial = partFor(s)
        fun failed(reason: String) = Download.Failed(s.id, reason)
        val stamp = File(dir, "${s.fileName}.part.sha256")
        try {
            // The partial belongs to whatever file the catalogue pointed at when it was
            // started. If the app has since pinned a different file, start clean.
            if (partial.exists() && stamp.exists() && stamp.readText().trim() != s.sha256) {
                partial.delete()
            }
            stamp.writeText(s.sha256)
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
                        emit(failed(text(R.string.download_server_answered, code)))
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
                            emit(failed(text(R.string.download_cannot_continue)))
                            return@flow
                        }

                        ResumePlan.Outcome.AlreadyComplete -> Unit

                        is ResumePlan.Outcome.Copy -> {
                            if (!outcome.resuming) partial.delete()
                            val remaining = outcome.total - outcome.start
                            if (!hasRoomFor(remaining, s)) {
                                emit(failed(text(R.string.download_not_enough_space, android.text.format.Formatter.formatShortFileSize(app, remaining))))
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
                                emit(failed(text(R.string.download_interrupted)))
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
            // Hashing gigabytes takes a while; say so rather than sit at 100%.
            emit(Download.Progress(partial.length(), partial.length(), verifying = true))
            if (!sha256Of(partial).equals(s.sha256, ignoreCase = true)) {
                partial.delete()
                emit(failed(text(R.string.download_corrupted)))
                return@flow
            }
            validate(partial)?.let { problem ->
                partial.delete()
                emit(failed(text(problem)))
                return@flow
            }

            currentCoroutineContext().ensureActive()
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                emit(failed(text(R.string.download_could_not_save)))
                return@flow
            }
            stamp.delete()
            emit(Download.Done(target, s.id))
        } catch (e: CancellationException) {
            // Cancelled on purpose: keep the partial file so the next attempt resumes.
            throw e
        } catch (e: Exception) {
            emit(failed(humanise(e)))
        }
    }.flowOn(Dispatchers.IO)

    /** Everything on disk for [s]: the file, any partial download, and the runtime's cache. */
    fun bytesFor(s: ModelSpec): Long =
        fileFor(s).length() + partFor(s).length() +
            File(cacheRoot, s.id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Throws away an unfinished download. */
    fun removePartial(s: ModelSpec) {
        partFor(s).delete()
        File(dir, "${s.fileName}.part.sha256").delete()
    }

    fun delete(s: ModelSpec = spec) {
        fileFor(s).delete()
        partFor(s).delete()
        File(dir, "${s.fileName}.part.sha256").delete()
        File(cacheRoot, s.id).deleteRecursively()
    }

    /** Room for the rest of the file plus the runtime's prepared copy of it. */
    private fun hasRoomFor(bytes: Long, s: ModelSpec): Boolean =
        runCatching { dir.usableSpace > bytes + maxOf(512L shl 20, s.approxBytes / 4) }.getOrDefault(false)

    private fun humanise(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> text(R.string.download_no_connection)
        is java.net.SocketTimeoutException -> text(R.string.download_timed_out)
        is java.io.IOException ->
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true) {
                text(R.string.download_disk_full)
            } else {
                text(R.string.download_dropped)
            }
        else -> e.message ?: e::class.java.simpleName
    }

    internal companion object {
        /**
         * The default instructions as maik worded them before 3.0, when nothing
         * recorded which default a saved prompt came from. Kept so an upgrade can tell
         * its own old wording from something the user wrote. Nothing is added here
         * after 3.0: `system_base` answers the question from now on.
         */
        val PRE_3_0_DEFAULTS = setOf(
            "You are maik, a helpful assistant running entirely on the user's phone. " +
                "Answer clearly and concisely.",
            "You are maik, an assistant running entirely on the user's phone, offline.\n\n" +
                "You have no internet, no search, no apps, no location and no live data. You " +
                "cannot look anything up, check prices or availability, book or order anything, " +
                "send messages, open links, or read anything the user has not written to you. " +
                "Never offer to do those things and never ask for details you could only use by " +
                "doing them.\n\n" +
                "Answer from what you already know, in the first reply, even when the answer can " +
                "only be general advice. Say plainly when something needs checking online, needs a " +
                "newer source than your training, or when you are unsure — then give the best " +
                "answer you can anyway. Be clear and concise.",
            "You are maik, an assistant running entirely on the user's phone, offline.\n\n" +
                "You have no internet, no search, no apps, no location and no live data. You " +
                "cannot look anything up, check prices or availability, book or order anything, " +
                "send messages, open links, or read anything the user has not written to you. " +
                "Never offer to do those things and never ask for details you could only use by " +
                "doing them.\n\n" +
                "Answer from what you already know, in the first reply, even when the answer can " +
                "only be general advice. Be clear and concise.\n\n" +
                "Never invent specifics. If you are not sure of a name, number, price, timetable, " +
                "address, quotation, command or line of code, say you are not sure rather than " +
                "producing something that looks right. \"I do not know\" and \"you would need to " +
                "check\" are good answers. Say when something may have changed since you were " +
                "trained. If a question is vague, answer the most likely reading of it and say " +
                "which reading you took, instead of asking the user to start again."
        )

        /** Anything smaller than this is a stub or an error page, not a model. */
        const val MIN_PLAUSIBLE_BYTES = 20L * 1024 * 1024

        /** Every LiteRT-LM bundle opens with these eight ASCII bytes. */
        const val MAGIC = "LITERTLM"

        suspend fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    // Gigabytes of hashing must stop the moment the download is cancelled.
                    currentCoroutineContext().ensureActive()
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

        fun validate(file: File): Int? = try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(MAGIC.length)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) == MAGIC) null
                else R.string.download_not_a_model
            }
        } catch (_: Exception) {
            R.string.download_unreadable
        }
    }
}
