@echo off
rem Publishes a release on GitHub: tests, signed APK, tag, notes from CHANGELOG.md. Options: -DryRun, -NoBuild
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" %*
exit /b %ERRORLEVEL%
