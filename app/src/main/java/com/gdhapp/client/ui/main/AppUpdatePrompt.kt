package com.gdhapp.client.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gdhapp.client.BuildConfig
import com.gdhapp.client.R
import com.gdhapp.client.data.AppRelease
import com.gdhapp.client.data.AppReleases
import com.gdhapp.client.i18n.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Checks never block the server list or subscription refresh. */
@Composable
internal fun rememberAppUpdateCheck(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("app-updates", 0) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        if (!AppReleases.validRepository(BuildConfig.GITHUB_REPOSITORY)) {
            if (manual) message = AppText.text(R.string.app_update_not_configured)
            return
        }
        busy = manual
        job = scope.launch {
            try {
                release = AppReleases.latest(BuildConfig.GITHUB_REPOSITORY, BuildConfig.VERSION_NAME,
                    Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
                preferences.edit().putLong("last-check", System.currentTimeMillis()).apply()
                if (manual && release == null) message = AppText.text(R.string.app_update_current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manual) message = AppText.text(R.string.app_update_failed)
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        delay(3000)
        val elapsed = System.currentTimeMillis() - preferences.getLong("last-check", 0)
        if (elapsed !in 0 until 6 * 60 * 60 * 1000L) check(false)
    }
    if (busy) AlertDialog(onDismissRequest = { job?.cancel(); busy = false },
        title = { Text(AppText.text(R.string.app_update_title)) },
        text = { Row { CircularProgressIndicator(); Spacer(Modifier.width(16.dp)); Text(AppText.text(R.string.app_update_checking)) } },
        confirmButton = { TextButton(onClick = { job?.cancel(); busy = false }) { Text(AppText.text(R.string.cancel)) } })
    release?.let { update ->
        AlertDialog(onDismissRequest = { release = null },
            title = { Text("NyaWire ${update.version}") }, text = { Text(AppText.text(R.string.app_update_available)) },
            confirmButton = { TextButton(onClick = {
                release = null
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl))) }
                    .onFailure { message = AppText.text(R.string.app_update_failed) }
            }) { Text(AppText.text(R.string.app_update_download)) } },
            dismissButton = { TextButton(onClick = { release = null }) { Text(AppText.text(R.string.close)) } })
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null },
        title = { Text(AppText.text(R.string.app_update_title)) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { message = null }) { Text(AppText.text(R.string.close)) } }) }
    return { check(true) }
}
