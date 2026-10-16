<#
.SYNOPSIS
    Builds BetterAuto (debug) and installs it on the phone connected over adb.

.EXAMPLE
    scripts\deploy.cmd                        build, install, open the app on the phone
    scripts\deploy.cmd -Tests                 run the unit tests before installing
    scripts\deploy.cmd -NoLaunch              install without opening the app
    scripts\deploy.cmd -Serial <serial>       pick the phone when more than one is connected
#>
param(
    [string]$Serial,
    [switch]$Tests,
    [switch]$NoLaunch
)

# Native tools write progress to stderr: judge them by exit code, not by PowerShell errors
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$package = 'com.simoneluconi.betterauto'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

function Fail($message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

function Step($message) {
    Write-Host "==> $message" -ForegroundColor Cyan
}

# Java for Gradle: JAVA_HOME, java on the PATH, or the one shipped with Android Studio
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not (Test-Path (Join-Path $studioJbr 'bin\java.exe'))) {
        Fail 'No Java found: install Android Studio or set JAVA_HOME'
    }
    $env:JAVA_HOME = $studioJbr
}

# adb from the SDK Android Studio uses (local.properties), then ANDROID_HOME, then the default install
$sdk = $null
$localProperties = Join-Path $root 'local.properties'
if (Test-Path $localProperties) {
    $line = Select-String -Path $localProperties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
    if ($line) { $sdk = $line.Matches[0].Groups[1].Value.Replace('\:', ':').Replace('\\', '\') }
}
if (-not $sdk) { $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { Fail "adb not found in $sdk" }

# The phone: the one asked for, else the only one connected (a phone on both USB and wireless counts once, by USB)
$lines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
$ready = @($lines | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
if ($lines | Where-Object { $_ -match '\tunauthorized$' }) {
    Write-Host 'A phone is waiting for you to allow USB debugging: accept the prompt on its screen.' -ForegroundColor Yellow
}
if ($Serial) {
    if ($ready -notcontains $Serial) { Fail "Phone $Serial is not connected. Connected: $($ready -join ', ')" }
} elseif ($ready.Count -eq 0) {
    Fail 'No phone connected: plug it in with USB debugging on'
} elseif ($ready.Count -eq 1) {
    $Serial = $ready[0]
} else {
    $usb = @($ready | Where-Object { $_ -notmatch ':' })
    if ($usb.Count -ne 1) { Fail "More than one phone connected, pick one with -Serial: $($ready -join ', ')" }
    $Serial = $usb[0]
}
$model = (& $adb -s $Serial shell getprop ro.product.model | Out-String).Trim()

$tasks = @('assembleDebug')
if ($Tests) { $tasks = @('testDebugUnitTest') + $tasks }
Step "Building: $($tasks -join ', ')"
Push-Location $root
& .\gradlew.bat --console=plain @tasks
$buildExit = $LASTEXITCODE
Pop-Location
if ($buildExit -ne 0) { Fail 'Build failed, see the errors above' }

Step "Installing on $model ($Serial)"
$install = (& $adb -s $Serial install -r $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
Write-Host $install
if ($install -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Fail "The phone has a BetterAuto signed with another key. Uninstall it first (this deletes its settings):`n    `"$adb`" -s $Serial uninstall $package"
}
if ($install -notmatch 'Success') { Fail 'Install failed, see the message above' }

if (-not $NoLaunch) {
    # Opening the app once also lets it receive the debug broadcasts after a fresh install
    Step 'Opening BetterAuto on the phone'
    & $adb -s $Serial shell am start -n "$package/.MainActivity" | Out-Null
}

$version = ((& $adb -s $Serial shell dumpsys package $package | Select-String 'versionName=' | Select-Object -First 1) -replace '.*versionName=', '').Trim()
Write-Host "Done: BetterAuto $version installed on $model." -ForegroundColor Green
Write-Host 'If the phone is plugged into the head unit, the session stopped with the update: re-plug the cable (or restart crankshaft-core on the Pi).'
