param(
    [Parameter(Mandatory = $true)][string]$Repository,
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or ($Repository.Split('/') | Where-Object { $_ -in @('.', '..') })) { throw 'Use owner/repository' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildFile = Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle.kts') -Raw
$version = [regex]::Match($buildFile, 'versionName = "([0-9.]+)"').Groups[1].Value
if (-not $version) { throw 'Missing versionName' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or newer' }
if ($Publish) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Install GitHub CLI and run gh auth login first' }
    & gh auth status
    if ($LASTEXITCODE -ne 0) { throw 'GitHub authorization required' }
    & gh repo view $Repository --json nameWithOwner,visibility
    if ($LASTEXITCODE -ne 0) { throw 'Repository unavailable' }
    $visibility = & gh repo view $Repository --json visibility --jq '.visibility'
    if ($LASTEXITCODE -ne 0 -or $visibility -ne 'PUBLIC') { throw 'App update checks require a public repository' }
    & gh release view "v$version" --repo $Repository *> $null
    if ($LASTEXITCODE -eq 0) { throw "Release v$version already exists; increase versionName and versionCode" }
}
$artifactDir = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
$universal = Join-Path $artifactDir "NyaWire-$version-universal.apk"
$arm64 = Join-Path $artifactDir "NyaWire-$version-arm64.apk"
$source = Join-Path $artifactDir "NyaWire-$version-source.zip"
$checksums = Join-Path $artifactDir "SHA256SUMS-$version.txt"
foreach ($target in @($universal, $arm64, $source, $checksums)) {
    if (Test-Path -LiteralPath $target) { throw "Artifact already exists: $target" }
}
Push-Location $projectRoot
try {
    & .\gradlew.bat assembleRelease testDebugUnitTest lintRelease "-PgithubRepository=$Repository" --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed' }
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $universal
    & .\gradlew.bat assembleRelease '-PtargetAbi=arm64-v8a' "-PgithubRepository=$Repository" --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'ARM64 build failed' }
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $arm64
    # Existing installations must accept the APK as an update. Never publish the private key.
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter apksigner.bat -File -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $signer) { throw 'apksigner unavailable' }
    foreach ($apk in @($universal, $arm64)) {
        $certificate = & $signer verify --print-certs $apk
        if ($LASTEXITCODE -ne 0 -or ($certificate -join "`n") -notmatch 'bc6646993ffcd9c857ee91fcac843b5a4d65cfda9db348092da6cd9dbe6270b0') {
            throw 'Signing certificate differs from installed NyaWire; release stopped'
        }
    }
    & (Join-Path $PSScriptRoot 'package-source.ps1') -OutputName "NyaWire-$version-source.zip"
    $lines = foreach ($file in @($universal, $arm64, $source)) {
        $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $([IO.Path]::GetFileName($file))"
    }
    [IO.File]::WriteAllLines($checksums, $lines, [Text.UTF8Encoding]::new($false))
    if ($Publish) {
        & gh release create "v$version" $universal $arm64 $source $checksums --repo $Repository --title "NyaWire $version" --notes 'APK: arm64 for modern phones; universal for other architectures. Source and SHA-256 included.' --draft
        if ($LASTEXITCODE -ne 0) { throw 'Release upload failed; any draft is left unpublished' }
        & gh release edit "v$version" --repo $Repository --draft=false --latest
        if ($LASTEXITCODE -ne 0) { throw 'Could not publish release' }
    }
} finally { Pop-Location }
