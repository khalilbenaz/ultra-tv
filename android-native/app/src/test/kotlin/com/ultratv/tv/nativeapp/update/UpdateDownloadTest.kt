package com.ultratv.tv.nativeapp.update

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Téléchargement de mise à jour : un seul à la fois, jamais d'APK tronqué installé. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadTest {
    private val server = MockWebServer()
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val hex = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var apkRequests = 0
    private var truncateFirst = false

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/UltraTV-x.apk" -> {
                    apkRequests++
                    if (truncateFirst && apkRequests == 1) {
                        // Corps coupé avant la fin : la longueur annoncée n'est pas atteinte.
                        MockResponse().setBody(okio.Buffer().write(apk)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    } else MockResponse().setBody(okio.Buffer().write(apk)).setBodyDelay(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                "/SHA256SUMS.txt" -> MockResponse().setBody("$hex  UltraTV-x.apk\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown() }

    private fun info(v: String) = UpdateChecker.UpdateInfo(
        tag = "v$v", versionName = v, versionCode = 1, apkUrl = server.url("/UltraTV-x.apk").toString(), notes = "",
        apkName = "UltraTV-x.apk", sumsUrl = server.url("/SHA256SUMS.txt").toString(),
    )

    @Test fun deuxTelechargementsSimultanes_unSeulTelechargement_apkIntact() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val i = info("9.9.1")
        val files = listOf(async { UpdateChecker.fetchVerifiedLocked(ctx, i) }, async { UpdateChecker.fetchVerifiedLocked(ctx, i) }).awaitAll()
        assertEquals(files[0], files[1])
        assertEquals(hex, UpdateChecker.sha256Hex(files[0]))
        assertEquals(1, apkRequests)   // le second appel réutilise l'APK déjà vérifié
    }

    @Test fun telechargementCoupe_secondeTentative_apkIntact() = runBlocking {
        truncateFirst = true
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = UpdateChecker.fetchVerifiedLocked(ctx, info("9.9.2"))
        assertEquals(hex, UpdateChecker.sha256Hex(f))
        assertEquals(2, apkRequests)
        assertTrue(f.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }
}
