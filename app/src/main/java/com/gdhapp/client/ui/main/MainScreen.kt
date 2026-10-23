package com.gdhapp.client.ui.main

import com.gdhapp.client.i18n.AppText

import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Build
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import com.gdhapp.client.domain.model.VpnMetrics
import androidx.compose.ui.platform.LocalView
import com.gdhapp.client.ui.theme.touchFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.gdhapp.client.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhapp.client.data.SubscriptionParser
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.ServerLayout
import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.ServerSort
import com.gdhapp.client.domain.model.SubscriptionGroup
import com.gdhapp.client.domain.model.VpnState
import com.gdhapp.client.ui.settings.SettingsScreen
import com.gdhapp.client.ui.theme.NyaWireTheme
import com.gdhapp.client.ui.theme.IosMotion
import com.gdhapp.client.ui.theme.LocalMotionEnabled

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(state: MainUiState, actions: MainActions, snackbarHostState: SnackbarHostState, metrics: kotlinx.coroutines.flow.StateFlow<VpnMetrics>? = null) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showInput by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var infoNode by rememberSaveable { mutableStateOf<String?>(null) }
    var editNode by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var renameGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val pingListState = rememberLazyListState()
    val pageState = rememberSaveableStateHolder()
    val connectionCardScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val feedbackView = LocalView.current
    val playConnectionSound = com.gdhapp.client.ui.theme.rememberVpnButtonSound(state.settings.connectionSound)
    val scheme = MaterialTheme.colorScheme
    val listModel = state
    val shownNodes = remember(listModel) { listModel.groups.associate { it.id to listModel.visibleNodes(it) } }
    val backdrop = rememberGraphicsLayer().apply { clip = true }
    val showMini = state.connectionBusy && tab != 2 && (tab == 1 || connectionCardScrolled)
    // Keep the glass stable during flings without recording another viewport pass.
    val scrolling by remember { derivedStateOf { if (tab == 1) pingListState.isScrollInProgress else listState.isScrollInProgress } }
    val blurPanel = !state.startupRefreshing && state.settings.softBlur && Build.VERSION.SDK_INT >= 31
    val captureBackdrop = !scrolling && blurPanel

    val navIcons = listOf(Icons.Rounded.Dns, Icons.Rounded.NetworkCheck, Icons.Rounded.Tune)
    val navLabels = listOf(AppText.text(R.string.servers_091), AppText.text(R.string.checks), AppText.text(R.string.settings))
    BackHandler(enabled = tab != 0) { tab = 0 }
    fun select(id: String) { touchFeedback(feedbackView, state.settings.haptics); actions.selectNode(id) }
    fun connect() {
        touchFeedback(feedbackView, state.settings.haptics)
        if (!state.connectionBusy && state.selectedNode != null) playConnectionSound()
        actions.connect()
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = { Box {
            com.gdhapp.client.ui.theme.AmbientGlow(state.settings.softBlur, Modifier.matchParentSize())
            TopAppBar(title = { Column {
                Crossfade(if (tab == 0) "NyaWire" else navLabels[tab], animationSpec = if (state.settings.animations) IosMotion.fade() else snap(), label = "screenTitle") { title ->
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            } }, actions = {
                // Fade and scale only: the bar never reflows while the buttons come and go.
                AnimatedVisibility(tab != 2, enter = if (state.settings.animations) fadeIn(IosMotion.fade()) + scaleIn(IosMotion.fade(), initialScale = .85f) else EnterTransition.None,
                    exit = if (state.settings.animations) fadeOut(IosMotion.fade()) + scaleOut(IosMotion.fade(), targetScale = .85f) else ExitTransition.None) { Row {
                    IconButton(onClick = { showAdd = true }, enabled = !state.importing) {
                        Crossfade(state.importing, animationSpec = if (state.settings.animations) tween(180) else snap(), label = "importing") { importing ->
                            if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Add, AppText.text(R.string.add_subscription))
                        }
                    }
                    IconButton(onClick = { tab = 2 }) { Icon(Icons.Rounded.Tune, AppText.text(R.string.client_settings)) }
                } }
                Spacer(Modifier.width(8.dp))
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
        } },
        bottomBar = {
            Column {
                Box {
                FrostedNavigationBackground(backdrop, blurPanel, Modifier.matchParentSize(), refresh = captureBackdrop)
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp) {
                    navLabels.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(navIcons[index], title) }, label = { Text(title, fontSize = 11.sp) }) }
                }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxSize().drawWithContent {
                if (captureBackdrop) {
                    // Retain drawing commands once. The panel clips its blur pass to its own bounds.
                    backdrop.record {
                        // An opaque retained background prevents sharp content showing through
                        // transparent pixels of the blurred copy under the navigation strip.
                        drawRect(scheme.background)
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdrop)
                } else drawContent()
            }) {
            com.gdhapp.client.ui.theme.DriftingBackground(state.settings.backgroundPictures, state.settings.animations && !scrolling && !state.startupRefreshing,
                com.gdhapp.client.ui.theme.isDarkAppearance(state.settings), Modifier.matchParentSize(), state.settings.backgroundDensity, state.settings.backgroundOpacity)
            AnimatedContent(tab, Modifier.fillMaxSize().clipToBounds(), transitionSpec = {
                if (state.settings.animations) IosMotion.page(forward = targetState > initialState) else IosMotion.none
            }, label = "tabContent") { tab -> when (tab) {
                2 -> pageState.SaveableStateProvider("settings") {
                    SettingsScreen(state, actions, Modifier.widthIn(max = 820.dp).fillMaxSize().padding(bottom = insets.calculateBottomPadding()), onPreviewSound = playConnectionSound)
                }
                1 -> ChecksScreen(state, actions, onInfo = { infoNode = it }, listState = pingListState, modifier = Modifier.widthIn(max = 820.dp).fillMaxSize().padding(bottom = insets.calculateBottomPadding() + if (state.connectionBusy) 84.dp else 0.dp))
                else -> LazyColumn(state = listState, modifier = Modifier.widthIn(max = 820.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = insets.calculateBottomPadding() + if (state.connectionBusy) 104.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "connection") { val currentMetrics = metrics?.let { it.collectAsStateWithLifecycle().value } ?: state.metrics
                        Box(itemMotion(state.settings.animations)) { ConnectionCard(state.copy(metrics = currentMetrics), ::connect, { showAdd = true }, { showError = true }) } }
                    if (listModel.groups.isEmpty()) item {
                        EmptyView(Icons.Rounded.TravelExplore, AppText.text(R.string.no_servers_yet),
                            AppText.text(R.string.add_a_subscription_url_paste_a_configuration), AppText.text(R.string.add_subscription), { showAdd = true })
                    } else {
                        item(key = "filters") {
                            Row(Modifier.fillMaxWidth().then(itemMotion(state.settings.animations)), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = !listModel.favoritesOnly && listModel.protocolFilter == null,
                                        onClick = { actions.favoritesOnly(false); actions.protocolFilter(null) }, label = { Text(AppText.text(R.string.all, listModel.allNodes.size), fontSize = 11.sp) })
                                    FilterChip(selected = listModel.favoritesOnly, onClick = { actions.favoritesOnly(!listModel.favoritesOnly) },
                                        label = { Text(AppText.text(R.string.favorites), fontSize = 11.sp) }, leadingIcon = { Icon(Icons.Rounded.StarBorder, null, modifier = Modifier.size(14.dp)) })
                                    listModel.allNodes.map { it.protocolSummary.substringBefore(" /") }.distinct().forEach { protocol ->
                                        FilterChip(selected = listModel.protocolFilter == protocol, onClick = { actions.protocolFilter(if (listModel.protocolFilter == protocol) null else protocol) }, label = { Text(protocol, fontSize = 10.sp) })
                                    }
                                }
                                SortMenu(listModel, actions)
                            }
                        }
                        item(key = "ping-all") { Box(itemMotion(state.settings.animations)) { PingAllControl(listModel, actions) } }
                        item(key = "section-title") {
                            Row(Modifier.fillMaxWidth().then(itemMotion(state.settings.animations)).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(AppText.text(R.string.subscriptions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = actions.collapseAll) { Text(AppText.text(R.string.collapse), fontSize = 11.sp) }
                            }
                        }
                        val visibleGroups = listModel.groups.filter { shownNodes.getValue(it.id).isNotEmpty() || it.servers.isEmpty() }
                        if (visibleGroups.isEmpty()) item { EmptyView(Icons.Rounded.SearchOff, AppText.text(R.string.no_results), AppText.text(R.string.try_a_different_search_or_filter), AppText.text(R.string.reset_filters), {
                            actions.favoritesOnly(false); actions.protocolFilter(null)
                        }) }
                        visibleGroups.forEach { group ->
                            item(key = "group:${group.id}") { Box(itemMotion(state.settings.animations)) { SubscriptionHeader(group, listModel, actions, onDelete = { deleteGroup = group.id }, onRename = { renameGroup = group.id }) } }
                            if (group.isExpanded) serverRows(shownNodes.getValue(group.id), listModel, actions,
                                onSelect = { select(it) }, onInfo = { infoNode = it }, keyPrefix = group.id)
                            item(key = "space:${group.id}") { Spacer(Modifier.height(6.dp).then(itemMotion(state.settings.animations))) }
                        }
                    }
                }
            } }
            }
            AnimatedVisibility(visible = showMini, modifier = Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = insets.calculateBottomPadding() + 8.dp),
                enter = if (state.settings.animations) fadeIn() + slideInVertically { it / 3 } else EnterTransition.None,
                exit = if (state.settings.animations) fadeOut() + slideOutVertically { it / 3 } else ExitTransition.None) {
                val currentMetrics = metrics?.let { it.collectAsStateWithLifecycle().value } ?: state.metrics
                FrostedConnectionBar(state.copy(metrics = currentMetrics), ::connect, backdrop, blurPanel, insets.calculateBottomPadding(), refresh = captureBackdrop)
            }
        }
    }

    if (state.startupRefreshing) SubscriptionRefreshDialog(state, actions.cancelStartupRefresh)
    if (showAdd) ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Text(AppText.text(R.string.add_servers), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(AppText.text(R.string.subscription_or_individual_configuration), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        AddAction(Icons.Rounded.ContentPaste, AppText.text(R.string.paste_from_clipboard), AppText.text(R.string.link_json_or_server_list)) { showAdd = false; actions.paste() }
        AddAction(Icons.Rounded.Link, AppText.text(R.string.add_a_link_or_configuration), "VLESS, VMess, Trojan, SS, Hysteria, TUIC") { showAdd = false; showInput = true }
        AddAction(Icons.Rounded.QrCodeScanner, AppText.text(R.string.scan_qr_code), AppText.text(R.string.opens_the_camera)) { showAdd = false; actions.scanQr() }
        AddAction(Icons.Rounded.Description, AppText.text(R.string.import_file), AppText.text(R.string.json_or_text_subscription)) { showAdd = false; actions.importFile() }
        Spacer(Modifier.height(22.dp))
    }
    if (showInput) AlertDialog(onDismissRequest = { showInput = false }, title = { Text(AppText.text(R.string.add_connection)) },
        text = { OutlinedTextField(input, { input = it }, label = { Text(AppText.text(R.string.link_or_configuration)) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { actions.importText(input); input = ""; showInput = false }, enabled = input.isNotBlank()) { Text(AppText.text(R.string.add)) } },
        dismissButton = { TextButton(onClick = { showInput = false }) { Text(AppText.text(R.string.cancel)) } })

    infoNode?.let { id -> state.allNodes.firstOrNull { it.id == id }?.let { node ->
        ServerDetails(node, state, actions, onDismiss = { infoNode = null }, onSelect = { select(id); infoNode = null; tab = 0 },
            onEdit = { infoNode = null; editNode = id })
    } }
    editNode?.let { id -> state.allNodes.firstOrNull { it.id == id }?.let { node ->
        EditNodeDialog(node, onDismiss = { editNode = null }, onSave = { name, config -> actions.editNode(id, name, config); editNode = null })
    } }
    deleteGroup?.let { id -> AlertDialog(onDismissRequest = { deleteGroup = null }, title = { Text(AppText.text(R.string.delete_subscription)) },
        text = { Text(AppText.text(R.string.and_its_servers_will_be_removed_from, state.groups.firstOrNull { it.id == id }?.title.orEmpty())) },
        confirmButton = { TextButton(onClick = { actions.deleteGroup(id); deleteGroup = null }) { Text(AppText.text(R.string.delete), color = scheme.error) } },
        dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text(AppText.text(R.string.cancel)) } }) }
    renameGroup?.let { id ->
        var title by remember(id) { mutableStateOf(state.groups.firstOrNull { it.id == id }?.title.orEmpty()) }
        AlertDialog(onDismissRequest = { renameGroup = null }, title = { Text(AppText.text(R.string.subscription_name)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { actions.renameGroup(id, title); renameGroup = null }) { Text(AppText.text(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renameGroup = null }) { Text(AppText.text(R.string.cancel)) } })
    }
    if (showError) AlertDialog(onDismissRequest = { showError = false }, title = { Text(AppText.text(R.string.connection_diagnostics)) },
        text = { SelectionContainer { Text((state.vpnState as? VpnState.Error)?.message ?: AppText.text(R.string.no_errors)) } },
        confirmButton = { TextButton(onClick = { showError = false; tab = 2 }) { Text(AppText.text(R.string.vpn_settings)) } },
        dismissButton = { TextButton(onClick = { showError = false }) { Text(AppText.text(R.string.close)) } })
}

