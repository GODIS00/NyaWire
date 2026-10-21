package com.gdhapp.client.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.main.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsPicker(apps: List<AppEntry>, selected: Set<String>, loading: Boolean, onDismiss: () -> Unit,
    onChange: (Set<String>) -> Unit, onExport: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val context = LocalContext.current
    val icons = remember { object : LruCache<String, ImageBitmap>(2048) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    } }
    val rows = remember(apps, selected, query) {
        (apps + selected.filter { item -> apps.none { it.packageName == item } }.map { AppEntry(it, it) })
            .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
            .filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    LaunchedEffect(feedback) { if (feedback.isNotBlank()) { delay(3000); feedback = "" } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(AppText.text(R.string.apps, selected.size), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(AppText.text(R.string.done)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importing = true }, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(AppText.text(R.string.import_apps))
                }
                OutlinedButton(onClick = { onExport(AppListCodec.export(selected)); feedback = AppText.text(R.string.apps_exported, selected.size) },
                    enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FileUpload, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(AppText.text(R.string.export_apps))
                }
            }
            Text(feedback, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.height(18.dp))
            OutlinedTextField(query, { query = it }, placeholder = { Text(AppText.text(R.string.search_apps), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) { items(rows, key = { it.packageName }) { entry ->
                val checked = entry.packageName in selected
                Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) {
                    onChange(if (checked) selected - entry.packageName else selected + entry.packageName)
                }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    InstalledAppIcon(entry.packageName, context, icons)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(entry.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked, onCheckedChange = null)
                }
            } }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (importing) AppImportDialog(apps, onDismiss = { importing = false }) { result ->
        onChange(selected + result.packages)
        feedback = AppText.text(R.string.apps_imported, result.packages.count { it !in selected })
        importing = false
    }
}

@Composable
private fun InstalledAppIcon(packageName: String, context: Context, cache: LruCache<String, ImageBitmap>) {
    val size = with(LocalDensity.current) { 36.dp.roundToPx().coerceIn(48, 96) }
    var icon by remember(packageName, size) { mutableStateOf<ImageBitmap?>(cache.get(packageName)) }
    LaunchedEffect(packageName, size) {
        if (icon == null) icon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap() }
                .getOrNull()?.also { cache.put(packageName, it) }
        }
    }
    val frame = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
    icon?.let { Image(it, contentDescription = null, modifier = frame) }
        ?: Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Apps, null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
}

@Composable
private fun AppImportDialog(apps: List<AppEntry>, onDismiss: () -> Unit, onImport: (AppListImport) -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val parsed = remember(input, apps) { AppListCodec.parse(input, apps) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.text(R.string.import_apps_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AppText.text(R.string.import_apps_hint), fontSize = 12.sp)
            OutlinedTextField(input, { input = it.take(AppListCodec.MAX_LENGTH + 1) }, label = { Text(AppText.text(R.string.apps_list)) }, minLines = 3, maxLines = 7,
                isError = parsed.tooLarge, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(AppListCodec.MAX_LENGTH + 1)
                    }) { Icon(Icons.Rounded.ContentPaste, AppText.text(R.string.paste_from_clipboard)) }
                })
            Text(AppText.text(R.string.apps_recognized, parsed.packages.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            if (parsed.tooLarge) Text(AppText.text(R.string.apps_list_too_large), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            if (parsed.unmatched.isNotEmpty()) Text(AppText.text(R.string.apps_not_found, parsed.unmatched.take(3).joinToString(", ")),
                color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }, confirmButton = { TextButton(enabled = parsed.packages.isNotEmpty() && !parsed.tooLarge, onClick = { onImport(parsed) }) { Text(AppText.text(R.string.import_apps)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.cancel)) } })
}
