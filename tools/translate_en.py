"""Reviewed English catalogue. Placeholder positions must match the source."""
from pathlib import Path
import json,re,collections
p=Path(__file__).with_name('translations.json')
entries=json.loads(p.read_text('utf-8'))
translations=r'''001|This site does not support HEAD. Try GET.
002|Try a different test URL.
003|Clipboard is empty
004|Limit reached: 2,000 servers
005|This subscription has already been added
006|This is a local profile. Add a subscription URL to enable updates.
007|Server not found
008|Select a single configuration to edit
009|Enter a server name
010|Enter a subscription name
011|Enter a valid HTTPS URL
012|The subscription server returned HTTP %1$s
013|Subscriptions must use HTTPS
014|The subscription is empty
015|The subscription exceeds 2 MB
016|No response
017|Server address is missing. Open the details and check the JSON configuration.
018|Server port is missing from the configuration
019|Server address not found
020|DNS returned no addresses
021|Android does not allow ICMP for this app. Try TCP or HTTP.
022|No ICMP reply. The server may block ping while its VPN connection still works.
023|Could not read the ICMP response
024|No response within %1$s s
025|DNS: server address not found
026|Connection refused or port unavailable
027|A subscription must contain between 1 and 500 servers
028|Unsupported link. Use VLESS, VMess, Trojan, SS, Hysteria or TUIC.
029|Invalid server link
030|Server address is missing from the link
031|Server port must be between 1 and 65535
032|Authentication details are missing from the link
033|Invalid Shadowsocks credentials
034|Shadowsocks plugins are not supported yet. Import the complete JSON configuration.
035|TUIC requires a UUID and password
036|Invalid VMess link
037|VMess: invalid address, port or UUID
038|Invalid Base64 subscription data
039|Could not read the JSON subscription
040|The subscription must contain a JSON object or array
041|New subscription
042|Server %1$s: expected a JSON object
043|Server %1$s: invalid JSON configuration
044|The configuration must be a JSON object
045|The configuration must include an outbounds array (Xray / sing-box)
046|No proxy server found in the configuration
047|Server %1$s
048|System
049|Dark
050|Light
051|Mint
052|Ocean
053|Lavender
054|Peach
055|Rose
056|Lime
057|Cards
058|Compact
059|Grid
060|Subscription order
061|Name
062|Latency
063|Favorites first
064|All apps
065|Exclude selected
066|Selected only
067|Follow system
068|Русский
069|Auto
070|VPN permission was not granted
071|The file exceeds 2 MB
072|File unavailable
073|Could not read the file. Choose a JSON or text subscription up to 2 MB.
074|Could not open the support link
075|Copied
076|Scan a link, subscription or configuration QR code
077|VPN settings are unavailable
078|VPN connected
079|Connecting…
080|Connection failed
081|Add a subscription
082|VPN is off
083|Your servers will appear here
084|Servers: %1$s · %2$s
085|Add subscription
086|Disconnect VPN
087|Connect VPN
088|View error details
089|Could not load routing data. View details
090|The core rejected this configuration. View details
091|Servers
092|Checks
093|Settings
094|Your connection, your control
095|NyaWire logo
096|Client settings
097|%1$s · VPN connected
098|No servers yet
099|Add a subscription URL, paste a configuration or scan a QR code.
100|All · %1$s
101|Favorites
102|Subscriptions
103|Collapse
104|Check servers
105|No results
106|Try a different search or filter
107|Reset filters
108|Add servers
109|Subscription or individual configuration
110|Paste from clipboard
111|Link, JSON or server list
112|Add a link or configuration
113|Scan QR code
114|Opens the camera
115|Import file
116|JSON or text subscription
117|Add connection
118|Link or configuration
119|Add
120|Cancel
121|Delete subscription?
122|%1$s and its servers will be removed from this device.
123|Delete
124|Subscription name
125|Save
126|Connection diagnostics
127|No errors
128|VPN settings
129|Close
130|Name or address
131|Clear search
132|Sort servers
133|Collapse subscription
134|Expand subscription
135|Pinned
136|Update subscription
137|Subscription menu
138|Information
139|Rename
140|Unpin
141|Pin
142|Support
143|Until %1$s
144|Servers: %1$s\nUpdated: %2$s\nInterval: %3$s h\nTraffic: %4$s/%5$s\nExpires: %6$s
145|Connection checks
146|Use HTTP GET to check a VPN profile. The request passes through the selected server.
147|Request through the VPN core
148|Direct server check
149|Timeout: %1$s s · Samples: %2$s · Concurrent: %3$s
150|Total
151|Successful
152|Failed
153|Select fastest
154|%1$s does not verify VPN authentication. ICMP and TCP may be blocked on a working UDP server. A failed check does not necessarily mean the VPN profile is broken.
155|Add a subscription on the Servers tab to start checking.
156|%1$s of %2$s
157|Stop
158|Address not found in configuration
159|Connection checks
160|Check · %1$s
161|Selected
162|Select
163|Edit
164|Copy configuration
165|Edit server
166|Name
167|JSON or link
168|A single configuration is required
169|Wait for the subscription update to finish
170|Add a subscription and select a server
171|Disconnect the VPN before switching servers
172|Disconnect the VPN before editing the active server
173|Configuration saved
174|Disconnect the VPN before deleting the active subscription
175|Subscription added
176|Disconnect the VPN before updating the active subscription
177|Subscription updated
178|A check is already running. You can stop it on the Checks tab.
179|No servers to check
180|%1$s · %2$s of %3$s successful
181|Run a check with the selected method first
182|Selected %1$s
183|Could not complete this action. Check the network and data format.
184|Address not found
185|Server actions
186|Details and checks
187|Remove from favorites
188|Add to favorites
189|Checking
190|Not checked
191|%1$s ms
192|Error
193|Core data error
194|config
195|Config error
196|DNS error
197|TLS error
198|No response within
199|Timed out
200|Check failed
201|/s
202|B
203|KB
204|MB
205|GB
206|Appearance
207|About
208|VPN configuration
209|VPN settings take effect when you reconnect.
210|Connection core
211|Auto keeps the profile's format: Xray for Xray JSON, including XHTTP and Hysteria2; sing-box for sing-box JSON and Hysteria/TUIC links.
212|Network
213|Route IPv6 through the tunnel. IPv6 is blocked inside the VPN when disabled.
214|Tunnel packet size
215|1280 to 9000
216|Enter a number between 1280 and 9000
217|Primary DNS
218|For apps routed through the VPN
219|IPv4 or IPv6 address
220|Enter a DNS server IP address
221|Secondary DNS
222|Optional
223|Not set
224|IP address or leave blank
225|Bypass local network
226|Access home devices and local addresses directly
227|Per-app VPN
228|Selected apps
229|Use the VPN for these apps
230|These apps bypass the VPN
231|The VPN client connects directly so its core can operate. HTTP checks still pass through the selected profile.
232|Behavior
233|Verify connection
234|Run an HTTP GET through the profile before showing Connected
235|Reconnect on selection
236|Switch the active server automatically
237|Local SOCKS port
238|0 selects an available port automatically
239|0 or 1024–65535
240|Enter 0 or a port between 1024 and 65535
241|Log level
242|Errors
243|Normal
244|Info
245|Debug
246|Android VPN settings
247|Appearance and language
248|Customize the interface and choose your language.
249|App language
250|Color and theme
251|Accent
252|Android wallpaper colors
253|Dynamic colors on Android 12 and later
254|True black
255|AMOLED background in dark mode
256|Server list
257|Sort order
258|Country flags
259|Detected from the profile name
260|Protocol and transport
261|For example, VLESS · XHTTP · REALITY
262|Server addresses
263|Show host and port on server cards
264|Shape and motion
265|Corner radius · %1$s dp
266|Text size · %1$s%%
267|Subtle animations
268|Smooth selection, expansion and transitions
269|Soft blur
270|Subtle panel lighting. Blur is available on Android 12 and later.
271|Haptic feedback
272|Light feedback when selecting a server
273|Reset appearance
274|Latency checks
275|HTTP GET and HEAD use the VPN profile. Other methods check the server address directly.
276|Check method
277|Read HTTP headers through the selected core. Checks authentication, transport and access to the test site.
278|Send HEAD through the profile. Some sites block HEAD; try GET instead.
279|Measure a direct TCP connection. This may fail for UDP protocols such as Hysteria.
280|Ping the address. Android or the server may block ICMP even when the VPN works.
281|Check the TLS handshake with the profile's SNI. REALITY and other specialized transports may not support this check.
282|Measure system DNS resolution. Cached results may have very low latency.
283|Check options
284|HTTP connections
285|Default uses standard HTTP behavior. Disable opens a new connection for every sample. KeepAlive reuses the connection between samples when supported by the server. Choose at least 2 samples to compare. Applies only to HTTP GET and HEAD.
286|HTTP test URL
287|Also used to verify a new VPN connection
288|Test URL
289|Enter an HTTP or HTTPS URL
290|%1$s s
291|Samples · median result
292|Concurrent checks
293|Each HTTP check uses a separate core session. Select 1 or 2 concurrent checks on slower devices.
294|Subscriptions and settings stay on your device. A new installation starts with an empty server list.
295|Bundled cores
296|Checking library…
297|TUN bridge: HEV 2.19.1. Cores are bundled with the APK; no separate installation is required.
298|Check libraries
299|Component licenses
300|Update when opened
301|Uses the interval provided by the subscription
302|Import Xray and sing-box JSON, Base64, VLESS, VMess, Trojan, Shadowsocks, Hysteria and TUIC. Support links and quotas come from your subscription.
303|Diagnostics
304|No critical connection errors
305|Diagnostic information
306|Licenses
307|Architectures: %1$s
308|Profiles: %1$s; method: %2$s
309|Error: %1$s
310|Copy
311|Your style
312|Apps · %1$s
313|Done
314|Search apps
315|Package name
316|Add package
317|Could not create the Xray data folder
318|Bundled file %1$s is corrupted
319|Could not install %1$s for Xray: %2$s
320|VPN is already running
321|Could not start the HEV TUN bridge
322|The TUN bridge stopped during startup
323|The configuration is not a JSON object
324|Demo configurations were removed. Add your own subscription.
325|The configuration has no outbounds
326|The configuration has no proxy outbound
327|This profile uses Xray routing rules. Select Xray or import sing-box JSON.
328|This is a sing-box profile. Select Auto or sing-box in VPN settings.
329|Invalid outbound
330|This profile requires Xray
331|XHTTP requires Xray. Select Auto or Xray.
332|This profile uses certificate pinning and requires Xray
333|Xray error: %1$s
334|Timed out
335|sing-box error: %1$s
336|certificate unavailable
337|No core selected
338|%1$s: the core stopped
339|%1$s: the SOCKS listener did not start
340|sing-box: no binary found for this device architecture
341|TLS: server address is missing from the profile
342|TLS: the server did not return a certificate fingerprint
343|Select a server in the app
344|Allow the VPN in the Android permission dialog
345|Primary DNS must be an IP address
346|Secondary DNS must be an IP address
347|Enable IPv6 in VPN settings to use IPv6 DNS
348|Select apps for Selected only mode
349|The selected apps are not installed. Update the per-app VPN list.
350|Android could not create the TUN interface
351|The core did not start within 25 seconds
352|%1$s: the core process or TUN bridge stopped
353|Disconnect'''
mapping={f'text_{line.split("|",1)[0]}':line.split('|',1)[1].replace('\\n','\n') for line in translations.splitlines()}
ru_updates={
 'text_098':'Серверов пока нет', 'text_108':'Добавить серверы', 'text_145':'Проверка соединения',
 'text_208':'Параметры VPN', 'text_274':'Проверка задержки',
 'text_144':'Серверов: %1$s\nОбновлено: %2$s\nИнтервал: %3$s ч.\nТрафик: %4$s/%5$s\nИстекает: %6$s',
 'text_291':'Повторы · результат по медиане', 'text_300':'Обновлять при открытии',
 'text_267':'Плавные анимации', 'text_083':'Здесь появятся ваши серверы',
}
used=set()
for e in entries:
    key=e.get('key',e['id'])
    if key not in mapping:raise ValueError('Untranslated '+key)
    e['en']=mapping[key]
    assert collections.Counter(re.findall(r'%\d+\$s',e['ru']))==collections.Counter(re.findall(r'%\d+\$s',e['en'])), key
    if key in ru_updates:e['ru_display']=ru_updates[key]
    e['key']=key
    slug=re.sub(r'%\d+\$s','',e['en']).lower()
    slug='_'.join(re.findall('[a-z0-9]+',slug)[:7])[:65] or 'language_russian'
    if not slug[0].isalpha():slug='value_'+slug
    if slug in used:slug+='_'+key[5:]
    used.add(slug);e['id']=slug
p.write_text(json.dumps(entries,ensure_ascii=False,indent=2)+'\n','utf-8')
print('Reviewed English translations:',len(entries))
