package com.gdhapp.client

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.SettingsRepository
import com.gdhapp.client.domain.model.AppLanguage
import com.gdhapp.client.ui.theme.VpnButtonSound
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class SoundSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun <T> withMutedRinger(block: () -> T): T {
        val audio = app.getSystemService(AudioManager::class.java)
        val original = arrayOf("SILENT", "VIBRATE", "NORMAL")[audio.ringerMode]
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            shell("cmd audio set-ringer-mode VIBRATE")
            // Devices without a vibration motor convert VIBRATE to SILENT.
            assertTrue("Regression fixture must mute the ringer", audio.ringerMode in setOf(AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT))
            return block()
        } finally { shell("cmd audio set-ringer-mode $original") }
    }

    @Test fun oldDisabledEffectsAreEnabledAndSoundChoicePersists() {
        val name = "sound-settings-${UUID.randomUUID()}"
        val preferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = preferences
        }
        try {
            preferences.edit().putString("settings", """{"animations":false,"softBlur":false,"haptics":false,"backgroundPictures":false,"probeTimeoutSeconds":11}""").commit()
            val repository = SettingsRepository(context)
            val settings = repository.settings.value
            assertTrue(settings.animations && settings.softBlur && settings.haptics)
            assertTrue(settings.connectionSound)
            assertFalse(settings.backgroundPictures)
            assertEquals(11, settings.probeTimeoutSeconds)
            repository.update { it.copy(connectionSound = false, animations = false, haptics = false, softBlur = false) }
            val restored = SettingsRepository(context).settings.value
            assertFalse(restored.connectionSound)
            assertTrue(restored.animations && restored.softBlur && restored.haptics)
        } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun originalMp3ContainsAudibleDecodedSamples() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            app.resources.openRawResourceFd(R.raw.vpn_button).use { source ->
                extractor.setDataSource(source.fileDescriptor, source.startOffset, source.length)
            }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnd = false
            var outputEnd = false
            var peak = 0
            var squares = 0.0
            var samples = 0L
            val pcm = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 10000
            while (!outputEnd && System.currentTimeMillis() < deadline) {
                if (!inputEnd) {
                    val input = decoder.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10000)
                if (output >= 0) {
                    val buffer = decoder.getOutputBuffer(output)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size); buffer.get(bytes); pcm.write(bytes)
                    val shorts = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shorts.hasRemaining()) {
                        val value = shorts.get().toInt()
                        peak = maxOf(peak, kotlin.math.abs(value)); squares += value.toDouble() * value; samples++
                    }
                    outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(output, false)
                }
            }
            assertTrue("Decoder must reach the end of the clip", outputEnd)
            assertTrue("PCM samples must exist", samples > 1000)
            val rms = kotlin.math.sqrt(squares / samples) / 32768.0
            android.util.Log.i("SoundPcmTest", "samples=$samples peak=$peak rms=$rms format=${decoder.outputFormat}")
            // Confirm actual non-silent audio, rather than a successful player.start().
            assertTrue("Clip is effectively silent: rms=$rms", rms > .005)
            assertTrue("Clip must contain audible peaks", peak > 1000)
            app.cacheDir.resolve("connection-sound-decoded.pcm").writeBytes(pcm.toByteArray())
        } finally {
            runCatching { codec?.stop() }; codec?.release(); extractor.release()
        }
    }

    @Test fun originalMp3DecodesAndPlaybackHonorsSwitchAndRelease() {
        val bytes = app.resources.openRawResource(R.raw.vpn_button).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("723fd5db163b86187bb4ec845513fc3509307f0b33211df82e062ff38ca0c763", hash)
        val metadata = MediaMetadataRetriever()
        try {
            app.resources.openRawResourceFd(R.raw.vpn_button).use { metadata.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("Short effect duration: $duration", duration in 1..10000)
        } finally { metadata.release() }
        lateinit var sound: VpnButtonSound
        instrumentation.runOnMainSync { sound = VpnButtonSound(app) }
        try { ActivityScenario.launch(MainActivity::class.java).use { withMutedRinger {
            val until = System.currentTimeMillis() + 10000
            while (!sound.isLoaded && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue("MP3 must prepare in MediaPlayer", sound.isLoaded)
            instrumentation.runOnMainSync {
                sound.enabled = false
                assertFalse(sound.play())
                sound.enabled = true
                assertTrue("Native audio playback must start", sound.play())
                assertTrue(sound.isPlaying)
            }
            await {
                var position = 0
                instrumentation.runOnMainSync { position = sound.positionMs }
                position >= 40
            }
            instrumentation.runOnMainSync {
                assertTrue("Playback clock must advance", sound.positionMs >= 40)
                assertTrue("Repeated taps restart playback", sound.play())
                sound.enabled = false
                assertFalse(sound.isPlaying)
                assertFalse(sound.play())
                sound.close()
                assertFalse(sound.play())
            }
        } } } finally { instrumentation.runOnMainSync { sound.close() } }
    }

    @Test fun actualVpnButtonProducesMediaPlaybackWithMutedRinger() {
        org.junit.Assume.assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        assertFalse(app.vpnSession.state.value == com.gdhapp.client.domain.model.VpnState.Connected)
        assertNull(android.net.VpnService.prepare(app))
        val settings = app.settings.settings.value
        val groups = app.subscriptions.groups.value
        fun audioDump() = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys audio")).bufferedReader().use { it.readText() }
        try {
            app.settings.update { it.copy(language = AppLanguage.RUSSIAN, connectionSound = true, refreshOnOpen = false,
                connectOnOpen = false, verifyOnConnect = false, coreEngine = com.gdhapp.client.domain.model.CoreEngine.XRAY,
                connectionMode = com.gdhapp.client.domain.model.ConnectionMode.VPN, perAppMode = com.gdhapp.client.domain.model.PerAppMode.ALL) }
            runBlocking { app.subscriptions.importText("vless://00000000-0000-4000-8000-000000000097@192.0.2.1:443?security=none&type=tcp#Sound-button-fixture") }
            val group = app.subscriptions.groups.value.single { current -> groups.none { it.id == current.id } }
            runBlocking { app.subscriptions.selectNode(group.servers.single().id) }
            withMutedRinger {
                ActivityScenario.launch(MainActivity::class.java).use {
                    await { nodes().any { node -> node.contentDescription?.toString() == "Подключить VPN" } }
                    // Public playback callbacks anonymize attributes. Inspect AudioService's own
                    // event log via the test shell, without granting extra app permissions.
                    val player = Regex("new player piid:(\\d+) uid/pid:${android.os.Process.myUid()}/.*type:android.media.MediaPlayer").findAll(audioDump()).last().groupValues[1]
                    assertTrue("Player must use media output", "player piid:$player new AudioAttributes:AudioAttributes: usage=USAGE_MEDIA" in audioDump())
                    val before = audioDump().lines().count { line -> "player piid:$player event:started" in line }
                    click("Подключить VPN")
                    await { audioDump().lines().count { line -> "player piid:$player event:started" in line } > before }
                    // The dummy endpoint may fail immediately. Audio playback, not connectivity,
                    // is the assertion; allow the asynchronous service start before cleanup.
                    Thread.sleep(1000)
                }
            }
        } finally {
            com.gdhapp.client.vpn.GdhappVpnService.stop(app)
            val until = System.currentTimeMillis() + 8000
            while (app.vpnSession.state.value != com.gdhapp.client.domain.model.VpnState.Disconnected && System.currentTimeMillis() < until) Thread.sleep(100)
            runBlocking {
                app.subscriptions.groups.value.filter { current -> groups.none { it.id == current.id } }.forEach { app.subscriptions.remove(it.id) }
                groups.flatMap { it.servers }.firstOrNull { it.isSelected }?.let { app.subscriptions.selectNode(it.id) }
            }
            app.settings.update { settings }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 12000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("UI condition timed out", condition())
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = null
        await { node = nodes().lastOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }; node != null }
        val bounds = android.graphics.Rect().also { node!!.getBoundsInScreen(it) }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
    }
    @Test fun soundSearchOpensSwitchAndRemovedControlsAreAbsent() {
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, connectionSound = true, refreshOnOpen = false, connectOnOpen = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click("Настройки")
                val field = nodes().single { it.className?.toString() == "android.widget.EditText" }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "звук")
                }))
                Thread.sleep(500)
                click("Звук подключения")
                await { nodes().any { node -> node.text?.toString() == "Звук при нажатии «Подключить»." } }
                fun starts() = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys audio")).bufferedReader().use { it.readText() }
                    .lines().count { line -> "event:started" in line }
                val previous = starts()
                click("Прослушать звук")
                await { starts() > previous }
                assertTrue("Preview must not start VPN", app.vpnSession.state.value == com.gdhapp.client.domain.model.VpnState.Disconnected)
                click("Звук подключения")
                await { !app.settings.settings.value.connectionSound }
                assertFalse(SettingsRepository(app).settings.value.connectionSound)
                assertFalse(nodes().any { it.text?.toString() in listOf("Плавные анимации", "Мягкое размытие", "Тактильный отклик") })
                assertTrue(app.settings.settings.value.let { settings -> settings.animations && settings.haptics && settings.softBlur })
            }
        } finally { app.settings.update { original } }
    }
}
