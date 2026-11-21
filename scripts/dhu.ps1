<#
.SYNOPSIS
    A car on this PC: Google's Desktop Head Unit (DHU) connected to BetterAuto on the phone.

.DESCRIPTION
    The DHU connects to the phone, as it does with Android Auto's "head unit server": BetterAuto (debug
    build) waits on the phone's loopback, adb forwards the PC's port to it, then the DHU starts in this
    console. Its window is the car screen (mouse = touch, keys = knob); its console takes commands
    ("help" lists them: day/night, dpad rotate left/right, keycode, focus, location, screenshot...).
    Closing the DHU ends the session. Needs the DHU from the SDK Manager (SDK Tools, "Android Auto
    Desktop Head Unit Emulator") and BetterAuto on, not the official Android Auto (toggle-aa.cmd).

.EXAMPLE
    scripts\dhu.cmd                           1920x1080 touch screen
    scripts\dhu.cmd -Config default_720p      another of the DHU's configurations (or a path to an .ini)
    scripts\dhu.cmd -InputMode rotary         knob only, like a car without a touchscreen
    scripts\dhu.cmd -Serial <serial>          pick the phone when more than one is connected
#>
param(
    [string]$Serial,
    [string]$Config = 'default_1080p',
    [ValidateSet('touch', 'rotary', 'hybrid')]
    [string]$InputMode,
    [int]$Port = 5277
)

. (Join-Path $PSScriptRoot 'phone.ps1')

$dhuDir = Join-Path (Get-Sdk) 'extras\google\auto'
$dhu = Join-Path $dhuDir 'desktop-head-unit.exe'
if (-not (Test-Path $dhu)) {
    Fail "Desktop Head Unit not found in ${dhuDir}: install it from Android Studio, SDK Manager > SDK Tools > Android Auto Desktop Head Unit Emulator"
}
$configFile = if (Test-Path $Config) { (Resolve-Path $Config).Path } else { Join-Path $dhuDir "config\$Config.ini" }
if (-not (Test-Path $configFile)) {
    $names = (Get-ChildItem (Join-Path $dhuDir 'config') -Filter *.ini | ForEach-Object { $_.BaseName }) -join ', '
    Fail "No DHU configuration '$Config'. Available: $names"
}

$adb = Find-Adb
$Serial = Select-Phone $adb $Serial
$model = Get-PhoneModel $adb $Serial
$state = Get-AppState $adb $Serial $package
if ($state -eq 'missing') { Fail 'BetterAuto is not installed on this phone: run scripts\deploy.cmd first' }
if ($state -eq 'disabled') { Fail 'BetterAuto is switched off on this phone: scripts\toggle-aa.cmd -To BetterAuto' }

Step "Forwarding port $Port to $model"
& $adb -s $Serial forward "tcp:$Port" "tcp:$Port" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "adb could not forward port $Port (is another DHU or program using it?)" }

try {
    # The phone's clock, to read only the log lines written from now on (epoch seconds: adb shell splits on spaces)
    $since = "$((& $adb -s $Serial shell date +%s | Out-String).Trim()).000"
    Step 'BetterAuto waits for the head unit'
    & $adb -s $Serial shell am broadcast -p $package -a "$package.DEBUG_INPUT" --es listen $Port | Out-Null
    $ready = $false
    for ($i = 0; $i -lt 20 -and -not $ready; $i++) {
        Start-Sleep -Milliseconds 250
        $lines = @(& $adb -s $Serial logcat -d -s BetterAuto -T $since)
        $failed = @($lines -match 'TCP: cannot listen|cannot start the service')
        if ($failed.Count -gt 0) { Fail ($failed -join "`n") }
        $ready = @($lines -match 'TCP: waiting for a head unit').Count -gt 0
    }
    if (-not $ready) { Fail 'BetterAuto did not answer: is the installed build a debug one? Open the app once and try again' }

    Step "Starting the Desktop Head Unit ($([IO.Path]::GetFileNameWithoutExtension($configFile)))"
    Write-Host 'The window is the car screen; type "help" here for the commands, "quit" or close the window to end.'
    $arguments = @('-c', $configFile, '-a', "127.0.0.1:$Port")
    if ($InputMode) { $arguments += @('-i', $InputMode) }
    Push-Location $dhuDir
    try { & $dhu @arguments } finally { Pop-Location }
} finally {
    & $adb -s $Serial forward --remove "tcp:$Port" 2>&1 | Out-Null
}
