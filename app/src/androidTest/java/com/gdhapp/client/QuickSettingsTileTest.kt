package com.gdhapp.client

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.data.ShareLinkParser
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.quicksettings.*
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import libv2ray.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class QuickSettingsTileTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val component get() = ComponentName(app, VpnTileService::class.java).flattenToShortString()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && predicate(current)) return current
            repeat(current.childCount) { current.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) { find(predicate)?.let { return it }; Thread.sleep(100) }
        screenshot("failure")
        error("Missing expected Quick Settings UI")
    }
    private fun clickText(text: String) {
        var node: AccessibilityNodeInfo? = awaitNode { it.text?.toString() == text || it.contentDescription?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(250)
    }
    private fun systemButton(id: String) {
        val node = awaitNode { it.viewIdResourceName == "android:id/$id" && it.packageName?.toString() in setOf("com.android.systemui", "com.android.vpndialogs") }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(300)
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        app.externalCacheDir!!.resolve("tile-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun revealTile() {
        shell("cmd statusbar collapse")
        Thread.sleep(400)
        shell("cmd statusbar expand-settings")
        Thread.sleep(1000)
        repeat(8) { page ->
            if (find { it.packageName?.toString() == "com.android.systemui" && it.text?.toString() == "NyaWire" } != null) return
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.systemui") shell("cmd statusbar expand-settings")
            else if (page < 4) shell("input swipe 120 900 950 900 350")
            else shell("input swipe 950 900 120 900 350")
            Thread.sleep(500)
        }
        awaitNode { it.packageName?.toString() == "com.android.systemui" && it.text?.toString() == "NyaWire" }
    }
    private fun clickTile() {
        val node = awaitNode { it.packageName?.toString() == "com.android.systemui" && it.text?.toString() == "NyaWire" }
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        Thread.sleep(350)
    }
    private suspend fun verifyTraffic(stage: String) {
        delay(1500) // ConnectivityService applies the newly established VPN to shell UID asynchronously.
        var result = ""
        repeat(3) {
            result = shell("sh /data/local/tmp/gdhapp-vpn-probe.sh 2>&1")
            if (result.contains("NyaWire-TUN-OK")) return
            delay(300)
        }
        fail("No HTTP response through the tile's VPN ($stage): $result")
    }

    @Test fun systemCanDiscoverOnlyPermissionProtectedTileAndConsentActivityIsPrivate() {
        val service = app.packageManager.getServiceInfo(ComponentName(app, VpnTileService::class.java), PackageManager.GET_META_DATA)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.permission)
        assertTrue(service.metaData.getBoolean("android.service.quicksettings.TOGGLEABLE_TILE"))
        assertFalse(app.packageManager.getActivityInfo(ComponentName(app, QuickConnectActivity::class.java), 0).exported)
        assertTrue(app.packageManager.queryIntentServices(Intent("android.service.quicksettings.action.QS_TILE").setPackage(app.packageName), 0).any { it.serviceInfo.name == VpnTileService::class.java.name })
    }

    @Test fun consentAndToggleVpnFromSystemShade(): Unit = runBlocking {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33 && android.os.Build.MODEL.contains("sdk"))
        val original = app.settings.settings.value
        val originalGroups = app.subscriptions.groups.value
        val tileWasPresent = shell("settings get secure sysui_qs_tiles").contains(component)
        val port = ServerSocket(0).use { it.localPort }
        val uuid = "00000000-0000-4000-8000-000000000077"
        app.coreSessions.initializeXray()
        val server = CoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String?) = 0L
        })
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            server.startLoop("""{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["10.0.2.2/32"]}]}}]}""", 0)
            app.subscriptions.importText(ShareLinkParser.parse("vless://$uuid@127.0.0.1:$port?security=none&type=tcp#QS%20tile%20test").toString())
            val node = app.subscriptions.groups.value.first { group -> originalGroups.none { it.id == group.id } }.servers.first()
            app.subscriptions.selectNode(node.id)
            app.settings.update { it.copy(language = AppLanguage.RUSSIAN, coreEngine = CoreEngine.XRAY, connectionMode = ConnectionMode.VPN,
                xrayCompatibilityMode = false, bypassLan = false, verifyOnConnect = false, localSocksEnabled = false,
                connectOnOpen = false, connectOnBoot = false, refreshOnOpen = false, perAppMode = PerAppMode.ALL, animations = false) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            shell("cmd statusbar add-tile $component")
            withTimeout(10000) { while (!shell("settings get secure sysui_qs_tiles").contains(component)) delay(100) }
            shell("input keyevent KEYCODE_HOME")
            shell("cmd appops set com.gdhapp.client ACTIVATE_VPN deny")
            assertNotNull(VpnService.prepare(app))
            revealTile()
            clickTile()
            // Cancel first consent: no tunnel may be started as a side effect.
            systemButton("button2")
            assertEquals(VpnState.Disconnected, app.vpnSession.state.value)
            shell("input keyevent KEYCODE_HOME"); delay(700)
            revealTile()
            clickTile()
            systemButton("button1")
            val connected = withTimeout(20000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } }
            assertEquals(connected.toString(), VpnState.Connected, connected)
            verifyTraffic("first consent")
            revealTile()
            screenshot("active")
            clickTile()
            withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            delay(300); screenshot("inactive")
            // Existing consent: start directly from the tile while the full app is backgrounded.
            shell("input keyevent KEYCODE_HOME"); delay(1500)
            revealTile()
            val monitor = instrumentation.addMonitor(QuickConnectActivity::class.java.name, null, false)
            try {
                clickTile()
                assertEquals(VpnState.Connected, withTimeout(20000) { app.vpnSession.state.first { it == VpnState.Connected || it is VpnState.Error } })
                assertEquals(0, monitor.hits)
                verifyTraffic("background reconnect")
                clickTile()
                withTimeout(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            if (app.vpnSession.state.value == VpnState.Connected || app.vpnSession.state.value == VpnState.Connecting) {
                GdhappVpnService.stop(app); withTimeoutOrNull(10000) { app.vpnSession.state.first { it == VpnState.Disconnected } }
            }
            shell("cmd statusbar collapse")
            if (!tileWasPresent) shell("cmd statusbar remove-tile $component")
            scenario?.close()
            runCatching { server.stopLoop() }
            app.subscriptions.groups.value.filter { group -> originalGroups.none { it.id == group.id } }.forEach { app.subscriptions.remove(it.id) }
            originalGroups.flatMap { it.servers }.firstOrNull { it.isSelected }?.let { app.subscriptions.selectNode(it.id) }
            app.settings.update { original }
            shell("cmd appops set com.gdhapp.client ACTIVATE_VPN allow")
        }
    }
}
