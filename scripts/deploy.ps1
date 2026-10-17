<#
.SYNOPSIS
    Builds BetterAuto (debug), installs it on the phone connected over adb and pre-approves its audio
    capture (no consent prompt on every connection).

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

. (Join-Path $PSScriptRoot 'phone.ps1')
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

# Java for Gradle: JAVA_HOME, java on the PATH, or the one shipped with Android Studio
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not (Test-Path (Join-Path $studioJbr 'bin\java.exe'))) {
        Fail 'No Java found: install Android Studio or set JAVA_HOME'
    }
    $env:JAVA_HOME = $studioJbr
}

$adb = Find-Adb
$Serial = Select-Phone $adb $Serial
$model = Get-PhoneModel $adb $Serial

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

# Pre-approved audio capture: the car gets the phone's sound without the consent prompt on every connection
Step 'Allowing audio capture without asking'
& $adb -s $Serial shell appops set $package PROJECT_MEDIA allow | Out-Null
$audio = (& $adb -s $Serial shell appops get $package PROJECT_MEDIA | Out-String).Trim()
if ($audio -notmatch 'allow') { Write-Host "Could not pre-approve audio capture ($audio): the phone will ask on each connection." -ForegroundColor Yellow }

# Installing switches BetterAuto back on even if toggle-aa had switched it off
if ((Get-AppState $adb $Serial $androidAuto) -eq 'enabled') {
    Write-Host 'The official Android Auto is on too, and both answer the head unit: run scripts\toggle-aa.cmd -To BetterAuto (or -To AndroidAuto) to keep one.' -ForegroundColor Yellow
}
if (-not $NoLaunch) {
    # Opening the app once also lets it receive the debug broadcasts after a fresh install
    Step 'Opening BetterAuto on the phone'
    & $adb -s $Serial shell am start -n "$package/.MainActivity" | Out-Null
}

$version = ((& $adb -s $Serial shell dumpsys package $package | Select-String 'versionName=' | Select-Object -First 1) -replace '.*versionName=', '').Trim()
Write-Host "Done: BetterAuto $version installed on $model." -ForegroundColor Green
Write-Host 'If the phone is plugged into the head unit, the session stopped with the update: re-plug the cable (or restart crankshaft-core on the Pi).'
