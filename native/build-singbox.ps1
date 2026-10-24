param(
    [Parameter(Mandatory = $true)][string]$NdkRoot,
    [string]$GoExecutable = 'go',
    [ValidateSet('arm64-v8a','armeabi-v7a','x86','x86_64')][string[]]$Abis = @('x86_64','arm64-v8a','armeabi-v7a','x86')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$version = '1.14.3-nyawire.1'
$archive = Join-Path $PSScriptRoot 'sources/sing-box-v1.14.3.tar.gz'
$patch = Join-Path $PSScriptRoot 'patches/sing-box-1.14.3-reality.patch'
$manifest = Get-Content (Join-Path $PSScriptRoot 'artifacts.json') -Raw | ConvertFrom-Json
$expectedSource = ($manifest.sourceArchives | Where-Object { $_.file -eq 'sing-box-v1.14.3.tar.gz' }).sha256
if ((Get-FileHash -LiteralPath $archive).Hash -ne $expectedSource) { throw 'sing-box source checksum mismatch' }
$patchHash = (Get-FileHash -LiteralPath $patch).Hash.ToLowerInvariant()
if ($manifest.singBoxBuild -and $patchHash -ne $manifest.singBoxBuild.patchSha256) { throw 'sing-box patch checksum mismatch' }
$work = Join-Path $PSScriptRoot ('.downloads/singbox-' + $patchHash.Substring(0,16))
$source = Join-Path $work 'sing-box-1.14.3'
$ndk = [IO.Path]::GetFullPath($NdkRoot)
if ((Get-Content (Join-Path $ndk 'source.properties') -Raw) -notmatch 'Pkg.Revision\s*=\s*28\.0\.13004108') { throw 'Use Android NDK r28 (28.0.13004108)' }
$compiler = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$goPath = (Get-Command $GoExecutable -ErrorAction Stop).Source
$savedEnvironment = @{}
foreach ($name in @('GOTOOLCHAIN','GOPATH','GOMODCACHE','GOCACHE','GOOS','GOARCH','GOARM','CGO_ENABLED','CC','CXX')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:GOTOOLCHAIN = 'local'
    $goVersion = & $goPath env GOVERSION
    if ($LASTEXITCODE -ne 0 -or $goVersion.Trim() -ne 'go1.27.3') { throw 'Use Go 1.27.3 to reproduce the bundled cores' }
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    if (-not (Test-Path (Join-Path $source '.nyawire-patch'))) {
        & tar -xzf $archive -C $work
        if ($LASTEXITCODE -ne 0) { throw 'Could not extract sing-box source' }
        & git -c "safe.directory=$projectRoot" -C $source apply --no-index --check $patch
        if ($LASTEXITCODE -ne 0) { throw 'Patch does not apply to the pinned source' }
        & git -c "safe.directory=$projectRoot" -C $source apply --no-index $patch
        if ($LASTEXITCODE -ne 0) { throw 'Could not apply REALITY compatibility patch' }
        [IO.File]::WriteAllText((Join-Path $source '.nyawire-patch'), $patchHash)
    }
    $env:GOPATH = Join-Path $PSScriptRoot '.downloads/gopath'
    $env:GOMODCACHE = Join-Path $env:GOPATH 'pkg/mod'
    $env:GOCACHE = Join-Path $PSScriptRoot '.downloads/gocache'
    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    $targets = @{
        'arm64-v8a' = @('arm64', 'aarch64-linux-android23')
        'armeabi-v7a' = @('arm', 'armv7a-linux-androideabi23')
        'x86' = @('386', 'i686-linux-android23')
        'x86_64' = @('amd64', 'x86_64-linux-android23')
    }
    $tags = (Get-Content (Join-Path $source 'release/DEFAULT_BUILD_TAGS_OTHERS') -Raw).Trim()
    $flags = "-X github.com/sagernet/sing-box/constant.Version=$version -s -w -buildid= " + (Get-Content (Join-Path $source 'release/LDFLAGS') -Raw).Trim()
    Push-Location $source
    try {
        foreach ($abi in $Abis) {
            $env:GOARCH = $targets[$abi][0]
            $env:GOARM = if ($abi -eq 'armeabi-v7a') { '7' } else { '' }
            $env:CC = Join-Path $compiler ($targets[$abi][1] + '-clang.cmd')
            $env:CXX = Join-Path $compiler ($targets[$abi][1] + '-clang++.cmd')
            $binary = Join-Path $work ($abi + '/libsingbox_exec.so')
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($binary)) -Force | Out-Null
            & $goPath build -mod=readonly -buildvcs=false -trimpath -tags $tags -ldflags $flags -o $binary ./cmd/sing-box
            if ($LASTEXITCODE -ne 0) { throw "sing-box build failed: $abi" }
            Write-Output "Built $abi $((Get-FileHash -LiteralPath $binary).Hash.ToLowerInvariant())"
        }
    } finally { Pop-Location }
    # Install only after every requested architecture has built successfully.
    foreach ($abi in $Abis) {
        $destination = Join-Path $projectRoot "app/src/main/jniLibs/$abi/libsingbox_exec.so"
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $work "$abi/libsingbox_exec.so") -Destination $destination
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
