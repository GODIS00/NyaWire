param([string]$OutputName = 'NyaWire-2.10.0-source.zip')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$prefix = $projectRoot.TrimEnd('\') + '\'
if ([IO.Path]::GetFileName($OutputName) -ne $OutputName -or -not $OutputName.EndsWith('.zip')) { throw 'OutputName must be a zip filename' }
$output = Join-Path $projectRoot ('artifacts/' + $OutputName)
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'artifacts') -Force | Out-Null
if (Test-Path -LiteralPath $output) { throw "Archive already exists: $output" }
$files = [Collections.Generic.List[IO.FileInfo]]::new()
foreach ($name in @('README.md','THIRD_PARTY.md','LICENSE','.gitignore','.gitattributes','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','Start-NyaWire.bat','app/build.gradle.kts','app/proguard-rules.pro')) {
    $files.Add((Get-Item -LiteralPath (Join-Path $projectRoot $name)))
}
foreach ($directory in @('app/src','app/libs','gradle','native','tools','assets')) {
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -File -Recurse)) {
        $relative = $file.FullName.Substring($prefix.Length).Replace('\','/')
        if ($relative -like 'native/.downloads/*' -or $relative -eq 'native/sources/AndroidLibXrayLite-v26.9.9.tar.gz') { continue }
        $files.Add($file)
    }
}
$archive = [IO.Compression.ZipFile]::Open($output, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        if (-not $file.FullName.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "File outside project: $file" }
        $relative = $file.FullName.Substring($prefix.Length).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
Get-Item -LiteralPath $output | Select-Object FullName,Length
