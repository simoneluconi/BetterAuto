# Shared by the scripts in this folder (dot-sourced): messages, Java and Gradle, adb and the phone to talk to,
# what an APK carries and who signed it.

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

# Java for Gradle: JAVA_HOME, java on the PATH, or the one shipped with Android Studio
function Initialize-Java {
    if ($env:JAVA_HOME -or (Get-Command java -ErrorAction SilentlyContinue)) { return }
    $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not (Test-Path (Join-Path $studioJbr 'bin\java.exe'))) {
        Fail 'No Java found: install Android Studio or set JAVA_HOME'
    }
    $env:JAVA_HOME = $studioJbr
}

# Runs Gradle from the project root; fails the script when the build fails
function Invoke-Gradle([string[]]$arguments) {
    Push-Location $root
    & .\gradlew.bat --console=plain @arguments
    $exit = $LASTEXITCODE
    Pop-Location
    if ($exit -ne 0) { Fail 'Build failed, see the errors above' }
}

# The SDK Android Studio uses (local.properties), then ANDROID_HOME, then the default install
function Get-Sdk {
    $sdk = $null
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path $localProperties) {
        $line = Select-String -Path $localProperties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) { $sdk = $line.Matches[0].Groups[1].Value.Replace('\:', ':').Replace('\\', '\') }
    }
    if (-not $sdk) { $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } }
    $sdk
}

function Find-Adb {
    $sdk = Get-Sdk
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

# The certificate files an APK carries (anywhere under assets/), as path -> SHA-256
function Get-ApkCertificates($apk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $extensions = @('.pem', '.crt', '.cer', '.key', '.der', '.p12', '.pfx')
    $found = @{}
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notlike 'assets/*') { continue }
            if ($extensions -notcontains [IO.Path]::GetExtension($entry.Name).ToLower()) { continue }
            $stream = $entry.Open()
            try { $found[$entry.FullName.Substring('assets/'.Length)] = Get-Sha256 $stream } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    $found
}

function Get-Sha256($stream) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLower() } finally { $sha.Dispose() }
}

function Get-FileSha256($path) {
    $stream = [IO.File]::OpenRead($path)
    try { Get-Sha256 $stream } finally { $stream.Dispose() }
}

# The SHA-256 of the key an APK is signed with (lower case, no colons), after apksigner has verified it
function Get-ApkSigner($apk) {
    $buildTools = Get-ChildItem (Join-Path (Get-Sdk) 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } | Select-Object -Last 1
    $apksigner = if ($buildTools) { Join-Path $buildTools.FullName 'apksigner.bat' }
    if (-not $apksigner -or -not (Test-Path $apksigner)) { Fail 'apksigner not found in the SDK build-tools' }
    $out = (& $apksigner verify --print-certs $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
    if ($LASTEXITCODE -ne 0) { Fail "The APK is not signed properly:`n$out" }
    $digest = [regex]::Match($out, 'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)')
    if (-not $digest.Success) { Fail "No signer in apksigner's answer:`n$out" }
    $digest.Groups[1].Value
}

# 'missing', 'enabled' or 'disabled' (switched off with pm disable-user, or from the phone's settings)
function Get-AppState($adb, $serial, $name) {
    if (-not (& $adb -s $serial shell pm list packages $name | Where-Object { $_.Trim() -eq "package:$name" })) { return 'missing' }
    if (& $adb -s $serial shell pm list packages -d $name | Where-Object { $_.Trim() -eq "package:$name" }) { return 'disabled' }
    'enabled'
}
