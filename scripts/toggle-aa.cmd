@echo off
rem Switches the phone between the official Android Auto and BetterAuto. Options: -Show, -To BetterAuto^|AndroidAuto, -Serial <id>
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0toggle-aa.ps1" %*
exit /b %ERRORLEVEL%
