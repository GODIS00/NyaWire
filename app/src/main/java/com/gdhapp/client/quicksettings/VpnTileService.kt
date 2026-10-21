package com.gdhapp.client.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.MainActivity
import com.gdhapp.client.R
import com.gdhapp.client.domain.model.ConnectionMode
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.ui.main.cleanNodeName
import com.gdhapp.client.vpn.GdhappVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Non-active tile: observe connection changes only while Quick Settings is visible. */
class VpnTileService : TileService() {
    private val app get() = application as GdhappApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(app.vpnSession.state, app.subscriptions.groups, app.settings.settings, app.vpnSession.activeNode) { state, groups, _, active ->
                val selected = active?.takeIf { state == VpnState.Connected || state == VpnState.Connecting }
                    ?: groups.firstNotNullOfOrNull { it.servers.firstOrNull { node -> node.isSelected } }
                val status = when (state) {
                    VpnState.Connecting -> AppText.text(R.string.connecting)
                    VpnState.Connected -> AppText.text(if (app.vpnSession.metrics.value.proxyOnly) R.string.proxy_started else R.string.vpn_connected)
                    is VpnState.Error -> AppText.text(R.string.connection_failed)
                    VpnState.Disconnected -> AppText.text(R.string.vpn_is_off)
                }
                Triple(state, selected?.name?.let(::cleanNodeName), status)
            }.collect { (state, name, status) ->
                qsTile?.apply {
                    label = AppText.text(R.string.quick_tile_label)
                    icon = Icon.createWithResource(this@VpnTileService, R.drawable.ic_vpn_notification)
                    this.state = if (state == VpnState.Connected || state == VpnState.Connecting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    subtitle = when (state) {
                        VpnState.Connected -> name?.take(48) ?: status
                        VpnState.Disconnected -> name?.take(48) ?: AppText.text(R.string.select_a_server_in_the_app)
                        else -> status
                    }
                    contentDescription = "$label · $status${name?.let { " · $it" }.orEmpty()}"
                    if (Build.VERSION.SDK_INT >= 30) stateDescription = status
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        when (app.vpnSession.state.value) {
            VpnState.Connected, VpnState.Connecting -> GdhappVpnService.stop(this)
            else -> {
                val selected = app.subscriptions.groups.value.firstNotNullOfOrNull { it.servers.firstOrNull { node -> node.isSelected } }
                if (selected == null) {
                    Toast.makeText(this, AppText.text(R.string.add_a_subscription_and_select_a_server), Toast.LENGTH_LONG).show()
                    openAndCollapse(Intent(this, MainActivity::class.java))
                } else if (app.settings.settings.value.connectionMode == ConnectionMode.VPN && VpnService.prepare(this) != null) {
                    openAndCollapse(Intent(this, QuickConnectActivity::class.java))
                } else GdhappVpnService.start(this, selected.rawConfigJson)
            }
        }
    }

    // The Intent overload is required only on API 29-33; API 34+ uses PendingIntent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openAndCollapse(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 481,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(intent)
    }
}