/** Placement, appearance and removal animations for keyed list rows; the resting layout is unchanged. */
private fun LazyItemScope.itemMotion(enabled: Boolean): Modifier = if (enabled) Modifier.animateItem() else Modifier

private fun LazyListScope.serverRows(nodes: List<ServerNode>, state: MainUiState, actions: MainActions, onSelect: (String) -> Unit,
    onInfo: (String) -> Unit, keyPrefix: String) {
    if (state.settings.layout == ServerLayout.GRID) items(nodes.chunked(2), contentType = { "server-grid" }, key = { "${keyPrefix}:${it.first().id}" }) { pair ->
        Row(Modifier.fillMaxWidth().then(itemMotion(state.settings.animations)), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { node -> ServerCard(node, state.settings, node.id in state.testingNodes,
                { onSelect(node.id) }, { onInfo(node.id) }, { actions.favorite(node.id) }, Modifier.weight(1f), grid = true,
                onCopy = { actions.copyText(node.rawConfigJson) }, onDelete = { actions.deleteNode(node.id) }, onPing = { actions.probeNode(node.id) }) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    } else items(nodes, contentType = { "server" }, key = { "${keyPrefix}:${it.id}" }) { node ->
        ServerCard(node, state.settings, node.id in state.testingNodes, { onSelect(node.id) }, { onInfo(node.id) }, { actions.favorite(node.id) }, Modifier.fillMaxWidth().then(itemMotion(state.settings.animations)),
            onCopy = { actions.copyText(node.rawConfigJson) }, onDelete = { actions.deleteNode(node.id) }, onPing = { actions.probeNode(node.id) })
    }
}

@Composable
private fun SortMenu(state: MainUiState, actions: MainActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, AppText.text(R.string.sort_servers)) }
        DropdownMenu(expanded, { expanded = false }) { ServerSort.entries.forEach { sort ->
            DropdownMenuItem(text = { Text(sort.label) }, leadingIcon = { if (sort == state.settings.sort) Icon(Icons.Rounded.Check, null) },
                onClick = { actions.updateSettings(state.settings.copy(sort = sort)); expanded = false })
        } }
    }
}

