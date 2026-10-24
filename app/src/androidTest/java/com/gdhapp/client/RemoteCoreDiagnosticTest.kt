package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.NetworkLatencyTester
import com.gdhapp.client.data.ShareLinkParser
import com.gdhapp.client.domain.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/** Opt-in diagnosis. Credentials are supplied on the device at runtime, never stored in the test APK. */
@RunWith(AndroidJUnit4::class)
class RemoteCoreDiagnosticTest {
    @Test fun compareHysteria2Cores() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("profileFile")
        assumeTrue("No runtime profile supplied", path != null)
        require(path!!.matches(Regex("/data/local/tmp/[A-Za-z0-9_.-]+")))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        val link = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        val native = ShareLinkParser.parse(link)
        val out = native.getAsJsonArray("outbounds")[0].asJsonObject
        InstrumentationRegistry.getArguments().getString("udpRelayPort")?.toInt()?.let { port ->
            require(port in 1024..65535)
            out.addProperty("server", "10.0.2.2"); out.addProperty("server_port", port)
        }
        require(out.get("type").asString == "hysteria2")
        val tls = out.getAsJsonObject("tls")
        val xray = JsonObject().apply { add("outbounds", JsonArray().apply { add(JsonObject().apply {
            addProperty("protocol", "hysteria")
            add("settings", JsonObject().apply { addProperty("version", 2); add("address", out.get("server")); add("port", out.get("server_port")) })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "hysteria"); addProperty("security", "tls")
                add("tlsSettings", JsonObject().apply { add("serverName", tls.get("server_name")); add("alpn", tls.get("alpn")); add("allowInsecure", tls.get("insecure")) })
                add("hysteriaSettings", JsonObject().apply { addProperty("version", 2); add("auth", out.get("password")) })
                out.getAsJsonObject("obfs")?.let { obfs -> add("finalmask", JsonObject().apply { add("udp", JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "salamander"); add("settings", JsonObject().apply { add("password", obfs.get("password")) })
                }) }) }) }
            })
        }) }) }
        val secrets = listOfNotNull(out.get("password")?.asString, out.getAsJsonObject("obfs")?.get("password")?.asString)
        fun redacted(value: String): String = secrets.fold(value) { result, secret -> if (secret.isNotBlank()) result.replace(secret, "[redacted]") else result }.take(1500)
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val failures = mutableListOf<String>()
        for (engine in listOf(CoreEngine.SING_BOX, CoreEngine.XRAY)) {
            val profile = if (engine == CoreEngine.SING_BOX) native else xray
            for (mode in listOf(ProbeConnectionMode.DEFAULT, ProbeConnectionMode.KEEP_ALIVE)) {
                val settings = ClientSettings(coreEngine = engine, probeConnectionMode = mode, logLevel = "debug", probeTimeoutSeconds = 8)
                try {
                    app.coreSessions.open(profile.toString(), settings, forProbe = true).use { session ->
                        for (method in listOf(ProbeMethod.HTTP_GET, ProbeMethod.HTTP_HEAD)) {
                            val result = runCatching { NetworkLatencyTester.httpViaProxy(session.port, method, settings) }
                            delay(250)
                            val report = "$engine $mode $method: " + result.fold({ "HTTP ${it.second}, ${it.first}ms" }, { redacted(it.toString()) + "; core: " + redacted(session.errorDetail) })
                            android.util.Log.i("RemoteCoreDiagnostic", report)
                            println(report)
                            if (result.isFailure) failures.add("$engine $mode $method")
                        }
                    }
                } catch (error: Exception) { println("$engine $mode: ${redacted(error.toString())}"); failures.add("$engine $mode startup") }
            }
        }
        assertTrue("Failed cases: $failures", failures.isEmpty())
    }
}
