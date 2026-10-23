$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$localProperties = Join-Path $projectRoot 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    $sdkSetting = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkSetting) { $sdkRoot = $sdkSetting.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
$emulatorExe = Join-Path $sdkRoot 'emulator/emulator.exe'
$adbExe = Join-Path $sdkRoot 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $emulatorExe) -or -not (Test-Path -LiteralPath $adbExe)) {
    throw 'Android Emulator not found. Install it through Android Studio.'
}
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/avd'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:ANDROID_EMULATOR_HOME = Join-Path $projectRoot '.tools/emulator-home'
$env:ANDROID_ADB_SERVER_PORT = '5038'
if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME 'GDHAPP.ini'))) {
    throw 'NyaWire emulator profile is missing.'
}
& $adbExe -P 5038 start-server
if ($LASTEXITCODE -ne 0) { throw 'Could not start ADB' }
$devices = & $adbExe -P 5038 devices
if (($devices -join "`n") -notmatch 'emulator-5558\s+(device|offline)') {
    $emulatorProcess = Start-Process -FilePath $emulatorExe -ArgumentList @('-avd', 'GDHAPP', '-port', '5558', '-no-snapshot', '-no-boot-anim', '-gpu', 'swiftshader', '-memory', '2560') -WindowStyle Normal -RedirectStandardOutput (Join-Path $projectRoot '.tools/emulator-interactive.stdout.log') -RedirectStandardError (Join-Path $projectRoot '.tools/emulator-interactive.stderr.log') -PassThru
}
Write-Host 'Starting Android...'
$deadline = [DateTime]::UtcNow.AddMinutes(3)
do {
    $connected = & $adbExe -P 5038 devices
    $booted = if (($connected -join "`n") -match 'emulator-5558\s+device') {
        & $adbExe -P 5038 -s emulator-5558 shell getprop sys.boot_completed
    } else { '' }
    if ($booted -and $booted.Trim() -eq '1') { break }
    if ([DateTime]::UtcNow -gt $deadline) { throw 'Android startup timed out. See .tools/emulator-interactive.stderr.log' }
    Start-Sleep -Seconds 2
} while ($true)
& $adbExe -P 5038 -s emulator-5558 shell wm dismiss-keyguard
$apk = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'artifacts') -Filter 'NyaWire-*-universal.apk' -File |
    Sort-Object { if ($_.Name -match 'NyaWire-(\d+\.\d+\.\d+)-universal.apk') { [version]$Matches[1] } else { [version]'0.0.0' } } -Descending |
    Select-Object -First 1
if (-not $apk) { throw 'Universal APK not found in artifacts.' }
& $adbExe -P 5038 -s emulator-5558 install -r $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'Could not install NyaWire' }
& $adbExe -P 5038 -s emulator-5558 shell am start -n com.gdhapp.client/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Could not open NyaWire' }
Write-Host 'NyaWire is open. Close the Android window when finished.'
if ($emulatorProcess) { $emulatorProcess.WaitForExit() }
