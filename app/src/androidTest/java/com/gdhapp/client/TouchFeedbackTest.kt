package com.gdhapp.client

import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gdhapp.client.ui.theme.touchFeedback
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchFeedbackTest {
    @Test fun switchAndSystemPreferenceControlStandardTouchFeedback() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = Settings.System.getString(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(android.Manifest.permission.VIBRATE))
            shell("settings put system haptic_feedback_enabled 1")
            val feedback = mutableListOf<Int>()
            lateinit var view: View
            instrumentation.runOnMainSync {
                view = object : View(app) {
                    override fun performHapticFeedback(feedbackConstant: Int): Boolean { feedback += feedbackConstant; return true }
                }
                view.isHapticFeedbackEnabled = true
                touchFeedback(view, false)
                assertTrue(feedback.isEmpty())
                touchFeedback(view, true)
                assertEquals(listOf(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY), feedback)
                view.isHapticFeedbackEnabled = false
                touchFeedback(view, true)
                assertEquals(1, feedback.size)
                view.isHapticFeedbackEnabled = true
            }
            shell("settings put system haptic_feedback_enabled 0")
            instrumentation.runOnMainSync { touchFeedback(view, true); assertEquals(1, feedback.size) }
        } finally {
            shell(if (original == null) "settings delete system haptic_feedback_enabled" else "settings put system haptic_feedback_enabled $original")
        }
    }
}
