package com.gdhapp.client.ui.main

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.domain.model.ImportedRouting

/** Shows what an imported Happ profile contains before it replaces the client rules. */
@Composable
fun RoutingImportDialog(routing: ImportedRouting, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.text(R.string.happ_routing_import_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(routing.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            RoutingSummary(routing)
            Text(AppText.text(R.string.happ_routing_import_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(AppText.text(R.string.happ_routing_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.cancel)) } })
}

/** Rule counts, the default route and any skipped entries of an imported profile. */
@Composable
fun RoutingSummary(routing: ImportedRouting) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(AppText.text(R.string.happ_routing_counts, routing.directCount, routing.proxyCount, routing.blockCount), fontSize = 13.sp)
        Text(AppText.text(if (routing.globalProxy) R.string.happ_routing_default_proxy else R.string.happ_routing_default_direct),
            fontSize = 12.sp, color = scheme.onSurfaceVariant)
        if (routing.skipped.isNotEmpty()) Text(AppText.text(R.string.happ_routing_skipped, routing.skipped.size,
            routing.skipped.take(4).joinToString() + if (routing.skipped.size > 4) "…" else ""),
            fontSize = 12.sp, lineHeight = 15.sp, color = scheme.error)
    }
}
