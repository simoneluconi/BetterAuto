@echo off
rem A car on this PC: Google's Desktop Head Unit connected to BetterAuto on the phone. Options: -Config <name^|ini>, -InputMode touch^|rotary^|hybrid, -Serial <id>, -Port <n>
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dhu.ps1" %*
exit /b %ERRORLEVEL%
