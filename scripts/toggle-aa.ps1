<#
.SYNOPSIS
    Switches the phone between the official Android Auto and BetterAuto: one on, the other off.

.DESCRIPTION
    Both answer when a head unit plugs in, so only one should be on. The one switched off is disabled
    with "pm disable-user": it stays installed with its data, and the phone hides it until it is switched
    back on (by this script or from Settings > Apps).

.EXAMPLE
    scripts\toggle-aa.cmd                     swap: whichever is on goes off, the other comes on
    scripts\toggle-aa.cmd -Show               only tell which one is on
    scripts\toggle-aa.cmd -To BetterAuto      BetterAuto on, Android Auto off (also -To AndroidAuto)
    scripts\toggle-aa.cmd -Serial <serial>    pick the phone when more than one is connected
#>
param(
    [string]$Serial,
    [ValidateSet('BetterAuto', 'AndroidAuto')]
    [string]$To,
    [switch]$Show
)

. (Join-Path $PSScriptRoot 'phone.ps1')

function Set-AppEnabled($name, $label, [bool]$enabled) {
    if ($enabled) {
        $out = (& $adb -s $Serial shell pm enable --user 0 $name 2>&1 | ForEach-Object { "$_" }) -join ' '
    } else {
        $out = (& $adb -s $Serial shell pm disable-user --user 0 $name 2>&1 | ForEach-Object { "$_" }) -join ' '
    }
    $wanted = if ($enabled) { 'enabled' } else { 'disabled' }
    if ((Get-AppState $adb $Serial $name) -ne $wanted) { Fail "Could not switch $label to ${wanted}: $out" }
}

function Show-State {
    $describe = @{ enabled = 'ON'; disabled = 'off'; missing = 'not installed' }
    Write-Host "  BetterAuto:           $($describe[(Get-AppState $adb $Serial $package)])"
    Write-Host "  Android Auto (Google): $($describe[(Get-AppState $adb $Serial $androidAuto)])"
}

$adb = Find-Adb
$Serial = Select-Phone $adb $Serial
$model = Get-PhoneModel $adb $Serial

$better = Get-AppState $adb $Serial $package
$official = Get-AppState $adb $Serial $androidAuto
if ($Show) {
    Write-Host "$model ($Serial):"
    Show-State
    exit 0
}

# Without -To: if Android Auto is on (also when both are), BetterAuto takes over; otherwise Android Auto comes back
if (-not $To) { $To = if ($official -eq 'enabled') { 'BetterAuto' } else { 'AndroidAuto' } }

if ($To -eq 'BetterAuto') {
    if ($better -eq 'missing') { Fail 'BetterAuto is not installed on this phone: run scripts\deploy.cmd first' }
    Step "Switching $model to BetterAuto"
    Set-AppEnabled $package 'BetterAuto' $true
    if ($official -ne 'missing') { Set-AppEnabled $androidAuto 'Android Auto' $false }
} else {
    if ($official -eq 'missing') { Fail 'The official Android Auto is not installed on this phone' }
    Step "Switching $model to the official Android Auto"
    Set-AppEnabled $androidAuto 'Android Auto' $true
    if ($better -ne 'missing') { Set-AppEnabled $package 'BetterAuto' $false }
}

Show-State
Write-Host 'Done. If the phone is plugged into the head unit, re-plug the cable so the new app takes the connection.' -ForegroundColor Green
