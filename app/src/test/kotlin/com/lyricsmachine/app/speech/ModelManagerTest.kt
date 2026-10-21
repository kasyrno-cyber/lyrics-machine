package com.lyricsmachine.app.speech

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Plain JVM test: a fake model (generated random bytes) served by MockWebServer. */
class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val body: ByteArray = Random(42).nextBytes(256 * 1024)
    private val spec = ModelSpec.TINY

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        dir = tmp.newFolder("models")
    }

    @After fun tearDown() { server.shutdown() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun source(sha: String = sha256(body)) =
        ModelSource(spec.fileName, server.url("/" + spec.fileName).toString(), sha, body.size.toLong())

    private fun manager(sha: String = sha256(body)) = ModelManager(dir, http) { source(sha) }

    private fun ok() = MockResponse().setBody(Buffer().write(body))

    private suspend fun ModelManager.awaitState(pred: (ModelState) -> Boolean): ModelState =
        withTimeout(10_000) { state(spec).first(pred) }

    @Test fun `downloads and verifies`() = runBlocking {
        server.enqueue(ok())
        val mm = manager()
        mm.ensure(spec)
        val state = mm.state(spec).value
        assertTrue("state was $state", state is ModelState.Ready)
        val file = (state as ModelState.Ready).file
        assertEquals(File(dir, spec.fileName), file)
        assertArrayEquals(body, file.readBytes())
        assertFalse(File(dir, spec.fileName + ".part").exists())
        assertEquals(1, server.requestCount)
    }

    @Test fun `checksum mismatch fails and removes part file`() = runBlocking {
        server.enqueue(ok())
        val mm = manager(sha = "0".repeat(64))
        mm.ensure(spec)
        assertEquals(ModelState.Failed("checksum"), mm.state(spec).value)
        assertFalse(File(dir, spec.fileName + ".part").exists())
        assertFalse(File(dir, spec.fileName).exists())
    }

    @Test fun `ready when file already present with correct hash`() = runBlocking {
        File(dir, spec.fileName).writeBytes(body)
        val mm = manager()
        val state = mm.awaitState { it is ModelState.Ready }
        assertEquals(File(dir, spec.fileName), (state as ModelState.Ready).file)
        mm.ensure(spec)
        assertTrue(mm.state(spec).value is ModelState.Ready)
        assertEquals(0, server.requestCount)
    }

    @Test fun `existing file with wrong hash is not ready`() = runBlocking {
        File(dir, spec.fileName).writeBytes(byteArrayOf(1, 2, 3))
        val mm = manager()
        mm.awaitState { it is ModelState.Missing }
        server.enqueue(ok())
        mm.ensure(spec)
        assertTrue(mm.state(spec).value is ModelState.Ready)
        assertArrayEquals(body, File(dir, spec.fileName).readBytes())
    }

    @Test fun `network error reports Failed and allows retry`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(ok())
        val mm = manager()
        mm.ensure(spec)
        val failed = mm.state(spec).value
        assertTrue("state was $failed", failed is ModelState.Failed)
        assertTrue((failed as ModelState.Failed).message.isNotBlank())
        assertFalse(File(dir, spec.fileName).exists())

        mm.ensure(spec)
        assertTrue(mm.state(spec).value is ModelState.Ready)
        assertArrayEquals(body, File(dir, spec.fileName).readBytes())
    }

    @Test fun `http error reports Failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val mm = manager()
        mm.ensure(spec)
        assertEquals(ModelState.Failed("HTTP 500"), mm.state(spec).value)
        assertFalse(File(dir, spec.fileName + ".part").exists())
    }

    @Test fun `concurrent ensure downloads once`() = runBlocking {
        server.enqueue(ok().throttleBody(64 * 1024, 50, TimeUnit.MILLISECONDS))
        server.enqueue(ok())
        val mm = manager()
        listOf(async { mm.ensure(spec) }, async { mm.ensure(spec) }).awaitAll()
        assertTrue(mm.state(spec).value is ModelState.Ready)
        assertEquals(1, server.requestCount)
    }

    @Test fun `progress is reported while downloading`() = runBlocking {
        server.enqueue(ok().throttleBody(32 * 1024, 30, TimeUnit.MILLISECONDS))
        val mm = manager()
        val job = async { mm.ensure(spec) }
        val downloading = mm.awaitState { it is ModelState.Downloading && it.progress > 0f }
        assertTrue((downloading as ModelState.Downloading).progress in 0f..1f)
        job.await()
        assertTrue(mm.state(spec).value is ModelState.Ready)
    }

    @Test fun `model specs are pinned`() {
        for (s in ModelSpec.entries) {
            assertTrue(s.name, s.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(s.name, s.sizeBytes > 1_000_000)
            assertTrue(s.name, s.url.endsWith("/" + s.fileName))
        }
    }
}
