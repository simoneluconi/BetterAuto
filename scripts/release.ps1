<#
.SYNOPSIS
    Publishes a BetterAuto release on GitHub: unit tests, the release APK (signed with the release key, our
    development certificate inside), the tag and the release with its notes from CHANGELOG.md.

.DESCRIPTION
    Before running it: appVersion set in app/build.gradle.kts and its section in CHANGELOG.md ("## x.y.z",
    renamed from "Unreleased", in English and Italian), both committed and pushed. The release key comes from
    %USERPROFILE%\.gradle\gradle.properties (docs/DEVELOPMENT.md, "Releases").
    Everything lands in build\release\<version>: the APK, the GitHub notes, the changes in each language.

.EXAMPLE
    scripts\release.cmd -DryRun      tests, build and checks only: nothing is published
    scripts\release.cmd              the same, then the tag and the GitHub release
    scripts\release.cmd -NoBuild     publishes what an earlier -DryRun left in build\release\<version>
#>
param(
    [switch]$DryRun,
    [switch]$NoBuild
)

. (Join-Path $PSScriptRoot 'phone.ps1')
Initialize-Java

# The release key (SHA-256 of its certificate): an update signed with another one is refused by the phone
$releaseKey = '14c08060a5d87b6d1122d935367b9a070c4d25787b68d494a33def2bb570deec'
$devCertificates = @('phone_cert.pem', 'phone_key.pem')
$utf8 = New-Object Text.UTF8Encoding $false

function Read-Text($path) { [IO.File]::ReadAllText($path, $utf8) }
function Write-Text($path, $text) { [IO.File]::WriteAllText($path, $text, $utf8) }

$m = [regex]::Match((Read-Text (Join-Path $root 'app\build.gradle.kts')), '(?m)^val appVersion = "(\d+)\.(\d+)\.(\d+)"')
if (-not $m.Success) { Fail 'No val appVersion = "x.y.z" in app/build.gradle.kts' }
$version = "$($m.Groups[1].Value).$($m.Groups[2].Value).$($m.Groups[3].Value)"
$prerelease = $m.Groups[1].Value -eq '0'
$tag = "v$version"
$out = Join-Path $root "build\release\$version"
$apk = Join-Path $out "BetterAuto-$version.apk"

# One language's changes for this version: "## x.y.z ..." in CHANGELOG.md, then "### English" / "### Italiano"
function Get-Changes($language) {
    $changelog = Read-Text (Join-Path $root 'CHANGELOG.md')
    $section = [regex]::Match($changelog, "(?ms)^## $([regex]::Escape($version))(?:[ \t][^\r\n]*)?\r?\n(.*?)(?=^## |\z)")
    if (-not $section.Success) { Fail "CHANGELOG.md has no section `"## $version`": rename `"Unreleased`" to it" }
    $part = [regex]::Match($section.Groups[1].Value, "(?ms)^### $language[ \t]*\r?\n(.*?)(?=^### |\z)")
    $text = if ($part.Success) { $part.Groups[1].Value.Trim() } else { '' }
    if (-not $text) { Fail "CHANGELOG.md, $version`: no `"### $language`" notes" }
    $text -replace '\r\n', "`n"
}

# Problems that stop a release: shown as errors, or as warnings in a dry run
function Stop-Release($message) {
    if ($DryRun) { Write-Host "WARNING: $message (a real release stops here)" -ForegroundColor Yellow } else { Fail $message }
}

Step "BetterAuto $version"
$english = Get-Changes 'English'
$italian = Get-Changes 'Italiano'

# What gets built is what is committed: nothing changed or new in the sources
$dirty = @(git -C $root status --porcelain -- app gradle build.gradle.kts settings.gradle.kts gradle.properties CHANGELOG.md docs/release-intro.md)
if ($dirty.Count -gt 0) { Stop-Release "Uncommitted changes in the sources:`n$($dirty -join "`n")" }
git -C $root fetch --quiet --tags origin
$commit = (git -C $root rev-parse HEAD | Out-String).Trim()
if (-not (git -C $root branch -r --contains $commit)) { Stop-Release 'This commit is not pushed to GitHub yet' }
if (git -C $root rev-parse -q --verify "refs/tags/$tag") { Stop-Release "Tag $tag exists already: raise appVersion" }

if ($NoBuild) {
    if (-not (Test-Path $apk)) { Fail "No ${apk}: run with -DryRun first" }
    $built = (Read-Text (Join-Path $out 'commit.txt')).Trim()
    if ($built -ne $commit) { Fail "The APK in $out was built from $built, not from the current commit: build again" }
} else {
    Step 'Unit tests and the release APK with our development certificate'
    Invoke-Gradle @('testDebugUnitTest', 'assembleRelease', '-Pbetterauto.certs=dev')
    New-Item -ItemType Directory -Force $out | Out-Null
    Copy-Item (Join-Path $root 'app\build\outputs\apk\release\app-release.apk') $apk -Force
    Write-Text (Join-Path $out 'commit.txt') $commit
}

Step 'Checking the APK'
$signer = Get-ApkSigner $apk
if ($signer -ne $releaseKey) { Fail "Signed with $signer, not with the release key: check BETTERAUTO_STORE_FILE in %USERPROFILE%\.gradle\gradle.properties" }
# Only our development pair, exactly as committed: nothing else from assets/ may go public
$expected = @{}
foreach ($name in $devCertificates) { $expected[$name] = Get-FileSha256 (Join-Path $root "app\src\main\assets\$name") }
$carried = Get-ApkCertificates $apk
$names = @(@($carried.Keys) + @($expected.Keys) | Sort-Object -Unique)
$wrong = @($names | Where-Object { $carried[$_] -ne $expected[$_] })
if ($wrong.Count -gt 0) { Remove-Item $apk; Fail "The APK carries the wrong certificate files ($($wrong -join ', ')): deleted, not published" }

$notes = (Read-Text (Join-Path $root 'docs\release-intro.md')).Trim() + "`n`n**Changes**`n`n" + $english + "`n"
Write-Text (Join-Path $out 'github-notes.md') $notes
Write-Text (Join-Path $out 'changes-en.md') ($english + "`n")
Write-Text (Join-Path $out 'changes-it.md') ($italian + "`n")
$size = '{0:N1} MB' -f ((Get-Item $apk).Length / 1MB)

if ($DryRun) {
    Write-Host "Dry run: $apk ($size), notes in $out\github-notes.md. Nothing published." -ForegroundColor Green
    exit 0
}

Step "Publishing $tag on GitHub"
$ghArguments = @('release', 'create', $tag, $apk, '--target', $commit, '--title', "BetterAuto $version", '--notes-file', (Join-Path $out 'github-notes.md'))
if ($prerelease) { $ghArguments += '--prerelease' }
Push-Location $root
& gh @ghArguments
$ghExit = $LASTEXITCODE
Pop-Location
if ($ghExit -ne 0) { Fail 'gh release create failed, see above' }
git -C $root fetch --quiet --tags origin
Write-Host "Done: BetterAuto $version on GitHub ($size)." -ForegroundColor Green
