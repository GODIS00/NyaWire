package com.gdhapp.client

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.ui.main.MainActions
import com.gdhapp.client.ui.main.MainEvent
import com.gdhapp.client.ui.main.MainScreen
import com.gdhapp.client.ui.main.MainViewModel
import com.gdhapp.client.ui.main.rememberAppUpdateCheck
import com.gdhapp.client.ui.theme.NyaWireTheme
import com.gdhapp.client.ui.theme.isDarkAppearance
import com.gdhapp.client.vpn.GdhappVpnService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.gdhapp.client.i18n.AppText.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(application as GdhappApplication))
            MainRoute(mainViewModel)
        }
    }
}

@Composable
private fun MainRoute(viewModel: MainViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var appliedLanguage by remember { mutableStateOf(state.settings.language) }
    LaunchedEffect(state.settings.language) {
        if (appliedLanguage != state.settings.language) {
            appliedLanguage = state.settings.language
            viewModel.checkCoreVersions()
            (context as? Activity)?.recreate()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val pending = remember { mutableStateOf<String?>(null) }
    val promptOpen = remember { mutableStateOf(false) }
    var startupAttempted by rememberSaveable { mutableStateOf(false) }
    fun startSelectedVpn() {
        val config = pending.value ?: viewModel.uiState.value.selectedNode?.rawConfigJson
        pending.value = null; promptOpen.value = false
        if (config != null) GdhappVpnService.start(context, config)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startSelectedVpn() }
    fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startSelectedVpn()
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) requestNotificationAndStart()
        else { promptOpen.value = false; pending.value = null; viewModel.message(AppText.text(R.string.vpn_permission_was_not_granted)) }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(viewModel::importText) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val read = input.read(buffer); if (read == -1) break
                        require(output.size() + read <= SubscriptionParser.MAX_BYTES) { AppText.text(R.string.the_file_exceeds_2_mb) }; output.write(buffer, 0, read) }
                    output.toString("UTF-8")
                } ?: error(AppText.text(R.string.file_unavailable)) }
                viewModel.importText(text)
            } catch (_: Exception) { viewModel.message(AppText.text(R.string.could_not_read_the_file_choose_a)) }
        }
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.foreground(true)
            launch {
                kotlinx.coroutines.yield()
                if (!startupAttempted) {
                    viewModel.awaitStartupRefresh()
                    startupAttempted = true
                    if (viewModel.uiState.value.settings.connectOnOpen && !viewModel.uiState.value.connectionBusy) viewModel.connect()
                }
            }
            var snackbarJob: kotlinx.coroutines.Job? = null
            try { viewModel.events.collect { event -> when (event) {
                is MainEvent.Message -> {
                    snackbarJob?.cancel()
                    snackbarJob = launch { snackbar.showSnackbar(event.text, duration = androidx.compose.material3.SnackbarDuration.Short) }
                }
                is MainEvent.ServerRemoved -> {
                    snackbarJob?.cancel()
                    snackbarJob = launch {
                    if (snackbar.showSnackbar(AppText.text(R.string.server_deleted), AppText.text(R.string.undo), withDismissAction = true,
                        duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed)
                        viewModel.restoreNode(event.removed)
                    }
                }
                is MainEvent.RequestVpnPermission -> if (!promptOpen.value) {
                    promptOpen.value = true; pending.value = event.config
                    val intent = if (viewModel.uiState.value.settings.connectionMode == com.gdhapp.client.domain.model.ConnectionMode.PROXY) null else VpnService.prepare(context)
                    if (intent == null) requestNotificationAndStart() else vpnPermission.launch(intent)
                }
                MainEvent.StopVpn -> GdhappVpnService.stop(context)
            } } } finally { viewModel.foreground(false) }
        }
    }
    NyaWireTheme(state.settings) {
        val checkAppUpdate = rememberAppUpdateCheck()
        val dark = isDarkAppearance(state.settings)
        SideEffect { (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        } }
        MainScreen(state, MainActions(
            connect = viewModel::connect,
            selectNode = viewModel::selectNode,
            toggleGroup = viewModel::toggleExpanded,
            collapseAll = viewModel::collapseAll,
            refresh = { viewModel.refresh(it) },
            togglePin = viewModel::togglePinned,
            deleteGroup = viewModel::remove,
            deleteNode = viewModel::removeNode,
            renameGroup = viewModel::renameGroup,
            importText = viewModel::importText,
            probeGroup = { viewModel.testLatency(groupId = it) },
            probeNode = { viewModel.testLatency(nodeId = it) },
            probeAll = { viewModel.testLatency() },
            cancelStartupRefresh = viewModel::cancelStartupRefresh,
            stopProbes = viewModel::stopProbes,
            favorite = viewModel::toggleFavorite,
            editNode = viewModel::editNode,
            chooseFastest = viewModel::chooseFastest,
            updateSettings = viewModel::updateSettings,
            resetAppearance = viewModel::resetAppearance,
            favoritesOnly = viewModel::setFavoritesOnly,
            protocolFilter = viewModel::setProtocol,
            loadApps = { viewModel.loadApps(context.packageManager) },
            checkCoreVersions = viewModel::checkCoreVersions,
            checkAppUpdate = checkAppUpdate,
            support = { value -> if (SubscriptionParser.safeSupportUrl(value) != null) runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            }.onFailure { viewModel.message(AppText.text(R.string.could_not_open_the_support_link)) } },
            paste = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                viewModel.importText(if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() else "")
            },
            copyText = { text ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NyaWire", text))
                viewModel.message(AppText.text(R.string.copied))
            },
            scanQr = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(AppText.text(R.string.scan_a_link_subscription_or_configuration_qr)).setBeepEnabled(false).setOrientationLocked(false)) },
            importFile = { filePicker.launch(arrayOf("*/*")) },
            openSystemVpnSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.onFailure { viewModel.message(AppText.text(R.string.vpn_settings_are_unavailable)) } },
        ), snackbar, viewModel.metrics)
    }
}
