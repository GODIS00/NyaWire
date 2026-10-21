package com.gdhapp.client.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

@Composable
fun SubscriptionRefreshDialog(state: MainUiState, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(.32f)
            window?.setWindowAnimations(0)
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f)) {
            Column(Modifier.widthIn(max = 340.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(AppText.text(R.string.subscription_refreshing), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val title = state.groups.firstOrNull { it.id in state.refreshing }?.title
                        if (title != null) Text(title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(AppText.text(R.string.refresh_count, state.refreshCompleted + 1, state.refreshTotal.coerceAtLeast(1)), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.refreshTotal <= 1) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                else LinearProgressIndicator(progress = { state.refreshCompleted.toFloat() / state.refreshTotal }, modifier = Modifier.fillMaxWidth().height(3.dp))
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(AppText.text(R.string.cancel), fontSize = 12.sp) }
            }
        }
    }
}
