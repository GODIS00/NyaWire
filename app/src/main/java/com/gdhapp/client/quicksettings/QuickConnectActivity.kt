package com.gdhapp.client.quicksettings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.MainActivity
import com.gdhapp.client.R
import com.gdhapp.client.domain.model.ConnectionMode
import com.gdhapp.client.i18n.AppText
import com.gdhapp.client.vpn.GdhappVpnService

/** Only used for Android's first VPN consent; subsequent tile taps start the service directly. */
class QuickConnectActivity : ComponentActivity() {
    private val app get() = application as GdhappApplication
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startSelected() }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) requestNotification() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // ActivityResultRegistry restores a pending system dialog.
        val permission = if (app.settings.settings.value.connectionMode == ConnectionMode.VPN) VpnService.prepare(this) else null
        if (permission != null) vpnPermission.launch(permission) else requestNotification()
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startSelected()
    }

    private fun startSelected() {
        val selected = app.subscriptions.groups.value.firstNotNullOfOrNull { it.servers.firstOrNull { node -> node.isSelected } }
        if (selected != null) GdhappVpnService.start(this, selected.rawConfigJson)
        else {
            Toast.makeText(this, AppText.text(R.string.add_a_subscription_and_select_a_server), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
