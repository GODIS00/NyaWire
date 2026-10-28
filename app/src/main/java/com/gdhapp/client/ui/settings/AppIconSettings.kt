package com.gdhapp.client.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.icons.AppIcon
import com.gdhapp.client.icons.LauncherIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppIconSettings() {
    val context = LocalContext.current
    val manager = remember(context) { LauncherIcons(context) }
    var current by remember { mutableStateOf(manager.selected()) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, manager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) current = manager.selected() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    SettingsSection(AppText.text(R.string.app_icon), Icons.Rounded.Pets) {
        Surface(onClick = { failed = false; showPicker = true }, color = androidx.compose.ui.graphics.Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LauncherIconPreview(current, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(AppText.text(current.title), fontSize = 13.sp)
                    Text(AppText.text(R.string.app_icon_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, AppText.text(R.string.app_icon))
            }
        }
    }
    if (showPicker) AlertDialog(onDismissRequest = { if (!busy) showPicker = false },
        title = { Text(AppText.text(R.string.app_icon)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { icon ->
                            val chosen = icon == current
                            Surface(onClick = {
                                if (chosen) showPicker = false else scope.launch {
                                    busy = true; failed = false
                                    val success = withContext(Dispatchers.IO) { runCatching { manager.select(icon) }.isSuccess }
                                    current = manager.selected()
                                    busy = false
                                    if (success) showPicker = false else failed = true
                                }
                            }, enabled = !busy, shape = RoundedCornerShape(16.dp),
                                color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                border = if (chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.weight(1f).semantics { selected = chosen; role = Role.RadioButton }) {
                                Column(Modifier.padding(vertical = 10.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box {
                                        LauncherIconPreview(icon, Modifier.size(54.dp))
                                        if (chosen) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp).align(Alignment.BottomEnd))
                                    }
                                    Text(AppText.text(icon.title), fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(AppText.text(R.string.app_icon_failed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showPicker = false }, enabled = !busy) { Text(AppText.text(R.string.close)) } })
}

@Composable
private fun LauncherIconPreview(icon: AppIcon, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = remember(context, icon) {
        context.packageManager.getActivityInfo(icon.component(context), PackageManager.MATCH_DISABLED_COMPONENTS)
            .loadIcon(context.packageManager).toBitmap(168, 168).asImageBitmap()
    }
    Image(bitmap, null, modifier)
}
