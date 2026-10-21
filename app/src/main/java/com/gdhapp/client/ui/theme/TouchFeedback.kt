package com.gdhapp.client.ui.theme

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** Touch feedback respects both the application switch and Android's system preference. */
fun touchFeedback(view: View, enabled: Boolean) {
    if (!enabled || !view.isHapticFeedbackEnabled) return
    runCatching {
        if (Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val kind = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        if (view.performHapticFeedback(kind)) return
        // Some OEM views reject a feedback type. A predefined touch tick is a light fallback.
        val vibrator = if (Build.VERSION.SDK_INT >= 31) view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else view.context.getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() != true) return
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }
}