@Composable
private fun SubscriptionHeader(group: SubscriptionGroup, state: MainUiState, actions: MainActions, onDelete: () -> Unit, onRename: () -> Unit) = com.gdhapp.client.ui.theme.BlockColors {
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var announcementExpanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val motion = state.settings.animations
    // ChevronRight turned a quarter is the ExpandMore glyph, so both resting states look as before.
    val chevron by animateFloatAsState(if (group.isExpanded) 90f else 0f, if (motion) IosMotion.value() else snap(), label = "groupChevron")
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerLow) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.toggleGroup(group.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ChevronRight, if (group.isExpanded) AppText.text(R.string.collapse_subscription) else AppText.text(R.string.expand_subscription), tint = scheme.primary,
                        modifier = Modifier.rotate(chevron))
                }
                Column(Modifier.weight(1f).padding(start = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(group.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (group.isPinned) Icon(Icons.Rounded.PushPin, AppText.text(R.string.pinned), tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    }
                    Text(AppText.text(R.string.servers, group.servers.size, group.lastUpdated), color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
                IconButton(onClick = { actions.refresh(group.id) }, enabled = group.id !in state.refreshing && group.sourceUrl != null, modifier = Modifier.size(36.dp)) {
                    Crossfade(group.id in state.refreshing, animationSpec = if (motion) tween(180) else snap(), label = "groupRefresh") { refreshing ->
                        if (refreshing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, AppText.text(R.string.update_subscription), modifier = Modifier.size(20.dp))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreHoriz, AppText.text(R.string.subscription_menu)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(AppText.text(R.string.check_servers)) }, leadingIcon = { Icon(Icons.Rounded.NetworkCheck, null) }, onClick = { menu = false; actions.probeGroup(group.id) })
                        DropdownMenuItem(text = { Text(AppText.text(R.string.information)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { menu = false; info = true })
                        DropdownMenuItem(text = { Text(AppText.text(R.string.rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(if (group.isPinned) AppText.text(R.string.unpin) else AppText.text(R.string.pin)) }, onClick = { menu = false; actions.togglePin(group.id) })
                        group.supportUrl?.let { url -> DropdownMenuItem(text = { Text(AppText.text(R.string.support)) }, onClick = { menu = false; actions.support(url) }) }
                        DropdownMenuItem(text = { Text(AppText.text(R.string.delete), color = scheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            AnimatedVisibility(group.isExpanded, enter = if (motion) fadeIn(IosMotion.fade()) + expandVertically(IosMotion.size) else EnterTransition.None,
                exit = if (motion) fadeOut(IosMotion.fade()) + shrinkVertically(IosMotion.size) else ExitTransition.None) { Column {
                if (group.usedTrafficText.replace(" ", "") != "0B" || group.expiryDateText != "—") Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 9.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${group.usedTrafficText} / ${group.totalTrafficText}", color = scheme.primary, fontSize = 11.sp)
                    if (group.expiryDateText != "—") Text(AppText.text(R.string.until, group.expiryDateText), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
                group.trafficFraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).height(3.dp), trackColor = scheme.surfaceContainerHighest) }
                if (group.announcementText.isNotBlank()) Text(group.announcementText, fontSize = 11.sp, lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant, maxLines = if (announcementExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { announcementExpanded = !announcementExpanded }
                        .then(if (motion) Modifier.animateContentSize() else Modifier).padding(horizontal = 8.dp, vertical = 6.dp))
            } }
        }
    }
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text(group.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(AppText.text(R.string.servers_updated_interval_h_traffic_expires, group.servers.size, group.lastUpdated, group.autoUpdateHours, group.usedTrafficText, group.totalTrafficText, group.expiryDateText))
        if (group.announcementText.isNotBlank()) Text(group.announcementText, modifier = Modifier.padding(top = 16.dp))
    } }, confirmButton = { TextButton(onClick = { info = false }) { Text(AppText.text(R.string.close)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChecksScreen(state: MainUiState, actions: MainActions, onInfo: (String) -> Unit, listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val results = state.allNodes.mapNotNull { it.probeResults[state.settings.probeMethod] }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ProbeMethod.entries.forEach { method ->
            FilterChip(selected = state.settings.probeMethod == method, onClick = { actions.updateSettings(state.settings.copy(probeMethod = method)) }, label = { Text(method.label, fontSize = 12.sp) })
        } } }
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(AppText.text(R.string.timeout_s_samples_concurrent, state.settings.probeTimeoutSeconds, state.settings.probeSamples, state.settings.probeConcurrency),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PingAllControl(state, actions)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CheckCounter(AppText.text(R.string.total), state.allNodes.size)
                        CheckCounter(AppText.text(R.string.successful), results.count { it.isSuccess })
                        CheckCounter(AppText.text(R.string.failed), results.count { !it.isSuccess })
                    }
                    OutlinedButton(onClick = actions.chooseFastest, modifier = Modifier.fillMaxWidth(), enabled = results.any { it.isSuccess }) {
                        Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(AppText.text(R.string.select_fastest))
                    }
                }
            }
        }
        items(state.allNodes, key = { it.id }) { node -> ServerCard(node, state.settings.copy(layout = ServerLayout.COMPACT), node.id in state.testingNodes,
            { onInfo(node.id) }, { onInfo(node.id) }, { actions.favorite(node.id) }, Modifier.fillMaxWidth().then(itemMotion(state.settings.animations)),
            onCopy = { actions.copyText(node.rawConfigJson) }, onDelete = { actions.deleteNode(node.id) }, onPing = { actions.probeNode(node.id) }) }
        if (state.allNodes.isEmpty()) item { Text(AppText.text(R.string.add_a_subscription_on_the_servers_tab), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
    }
}

@Composable
private fun CheckCounter(label: String, value: Int) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
    val motion = LocalMotionEnabled.current
    AnimatedContent(value, transitionSpec = {
        if (!motion) EnterTransition.None togetherWith ExitTransition.None
        else if (targetState > initialState) (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
        else (slideInVertically { -it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut())
    }, label = "checkCounter") { count ->
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
} }

@Composable
private fun PingAllControl(state: MainUiState, actions: MainActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = if (state.isTesting) actions.stopProbes else actions.probeAll,
            enabled = state.isTesting || state.allNodes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Icon(if (state.isTesting) Icons.Rounded.Stop else Icons.Rounded.NetworkCheck, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(AppText.text(if (state.isTesting) R.string.stop_ping else R.string.ping_all_servers), modifier = Modifier.weight(1f), fontSize = 12.sp)
            Text(if (state.isTesting) "${state.probeCompleted}/${state.probeTotal}" else state.allNodes.size.toString(), fontSize = 11.sp)
        }
        val motion = state.settings.animations
        val progress by animateFloatAsState(if (state.probeTotal == 0) 0f else state.probeCompleted.toFloat() / state.probeTotal,
            if (motion) tween(300) else snap(), label = "pingProgress")
        val barAlpha by animateFloatAsState(if (state.isTesting) 1f else 0f, if (motion) tween(220) else snap(), label = "pingBar")
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (barAlpha > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize().alpha(barAlpha))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDetails(node: ServerNode, state: MainUiState, actions: MainActions, onDismiss: () -> Unit, onSelect: () -> Unit, onEdit: () -> Unit) {
    val bottomSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = bottomSheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountryBadge(node.flagResOrCode)
                Text(cleanNodeName(node.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                IconButton(onClick = { actions.favorite(node.id) }) { Icon(if (node.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, AppText.text(R.string.favorites)) }
            }
            SelectionContainer { Text("${node.host ?: AppText.text(R.string.address_not_found_in_configuration)}:${node.port ?: "?"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            Text(node.protocolSummary.substringBefore('|').replace(" / ", " · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            node.serverName?.let { Text("SNI: ${it}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(AppText.text(R.string.connection_checks_159), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ProbeMethod.entries.forEach { method ->
                val result = node.probeResults[method]
                Row(verticalAlignment = Alignment.Top) {
                    Text(method.label, fontSize = 12.sp, modifier = Modifier.width(88.dp).padding(top = 6.dp))
                    Column(Modifier.weight(1f)) {
                        LatencyChip(result, node.id in state.testingNodes && state.settings.probeMethod == method)
                        result?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
                    }
                }
            }
            Button(onClick = { actions.probeNode(node.id) }, enabled = !state.isTesting, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.NetworkCheck, null); Spacer(Modifier.width(8.dp)); Text(AppText.text(R.string.check, state.settings.probeMethod.label))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f)) { Text(if (node.isSelected) AppText.text(R.string.profile_selected) else AppText.text(R.string.select)) }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(AppText.text(R.string.edit)) }
                IconButton(onClick = { actions.copyText(node.rawConfigJson) }) { Icon(Icons.Rounded.ContentCopy, AppText.text(R.string.copy_configuration)) }
            }
        }
    }
}

@Composable
private fun EditNodeDialog(node: ServerNode, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(node.id) { mutableStateOf(node.name) }
    var config by remember(node.id) { mutableStateOf(node.rawConfigJson) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.text(R.string.edit_server)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(AppText.text(R.string.name_166)) }, singleLine = true)
            OutlinedTextField(config, { config = it; error = null }, label = { Text(AppText.text(R.string.json_or_link)) }, minLines = 6, maxLines = 12, textStyle = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        runCatching { require(SubscriptionParser().parse(config).servers.size == 1) { AppText.text(R.string.a_single_configuration_is_required) } }
            .onSuccess { onSave(name, config) }.onFailure { error = it.message }
    }) { Text(AppText.text(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.cancel)) } })
}

@Composable
private fun EmptyView(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
        }
        Text(title, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 18.dp))
        Text(subtitle, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 9.dp, bottom = 18.dp))
        Button(onClick = onClick) { Text(button, fontSize = 13.sp) }
    }
}

@Composable
private fun AddAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 18.dp)) { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Preview(widthDp = 393, heightDp = 852)
@Composable
private fun EmptyMainPreview() { NyaWireTheme { MainScreen(MainUiState(), MainActions(), remember { SnackbarHostState() }) } }
