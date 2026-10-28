package com.gdhapp.client

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.drawable.toBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.domain.model.AppLanguage
import com.gdhapp.client.icons.AppIcon
import com.gdhapp.client.icons.LauncherIcons
import com.gdhapp.client.qr.QrCaptureActivity
import com.journeyapps.barcodescanner.ScanOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresentationUpdateTest {
    private val app get() = ApplicationProvider.getApplicationContext<GdhappApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun allTenIconsLeaveExactlyOneLauncherAndMainActivityAvailable() {
        val manager = LauncherIcons(app)
        val original = manager.selected()
        val collage = Bitmap.createBitmap(900, 380, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage).apply { drawColor(Color.rgb(16, 19, 28)) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER }
        try {
            assertTrue(AppIcon.entries.size >= 10)
            AppIcon.entries.forEachIndexed { index, icon ->
                manager.select(icon)
                assertEquals(icon, LauncherIcons(app).selected())
                val launchers = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0)
                assertEquals("Duplicate or missing launcher for $icon", listOf(icon.component(app).className), launchers.map { it.activityInfo.name })
                val entry = launchers.single().activityInfo
                assertEquals(MainActivity::class.java.name, entry.targetActivity)
                assertTrue(app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0).enabled)
                assertNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
                val bitmap = entry.loadIcon(app.packageManager).toBitmap(120, 120)
                val x = (index % 5) * 180f + 30f
                val y = (index / 5) * 190f + 14f
                canvas.drawBitmap(bitmap, x, y, null)
                canvas.drawText(app.getString(icon.title), x + 60, y + 154, paint)
                bitmap.recycle()
            }
            save("launcher-collection", collage)
            // Launch through a non-default alias, including after activity recreation.
            manager.select(AppIcon.MINT)
            ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(AppIcon.MINT.component(app))).use { scenario ->
                scenario.recreate()
                scenario.onActivity { assertEquals(AppIcon.MINT, LauncherIcons(it).selected()) }
            }
        } finally { manager.select(original); collage.recycle() }
    }

    @Test fun iconPickerChangesTheActualLauncherAndSurvivesRecreation() {
        val manager = LauncherIcons(app)
        val originalIcon = manager.selected()
        val originalSettings = app.settings.settings.value
        manager.select(AppIcon.ORIGINAL)
        app.settings.update { it.copy(language = AppLanguage.RUSSIAN, refreshOnOpen = false, connectOnOpen = false) }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("Настройки")
            val input = await { it.isEditable }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Иконка приложения")
            }))
            click("Иконка приложения")
            click("Оригинал")
            AppIcon.entries.forEach { icon -> await { it.text?.toString() == com.gdhapp.client.i18n.AppText.text(icon.title) } }
            capture("icon-picker")
            click("Сакура")
            val end = System.currentTimeMillis() + 5000
            while (manager.selected() != AppIcon.SAKURA && System.currentTimeMillis() < end) Thread.sleep(100)
            assertEquals(AppIcon.SAKURA, manager.selected())
            scenario.recreate()
            await { it.text?.toString() == "Сакура" }
            assertEquals(AppIcon.SAKURA, LauncherIcons(app).selected())
        } } finally { manager.select(originalIcon); app.settings.update { originalSettings } }
    }

    @Test fun scannerOpensWithoutRotatingPortraitScreenAndReturnsOnBack() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            main.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(700)
            val options = ScanOptions().setCaptureActivity(QrCaptureActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(true).setBeepEnabled(false)
            ActivityScenario.launch<QrCaptureActivity>(options.createScanIntent(app)).use { scanner ->
                Thread.sleep(1400)
                scanner.onActivity { activity ->
                    assertEquals(Configuration.ORIENTATION_PORTRAIT, activity.resources.configuration.orientation)
                    assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                        app.packageManager.getActivityInfo(activity.componentName, 0).screenOrientation)
                    val viewId = activity.resources.getIdentifier("zxing_barcode_scanner", "id", activity.packageName)
                    assertNotEquals(0, viewId)
                    assertNotNull(activity.findViewById<android.view.View>(viewId))
                }
                capture("qr-portrait")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
            main.onActivity { assertEquals(Configuration.ORIENTATION_PORTRAIT, it.resources.configuration.orientation) }
        }
    }

    @Test fun notificationMascotHasTransparentCornersAndReadableCutouts() {
        val bitmap = app.getDrawable(R.drawable.ic_vpn_notification)!!.toBitmap(96, 96)
        try {
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, Color.alpha(bitmap.getPixel(95, 95)))
            assertTrue(Color.alpha(bitmap.getPixel(48, 36)) > 240)
            assertEquals(0, Color.alpha(bitmap.getPixel(48, 62)))
        } finally { bitmap.recycle() }
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
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = System.currentTimeMillis() + 12000
        do {
            nodes().firstOrNull(predicate)?.let { return it }
            Thread.sleep(100)
        } while (System.currentTimeMillis() < end)
        error("Missing control; visible: ${nodes().mapNotNull { it.text?.toString() }}")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = await { !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Cannot click $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(400)
    }
    private fun capture(name: String) {
        Thread.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        save(name, bitmap)
        bitmap.recycle()
    }
    private fun save(name: String, bitmap: Bitmap) {
        app.externalCacheDir!!.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
