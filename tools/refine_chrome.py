from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=root/'app/src/main/java/com/gdhapp/client/ui/main/ConnectionCard.kt';s=p.read_text('utf-8')
s=s.replace('''        Column(Modifier.fillMaxWidth().padding(18.dp)) {''','''        Box {
        com.gdhapp.client.ui.theme.AmbientGlow(state.settings.softBlur, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {''')
s=s.replace('fontSize = 19.sp, lineHeight = 25.sp','fontSize = 16.sp, lineHeight = 21.sp')
s=s.replace('fontSize = 12.sp, lineHeight = 17.sp','fontSize = 11.sp, lineHeight = 15.sp')
s=s.replace('maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))', '''maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(if (connected) "${state.metrics.engine} · ${elapsed(state.metrics.connectedAt)} · ↓ ${formatBytes(state.metrics.downloadBytesPerSecond, true)}"
                        else "Серверов: ${state.allNodes.size} · ${state.settings.coreEngine.label}", fontSize = 9.sp, lineHeight = 12.sp,
                        color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))''')
s=s.replace('Modifier.size(66.dp)','Modifier.size(56.dp)').replace('modifier = Modifier.size(56.dp)','modifier = Modifier.size(48.dp)')
start=s.index('            Row(Modifier.fillMaxWidth().padding(top = 14.dp)');end=s.index('            if (error != null)',start)
s=s[:start]+s[end:]
s=s.replace('\n}\n\nprivate fun connectionErrorSummary','\n    }\n}\n\nprivate fun connectionErrorSummary',1)
p.write_text(s,'utf-8')
p=root/'app/src/main/java/com/gdhapp/client/ui/main/MainScreen.kt';s=p.read_text('utf-8')
s=s.replace('''        topBar = {
            TopAppBar''','''        topBar = { Box {
            com.gdhapp.client.ui.theme.AmbientGlow(state.settings.softBlur, Modifier.matchParentSize())
            TopAppBar''')
s=s.replace('colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background))\n        },', 'colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent))\n        } },')
s=s.replace('''                NavigationBar(containerColor = scheme.surfaceContainerLow, tonalElevation = 0.dp) {''','''                Box {
                com.gdhapp.client.ui.theme.AmbientGlow(state.settings.softBlur, Modifier.matchParentSize())
                NavigationBar(containerColor = scheme.surfaceContainerLow.copy(alpha = .8f), tonalElevation = 0.dp) {''')
s=s.replace('''                }
            }
        },
        snackbarHost''','''                }
                }
            }
        },
        snackbarHost''',1)
p.write_text(s,'utf-8')
p=root/'app/src/main/java/com/gdhapp/client/ui/main/ServerComponents.kt';s=p.read_text('utf-8')
s=s.replace('@Composable\nprivate fun ServerActionsMenu','@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun ServerActionsMenu')
p.write_text(s,'utf-8')
for name in ['app/build.gradle.kts','app/src/main/java/com/gdhapp/client/ui/settings/SettingsScreen.kt','README.md','tools/package-source.ps1']:
    p=root/name;s=p.read_text('utf-8').replace('2.0.2','2.1.0')
    if name.endswith('build.gradle.kts'):s=s.replace('versionCode = 4','versionCode = 5')
    p.write_text(s,'utf-8')
print('Updated fixed chrome and version')
