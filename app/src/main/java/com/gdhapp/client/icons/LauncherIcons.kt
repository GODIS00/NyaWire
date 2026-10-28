package com.gdhapp.client.icons

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.gdhapp.client.R

enum class AppIcon(val alias: String, @StringRes val title: Int) {
    ORIGINAL("Original", R.string.icon_original), SAKURA("Sakura", R.string.icon_sakura),
    MIDNIGHT("Midnight", R.string.icon_midnight), MINT("Mint", R.string.mint),
    OCEAN("Ocean", R.string.ocean), SUNSET("Sunset", R.string.icon_sunset),
    LAVENDER("Lavender", R.string.lavender), PEACH("Peach", R.string.peach),
    FROST("Frost", R.string.icon_frost), AMOLED("Amoled", R.string.icon_amoled);

    fun component(context: Context) = ComponentName(context, "${context.packageName}.launcher.$alias")
}

/** PackageManager is the source of truth: choices survive restarts and app updates. */
class LauncherIcons(private val context: Context) {
    private val pm get() = context.packageManager

    fun selected(): AppIcon = AppIcon.entries.firstOrNull(::isEnabled) ?: AppIcon.ORIGINAL

    private fun isEnabled(icon: AppIcon): Boolean = when (pm.getComponentEnabledSetting(icon.component(context))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.ORIGINAL
        else -> false
    }

    @Synchronized
    fun select(icon: AppIcon) {
        val previous = AppIcon.entries.associateWith { pm.getComponentEnabledSetting(it.component(context)) }
        val restoreFirst = AppIcon.entries.firstOrNull { previous[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            ?: AppIcon.ORIGINAL
        val changes = AppIcon.entries.associateWith {
            if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            apply(changes, icon)
        } catch (error: Exception) {
            runCatching { apply(previous, restoreFirst) }
            throw error
        }
    }

    private fun apply(states: Map<AppIcon, Int>, first: AppIcon) {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(states.map { (icon, state) ->
                PackageManager.ComponentEnabledSetting(icon.component(context), state, PackageManager.DONT_KILL_APP)
            })
        } else {
            // Enable the target before removing the old entry on Android 10-12.
            (listOf(first) + states.keys.filter { it != first }).forEach { icon ->
                pm.setComponentEnabledSetting(icon.component(context), states.getValue(icon), PackageManager.DONT_KILL_APP)
            }
        }
    }
}
