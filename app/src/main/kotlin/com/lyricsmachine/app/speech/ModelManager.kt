package com.lyricsmachine.app.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data class Ready(val file: File) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads each Whisper model once into [dir], verifying its SHA-256 before it becomes [ModelState.Ready].
 * All file and hash work runs on [io]; concurrent [ensure] calls for one spec share a single download.
 */
class ModelManager(
    private val dir: File,
    private val http: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sourceOf: (ModelSpec) -> ModelSource = { it.source },
) {
    private class Entry {
        val state = MutableStateFlow<ModelState>(ModelState.Missing)
        val lock = Mutex()
        var inFlight: Deferred<Unit>? = null
        /** Completes once the on-disk check started by [entry] has finished. */
        val initial = CompletableDeferred<Unit>()
    }

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val entries = ConcurrentHashMap<ModelSpec, Entry>()

    fun state(spec: ModelSpec): StateFlow<ModelState> = entry(spec).state.asStateFlow()

    /** Waits for the initial verification of any file already on disk, then returns the current state. */
    suspend fun awaitInitialState(spec: ModelSpec): ModelState {
        val e = entry(spec)
        e.initial.await()
        return e.state.value
    }

    /** Makes [spec] available; the outcome is reported through [state]. Safe to call again after a failure. */
    suspend fun ensure(spec: ModelSpec) {
        val e = entry(spec)
        val job = synchronized(e) {
            e.inFlight?.takeIf { it.isActive }
                ?: scope.async { e.lock.withLock { download(sourceOf(spec), e) } }.also { e.inFlight = it }
        }
        job.await()
    }

    private fun entry(spec: ModelSpec): Entry {
        entries[spec]?.let { return it }
        val created = Entry()
        val existing = entries.putIfAbsent(spec, created)
        if (existing != null) return existing
        scope.launch {
            try {
                created.lock.withLock {
                    if (created.state.value == ModelState.Missing) {
                        val target = File(dir, sourceOf(spec).fileName)
                        if (verifyExisting(target, sourceOf(spec))) created.state.value = ModelState.Ready(target)
                    }
                }
            } finally {
                created.initial.complete(Unit)
            }
        }
        return created
    }

    /** True when [target] exists with the expected hash; a corrupt file is deleted. */
    private fun verifyExisting(target: File, src: ModelSource): Boolean {
        if (!target.isFile) return false
        val ok = try {
            target.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().toHex().equals(src.sha256, ignoreCase = true)
            }
        } catch (_: IOException) {
            false
        }
        if (!ok) target.delete()
        return ok
    }

    private suspend fun download(src: ModelSource, e: Entry) {
        val target = File(dir, src.fileName)
        if (e.state.value is ModelState.Ready && target.isFile) return
        if (verifyExisting(target, src)) {
            e.state.value = ModelState.Ready(target)
            return
        }
        val part = File(dir, src.fileName + ".part")
        e.state.value = ModelState.Downloading(0f)
        try {
            dir.mkdirs()
            val request = Request.Builder().url(src.url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    e.state.value = ModelState.Failed("HTTP ${response.code}")
                    return
                }
                val body = response.body ?: throw IOException("empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: src.sizeBytes
                val md = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(BUFFER)
                        var read = 0L
                        var lastReported = 0f
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read.toFloat() / total).coerceIn(0f, 1f)
                                if (p - lastReported >= 0.01f) {
                                    lastReported = p
                                    e.state.value = ModelState.Downloading(p)
                                }
                            }
                        }
                        out.fd.sync()
                    }
                }
                if (!md.digest().toHex().equals(src.sha256, ignoreCase = true)) {
                    e.state.value = ModelState.Failed("checksum")
                    return
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                e.state.value = ModelState.Ready(target)
            }
        } catch (c: CancellationException) {
            e.state.value = ModelState.Missing
            throw c
        } catch (x: Exception) {
            e.state.value = ModelState.Failed(x.message?.takeIf { it.isNotBlank() } ?: x.javaClass.simpleName)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
