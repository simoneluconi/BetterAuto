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

    -Add layers more configurations after the main one (later values win): "status" (ours, in
    scripts\dhu) offers the cluster's status channels (navigation, phone, playback, each in a window),
    "cluster" a second screen for the instrument cluster and "auxillary_nav" (sic) a navigation-only
    one (both DHU 2.1+). The newest DHU found wins: the SDK's extras\google\auto, or a copy next to it
    in extras\google\auto-<version> (2.1 is on the SDK's beta channel only).

.EXAMPLE
    scripts\dhu.cmd                           1920x1080 touch screen
    scripts\dhu.cmd -Config default_720p      another of the DHU's configurations (or a path to an .ini)
    scripts\dhu.cmd -InputMode rotary         knob only, like a car without a touchscreen
    scripts\dhu.cmd -Add status,cluster       plus the cluster's status windows and its own screen
    scripts\dhu.cmd -Serial <serial>          pick the phone when more than one is connected
#>
param(
    [string]$Serial,
    [string]$Config = 'default_1080p',
    [string[]]$Add = @(),
    [ValidateSet('touch', 'rotary', 'hybrid')]
    [string]$InputMode,
    [int]$Port = 5277
)

. (Join-Path $PSScriptRoot 'phone.ps1')

# The newest DHU installed, by the version in its source.properties
$google = Join-Path (Get-Sdk) 'extras\google'
$dhuDir = Get-ChildItem $google -Directory -Filter 'auto*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'desktop-head-unit.exe') } |
    Sort-Object {
        $line = Select-String -Path (Join-Path $_.FullName 'source.properties') -Pattern '^Pkg\.Revision=(.+)$' -ErrorAction SilentlyContinue
        if ($line) { [version]$line.Matches[0].Groups[1].Value.Trim() } else { [version]'0.0' }
    } | Select-Object -Last 1 | ForEach-Object { $_.FullName }
if (-not $dhuDir) {
    Fail "Desktop Head Unit not found in ${google}\auto: install it from Android Studio, SDK Manager > SDK Tools > Android Auto Desktop Head Unit Emulator"
}
$dhu = Join-Path $dhuDir 'desktop-head-unit.exe'

# A configuration by path, by name among the DHU's own, or by name among ours (scripts\dhu)
function Resolve-Config($name) {
    if (Test-Path $name) { return (Resolve-Path $name).Path }
    foreach ($dir in @((Join-Path $dhuDir 'config'), (Join-Path $PSScriptRoot 'dhu'))) {
        $file = Join-Path $dir "$name.ini"
        if (Test-Path $file) { return $file }
    }
    $names = (@(Get-ChildItem (Join-Path $dhuDir 'config'), (Join-Path $PSScriptRoot 'dhu') -Filter *.ini) |
        ForEach-Object { $_.BaseName } | Sort-Object -Unique) -join ', '
    Fail "No DHU configuration '$name'. Available: $names"
}
$configFiles = @(Resolve-Config $Config) + @($Add | ForEach-Object { $_ -split ',' } | Where-Object { $_ } | ForEach-Object { Resolve-Config $_.Trim() })

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

    $version = (Split-Path $dhuDir -Leaf)
    $names = ($configFiles | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) }) -join ' + '
    Step "Starting the Desktop Head Unit ($version, $names)"
    Write-Host 'The window is the car screen; type "help" here for the commands, "quit" or close the window to end.'
    $arguments = @($configFiles | ForEach-Object { '-c', $_ }) + @('-a', "127.0.0.1:$Port")
    if ($InputMode) { $arguments += @('-i', $InputMode) }
    Push-Location $dhuDir
    try { & $dhu @arguments } finally { Pop-Location }
} finally {
    & $adb -s $Serial forward --remove "tcp:$Port" 2>&1 | Out-Null
}
