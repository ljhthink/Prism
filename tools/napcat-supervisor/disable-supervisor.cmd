@echo off
rem Disable the Prism-NapCat-Supervisor scheduled task.
rem
rem Why: the task fires every 2 minutes and launches powershell.exe, which creates
rem a console window that flashes on screen each time -- even though supervisor.ps1
rem now exits immediately (PC protocol was retired in favor of on-phone mode, ADR-069).
rem
rem The task's ACL only grants Write to Administrators, so this script self-elevates.
rem
rem Usage: double-click this file, then confirm the UAC prompt.

setlocal
net session >nul 2>&1
if not "%errorlevel%"=="0" (
    echo Requesting administrator privileges...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b 1
)

echo ==========================================================
echo   Disable task: Prism-NapCat-Supervisor
echo ==========================================================
schtasks /Change /TN "Prism-NapCat-Supervisor" /DISABLE
if errorlevel 1 (
    echo.
    echo FAILED: could not disable the task.
) else (
    echo.
    echo OK: task disabled. The 2-minute popup window will stop.
)

echo.
echo Current state:
schtasks /Query /TN "Prism-NapCat-Supervisor" /FO LIST | findstr /C:"Status"

echo.
echo To restore PC mode later, re-enable it with:
echo   schtasks /Change /TN "Prism-NapCat-Supervisor" /ENABLE
echo To delete it permanently:
echo   schtasks /Delete /TN "Prism-NapCat-Supervisor" /F
echo.
pause
