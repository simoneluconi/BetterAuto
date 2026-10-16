@echo off
rem Builds BetterAuto and installs it on the connected phone. Options: -Tests, -NoLaunch, -Serial <id>
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
exit /b %ERRORLEVEL%
