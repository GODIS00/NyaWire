param([switch]$DownloadMissing)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$projectPrefix = $projectRoot.TrimEnd('\') + '\'
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'artifacts.json') -Raw | ConvertFrom-Json
foreach ($artifact in $manifest.artifacts) {
    $target = [IO.Path]::GetFullPath((Join-Path $projectRoot $artifact.output))
    if (-not $target.StartsWith($projectPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Output outside project: $target" }
    $expected = if ($artifact.kind -in @('tar', 'zip')) { $artifact.embeddedSha256 } else { $artifact.sha256 }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        if (-not $DownloadMissing) { throw "Missing $($artifact.output); use -DownloadMissing to restore" }
        $cache = Join-Path $PSScriptRoot '.downloads'
        New-Item -ItemType Directory -Path $cache -Force | Out-Null
        $download = Join-Path $cache ($artifact.sha256 + '.download')
        if (-not (Test-Path -LiteralPath $download) -or (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $artifact.sha256) {
            Invoke-WebRequest -Uri $artifact.url -OutFile $download -UseBasicParsing
        }
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $artifact.sha256) { throw "Download SHA256 mismatch: $($artifact.name)" }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null
        if ($artifact.kind -eq 'tar') {
            & tar -xzf $download -C $cache $artifact.archiveBinary
            if ($LASTEXITCODE -ne 0) { throw "tar extraction failed: $($artifact.name)" }
            Copy-Item -LiteralPath (Join-Path $cache $artifact.archiveBinary) -Destination $target
        } elseif ($artifact.kind -eq 'zip') {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip = [IO.Compression.ZipFile]::OpenRead($download)
            try {
                $entry = $zip.GetEntry($artifact.archiveBinary)
                if (-not $entry) { throw "Missing ZIP entry: $($artifact.archiveBinary)" }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $false)
            } finally { $zip.Dispose() }
        } else { Copy-Item -LiteralPath $download -Destination $target }
    }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $expected) { throw "Local SHA256 mismatch: $($artifact.output)" }
    Write-Output "OK $($artifact.name) $($artifact.version)"
}
if ($manifest.singBoxBuild) {
    $patch = Join-Path $projectRoot $manifest.singBoxBuild.patch
    if ((Get-FileHash -LiteralPath $patch).Hash -ne $manifest.singBoxBuild.patchSha256) { throw 'sing-box patch checksum mismatch' }
    Write-Output 'OK sing-box REALITY source patch'
}
foreach ($source in $manifest.sourceArchives) {
    $file = Join-Path $PSScriptRoot ('sources/' + $source.file)
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $source.sha256) { throw "Source SHA256 mismatch: $($source.file)" }
    Write-Output "OK source $($source.file)"
}
