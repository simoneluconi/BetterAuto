# Shared by the scripts in this folder (dot-sourced): messages, adb and the phone to talk to.

# Native tools write progress to stderr: judge them by exit code, not by PowerShell errors
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$package = 'com.simoneluconi.betterauto'
$androidAuto = 'com.google.android.projection.gearhead'

function Fail($message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

function Step($message) {
    Write-Host "==> $message" -ForegroundColor Cyan
}

# adb from the SDK Android Studio uses (local.properties), then ANDROID_HOME, then the default install
function Find-Adb {
    $sdk = $null
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path $localProperties) {
        $line = Select-String -Path $localProperties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) { $sdk = $line.Matches[0].Groups[1].Value.Replace('\:', ':').Replace('\\', '\') }
    }
    if (-not $sdk) { $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { Fail "adb not found in $sdk" }
    $adb
}

# The phone: the one asked for, else the only one connected (a phone on both USB and wireless counts once, by USB)
function Select-Phone($adb, $serial) {
    $lines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
    $ready = @($lines | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
    if ($lines | Where-Object { $_ -match '\tunauthorized$' }) {
        Write-Host 'A phone is waiting for you to allow USB debugging: accept the prompt on its screen.' -ForegroundColor Yellow
    }
    if ($serial) {
        if ($ready -notcontains $serial) { Fail "Phone $serial is not connected. Connected: $($ready -join ', ')" }
        return $serial
    }
    if ($ready.Count -eq 0) { Fail 'No phone connected: plug it in with USB debugging on' }
    if ($ready.Count -eq 1) { return $ready[0] }
    $usb = @($ready | Where-Object { $_ -notmatch ':' })
    if ($usb.Count -ne 1) { Fail "More than one phone connected, pick one with -Serial: $($ready -join ', ')" }
    $usb[0]
}

function Get-PhoneModel($adb, $serial) {
    (& $adb -s $serial shell getprop ro.product.model | Out-String).Trim()
}

# 'missing', 'enabled' or 'disabled' (switched off with pm disable-user, or from the phone's settings)
function Get-AppState($adb, $serial, $name) {
    if (-not (& $adb -s $serial shell pm list packages $name | Where-Object { $_.Trim() -eq "package:$name" })) { return 'missing' }
    if (& $adb -s $serial shell pm list packages -d $name | Where-Object { $_.Trim() -eq "package:$name" }) { return 'disabled' }
    'enabled'
}
