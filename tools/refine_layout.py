from pathlib import Path
root=Path(__file__).resolve().parents[1]/'app/src/main/java/com/gdhapp/client/ui/main'
p=root/'ServerComponents.kt';s=p.read_text('utf-8')
start=s.index('@OptIn(ExperimentalFoundationApi::class)');end=s.index('@Composable\nprivate fun ServerActionsMenu',start)
s=s[:start]+'''@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerCard(node: ServerNode, settings: ClientSettings, testing: Boolean, onSelect: () -> Unit,
    onInfo: () -> Unit, onFavorite: () -> Unit, modifier: Modifier = Modifier, grid: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (node.isSelected) scheme.primaryContainer.copy(alpha = .45f) else scheme.surfaceContainerLow,
        animationSpec = if (LocalMotionEnabled.current) tween(180) else snap(), label = "nodeColor")
    val compact = settings.layout == ServerLayout.COMPACT
    Surface(color = background, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (node.isSelected) scheme.primary.copy(alpha = .5f) else scheme.outlineVariant.copy(alpha = .35f)),
        modifier = modifier.semantics { selected = node.isSelected }.combinedClickable(role = Role.RadioButton, onClick = onSelect, onLongClick = onInfo)) {
        Box {
            if (node.isSelected) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(22.dp).background(scheme.primary, CircleShape))
            if (grid) Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (settings.showFlags) CountryBadge(node.flagResOrCode, Modifier.size(25.dp), small = true)
                    if (node.isFavorite) Icon(Icons.Rounded.Star, "Избранное", tint = scheme.primary, modifier = Modifier.padding(start = 6.dp).size(12.dp))
                    Spacer(Modifier.weight(1f))
                    ServerActionsMenu(node, onInfo, onFavorite)
                }
                Text(cleanNodeName(node.name), fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (settings.showProtocol) node.protocolSummary.substringBefore(" /") else settings.probeMethod.label,
                        fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                    LatencyChip(node.probeResults[settings.probeMethod], testing, compact = true, flat = true, modifier = Modifier.widthIn(max = 84.dp))
                }
            } else Row(Modifier.padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (settings.showFlags) { CountryBadge(node.flagResOrCode, Modifier.size(28.dp), small = true); Spacer(Modifier.width(9.dp)) }
                Column(Modifier.weight(1f).padding(end = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cleanNodeName(node.name), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (node.isFavorite) Icon(Icons.Rounded.Star, "Избранное", tint = scheme.primary, modifier = Modifier.size(11.dp))
                    }
                    if (settings.showProtocol) Text(node.protocolSummary.substringBefore('|').trim().replace(" / ", " · "),
                        fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    if (settings.showAddress) Text("${node.host ?: "Адрес не найден"}:${node.port ?: "?"}", fontSize = 9.sp, lineHeight = 12.sp,
                        color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 84.dp)) {
                    LatencyChip(node.probeResults[settings.probeMethod], testing, compact = true, flat = true)
                    Text(settings.probeMethod.label, fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                ServerActionsMenu(node, onInfo, onFavorite)
            }
        }
    }
}

''' + s[end:]
s=s.replace('import androidx.compose.runtime.Composable','import androidx.compose.runtime.CompositionLocalProvider\nimport androidx.compose.runtime.Composable')
s=s.replace('    Box {\n        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp))',
'''    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) { Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp))''',1)
s=s.replace('\n}\n\n@Composable\nfun LatencyChip','\n} }\n\n@Composable\nfun LatencyChip',1)
s=s.replace('fontWeight = FontWeight.SemiBold, maxLines = 1)', 'fontWeight = FontWeight.SemiBold, lineHeight = if (compact) 12.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)')
start=s.index('fun formatBytes(')
s=s[:start]+'''fun formatBytes(value: Long, perSecond: Boolean = false): String {
    val suffix = if (perSecond) "/с" else ""
    val unit: String
    val divisor: Double
    when {
        value < 1024 -> { unit = "Б"; divisor = 1.0 }
        value < 1024 * 1024 -> { unit = "КБ"; divisor = 1024.0 }
        value < 1024L * 1024 * 1024 -> { unit = "МБ"; divisor = 1048576.0 }
        else -> { unit = "ГБ"; divisor = 1073741824.0 }
    }
    val amount = String.format(com.gdhapp.client.i18n.AppText.locale, if (divisor < 1048576) "%.0f" else "%.1f", value / divisor)
    return "$amount $unit$suffix"
}
'''
p.write_text(s,'utf-8')

p=root/'MainScreen.kt';s=p.read_text('utf-8')
start=s.index('                        item(key = "filters")');end=s.index('                        if (state.isTesting)',start)
s=s[:start]+'''                        item(key = "filters") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = !state.favoritesOnly && state.protocolFilter == null,
                                        onClick = { actions.favoritesOnly(false); actions.protocolFilter(null) }, label = { Text("Все · ${state.allNodes.size}", fontSize = 11.sp) })
                                    FilterChip(selected = state.favoritesOnly, onClick = { actions.favoritesOnly(!state.favoritesOnly) },
                                        label = { Text("Избранное", fontSize = 11.sp) }, leadingIcon = { Icon(Icons.Rounded.StarBorder, null, modifier = Modifier.size(14.dp)) })
                                    state.allNodes.map { it.protocolSummary.substringBefore(" /") }.distinct().forEach { protocol ->
                                        FilterChip(selected = state.protocolFilter == protocol, onClick = { actions.protocolFilter(if (state.protocolFilter == protocol) null else protocol) }, label = { Text(protocol, fontSize = 10.sp) })
                                    }
                                }
                                SortMenu(state, actions)
                            }
                        }
''' + s[end:]
s=s.replace('verticalArrangement = Arrangement.spacedBy(10.dp)) {\n                    item(key = "connection")','verticalArrangement = Arrangement.spacedBy(6.dp)) {\n                    item(key = "connection")')
s=s.replace('horizontalArrangement = Arrangement.spacedBy(10.dp)) {\n            pair.forEach','horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n            pair.forEach')
s=s.replace('    var info by remember { mutableStateOf(false) }\n    val scheme', '    var info by remember { mutableStateOf(false) }\n    var announcementExpanded by rememberSaveable(group.id) { mutableStateOf(false) }\n    val scheme')
s=s.replace('Text("${group.servers.size} серверов · ${group.lastUpdated}"','Text("Серверов: ${group.servers.size} · ${group.lastUpdated}"')
s=s.replace('fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)', 'fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp)')
s=s.replace('maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp)',
'''maxLines = if (announcementExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { announcementExpanded = !announcementExpanded }.padding(horizontal = 8.dp, vertical = 6.dp)''')
s=s.replace('import androidx.compose.foundation.BorderStroke', 'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.BorderStroke')
s=s.replace('Таймаут ${state.settings.probeTimeoutSeconds} с · ${state.settings.probeSamples} повтор(а) · ${state.settings.probeConcurrency} одновременно',
    'Таймаут: ${state.settings.probeTimeoutSeconds} с · Повторы: ${state.settings.probeSamples} · Одновременно: ${state.settings.probeConcurrency}')
p.write_text(s,'utf-8')
print('Refined compact cards, filters and announcements')
