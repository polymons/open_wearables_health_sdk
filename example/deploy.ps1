[CmdletBinding()]
param(
    [string]$Device = '',
    [ValidateSet('release', 'debug', 'profile')]
    [string]$Mode = 'release',
    [switch]$SkipClean,
    [switch]$KeepAppData
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppId = 'com.openwearables.health.sdk.example'

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

Set-Location $ScriptDir

if (-not $Device) {
    $devices = (& adb devices) | Select-Object -Skip 1 |
        Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 0) { Fail "No authorized adb devices. Connect a phone and accept the USB debugging prompt." }
    if ($devices.Count -gt 1) { Fail "Multiple devices found: $($devices -join ', '). Re-run with -Device <serial>." }
    $Device = $devices[0]
}
Write-Host "Target device: $Device" -ForegroundColor Green

Step "Stopping Gradle daemons (prevents stale file-locks on build/)"
& "$ScriptDir/android/gradlew.bat" --stop | Out-Host

if (-not $KeepAppData) {
    Step "Uninstalling $AppId from $Device (use -KeepAppData to skip)"
    & adb -s $Device uninstall $AppId 2>&1 | Out-Host
}

if (-not $SkipClean) {
    Step "flutter clean"
    & flutter clean | Out-Host
}

Step "flutter pub get"
& flutter pub get | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "flutter pub get failed" }

Step "flutter build apk --$Mode"
& flutter build apk "--$Mode" | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "flutter build apk failed" }

Step "flutter install -d $Device --$Mode"
& flutter install -d $Device "--$Mode" | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "flutter install failed" }

Write-Host "`nDone. $AppId installed on $Device." -ForegroundColor Green
