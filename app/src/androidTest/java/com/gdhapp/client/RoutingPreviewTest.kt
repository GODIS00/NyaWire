package com.gdhapp.client

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.*
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.main.*
import com.gdhapp.client.ui.theme.NyaWireTheme
import org.junit.Test
import org.junit.runner.RunWith

/** Local preview data only; never connects to a VPN. Screenshots go to the app's external cache. */
@RunWith(AndroidJUnit4::class)
class RoutingPreviewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val routing = ImportedRouting(name = "Провайдер · Россия", directSites = listOf("geosite:category-ru", "geosite:tld-ru"),
        directIps = listOf("geoip:ru", "geoip:private"), proxySites = listOf("geosite:youtube", "geosite:telegram"),
        blockSites = listOf("geosite:category-ads-all"), skipped = listOf("geosite:ru-blocked"))

    @Test fun captureButtonImportAndRoutingSettings() {
        val original = app.settings.settings.value
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, refreshOnOpen = false, connectOnOpen = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val nodes = listOf(ServerNode("one", "Нидерланды #1", "NL", "VLESS / TCP / REALITY", "{}", isSelected = true),
                    ServerNode("two", "Германия #2", "DE", "VLESS / WS / TLS", "{}"))
                fun show(light: Boolean, vpnState: VpnState, pending: ImportedRouting? = null, change: (ClientSettings) -> ClientSettings = { it }) =
                    scenario.onActivity { activity -> activity.setContent {
                        val settings = change(ClientSettings(language = AppLanguage.RUSSIAN, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK,
                            connectionSound = false, powerBorder = PowerBorder.CUSTOM, powerGradientStart = 0xFF6E8BFF, powerGradientEnd = 0xFFE070D3))
                        val state = MainUiState(settings = settings, vpnState = vpnState, pendingRouting = pending,
                            groups = listOf(SubscriptionGroup("preview", "Список серверов", lastUpdated = "", servers = nodes)))
                        NyaWireTheme(settings) { MainScreen(state, MainActions(), remember { SnackbarHostState() }) }
                    } }
                for (light in listOf(false, true)) for (vpnState in listOf(VpnState.Disconnected, VpnState.Connected)) {
                    show(light, vpnState); Thread.sleep(2200)
                    capture("button-${if (light) "light" else "dark"}-${if (vpnState == VpnState.Connected) "on" else "off"}")
                }
                // Hold a finger on the upper right of the dome: it should sink and lean that way.
                show(false, VpnState.Disconnected); Thread.sleep(2000)
                // The bottom bar repeats this label; the card's button is the highest one.
                val bounds = findAll(AppText.text(R.string.connect_vpn)).map { node -> android.graphics.Rect().also { node.getBoundsInScreen(it) } }.minBy { it.top }
                app.externalCacheDir!!.resolve("routing-preview").apply { mkdirs() }.resolve("button-bounds.txt").writeText(bounds.toShortString())
                val x = bounds.centerX() + bounds.width() * .9f; val y = bounds.centerY() - bounds.height() * .9f
                // Shell input reaches Compose like a finger; a held DOWN keeps the key pressed for the capture.
                shell("input motionevent DOWN ${x.toInt()} ${y.toInt()}"); Thread.sleep(2000)
                capture("button-dark-pressed")
                shell("input motionevent CANCEL ${x.toInt()} ${y.toInt()}"); Thread.sleep(600)

                show(false, VpnState.Disconnected, pending = routing); Thread.sleep(1500)
                capture("routing-import-dialog")

                for (light in listOf(false, true)) {
                    show(light, VpnState.Disconnected) { it.copy(routingMode = RoutingMode.RULES, routeRussiaDirect = true, blockAds = true, importedRouting = routing) }
                    Thread.sleep(1500)
                    click(AppText.text(R.string.settings))
                    capture("settings-overview-${if (light) "light" else "dark"}")
                    click(AppText.text(R.string.routing_title))
                    shell("input swipe 540 1700 540 1100 500"); Thread.sleep(1200)
                    capture("routing-settings-${if (light) "light" else "dark"}-1")
                    shell("input swipe 540 1800 540 900 500"); Thread.sleep(1200)
                    capture("routing-settings-${if (light) "light" else "dark"}-2")
                }
            }
        } finally { app.settings.update { original } }
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        app.externalCacheDir!!.resolve("routing-preview").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun findAll(label: String): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            // Merged tiles read as "Title, subtitle".
            val text = node.text?.toString().orEmpty(); val description = node.contentDescription?.toString().orEmpty()
            if (node.isVisibleToUser && (text == label || description == label || text.startsWith("$label,") || description.startsWith("$label,"))) found.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }
    // The accessibility snapshot can lag behind a page transition; look again briefly before giving up.
    private fun find(label: String) = (0 until 10).firstNotNullOfOrNull { attempt ->
        if (attempt > 0) Thread.sleep(300)
        findAll(label).lastOrNull()
    } ?: run {
        val seen = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && (node.text != null || node.contentDescription != null)) seen.add("${node.text}|${node.contentDescription}")
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        throw AssertionError("No '$label' among: ${seen.joinToString(" ; ")}")
    }

    private fun click(label: String) {
        val node = find(label)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        Thread.sleep(1500)
    }

    private fun shell(command: String) =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
}
