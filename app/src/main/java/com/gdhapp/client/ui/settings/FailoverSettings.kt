package com.gdhapp.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.main.MainActions
import com.gdhapp.client.ui.main.MainUiState

@Composable
internal fun FailoverSettings(state: MainUiState, actions: MainActions) {
    val settings = state.settings
    val nodes = state.allNodes
    val chosen = nodes.firstOrNull { it.id == settings.backupServerId }
    val sameAsPrimary = chosen != null && chosen.id == state.selectedNode?.id
    var picker by remember { mutableStateOf(false) }
    SettingsSection(AppText.text(R.string.backup_server), Icons.Rounded.SwapHoriz) {
        EditSetting(AppText.text(R.string.backup_server_choose), null, chosen?.name ?: AppText.text(R.string.not_set)) { picker = true }
        ToggleSetting(AppText.text(R.string.failover_auto), AppText.text(R.string.failover_hint),
            settings.failoverEnabled && chosen != null && !sameAsPrimary, enabled = chosen != null && !sameAsPrimary) { actions.updateSettings(settings.copy(failoverEnabled = it)) }
        if (nodes.isEmpty()) SettingsHint(AppText.text(R.string.failover_add_servers))
        else if (sameAsPrimary) SettingsHint(AppText.text(R.string.failover_same_server))
    }
    if (picker) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { picker = false }, title = { Text(AppText.text(R.string.backup_server_choose)) }, text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(AppText.text(R.string.name_or_address)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(nodes.filter { it.id != state.selectedNode?.id && (it.name.contains(query, true) || it.host.orEmpty().contains(query, true)) }, key = { it.id }) { node ->
                        Row(Modifier.fillMaxWidth().clickable { actions.updateSettings(settings.copy(backupServerId = node.id)); picker = false }
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(node.id == chosen?.id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = false }) { Text(AppText.text(R.string.close)) } },
            dismissButton = { if (settings.backupServerId != null) TextButton(onClick = {
                actions.updateSettings(settings.copy(backupServerId = null, failoverEnabled = false)); picker = false
            }) { Text(AppText.text(R.string.backup_server_remove)) } })
    }
}
