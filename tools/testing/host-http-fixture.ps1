$ErrorActionPreference = 'Stop'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 18080)
$listener.Start()
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 10000
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 8192, $true)
            while ($true) { $line = $reader.ReadLine(); if ($null -eq $line -or $line.Length -eq 0) { break } }
            $body = [System.Text.Encoding]::ASCII.GetBytes('NyaWire-TUN-OK')
            $headers = [System.Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($headers, 0, $headers.Length)
            $stream.Write($body, 0, $body.Length)
            $stream.Flush()
        } catch { } finally { $client.Dispose() }
    }
} finally { $listener.Stop() }
