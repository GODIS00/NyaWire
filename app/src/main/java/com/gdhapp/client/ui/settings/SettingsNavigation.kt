package com.gdhapp.client.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

@Composable
internal fun SettingsDestination(title: String, subtitle: String?, icon: ImageVector, modifier: Modifier = Modifier, prominent: Boolean = false, onClick: () -> Unit) = com.gdhapp.client.ui.theme.BlockColors {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (prominent) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(8.dp).size(20.dp))
            } else Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = if (prominent) 17.sp else 13.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, fontSize = if (prominent) 12.sp else 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SettingsOverview(titles: List<String>, subtitles: List<String>, icons: List<ImageVector>, modifier: Modifier, onOpen: (Int) -> Unit) {
    Box(modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 5, 4, 6, 1, 2, 3).forEach { index ->
                SettingsDestination(titles[index], subtitles[index], icons[index], Modifier.heightIn(min = 64.dp), prominent = true) { onOpen(index) }
            }
        }
    }
}

@Composable
internal fun CustomColorDialog(initial: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var rgb by rememberSaveable(initial) { mutableLongStateOf(initial and 0xFFFFFF) }
    var hex by rememberSaveable(initial) { mutableStateOf("%06X".format(rgb)) }
    val valid = hex.removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))
    fun update(value: Long) { rgb = value and 0xFFFFFF; hex = "%06X".format(rgb) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.text(R.string.custom_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(52.dp).background(Color(rgb or 0xFF000000), MaterialTheme.shapes.medium))
                OutlinedTextField(hex, { input ->
                    hex = input.take(7)
                    if (input.removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))) rgb = input.removePrefix("#").toLong(16)
                }, label = { Text("HEX · #RRGGBB") }, singleLine = true, isError = !valid)
                listOf("R" to 16, "G" to 8, "B" to 0).forEach { (label, shift) ->
                    val component = ((rgb shr shift) and 255).toFloat()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$label ${component.toInt()}", fontSize = 11.sp, modifier = Modifier.width(44.dp))
                        Slider(component, { value -> update((rgb and (255L shl shift).inv()) or (value.toLong() shl shift)) }, valueRange = 0f..255f,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(rgb or 0xFF000000) }) { Text(AppText.text(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.cancel)) } })
}
