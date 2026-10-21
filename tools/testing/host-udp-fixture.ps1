$ErrorActionPreference = 'Stop'
$server = [Net.Sockets.UdpClient]::new([Net.IPEndPoint]::new([Net.IPAddress]::Loopback, 18081))
try {
    while ($true) {
        $remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any, 0)
        $request = $server.Receive([ref]$remote)
        if ([Text.Encoding]::ASCII.GetString($request) -eq 'NyaWire-UDP-PROBE') {
            $response = [Text.Encoding]::ASCII.GetBytes('NyaWire-UDP-OK')
            [void]$server.Send($response, $response.Length, $remote)
        }
    }
} finally { $server.Dispose() }
