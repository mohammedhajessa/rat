@echo off
REM Remove Windows Defender Exclusions for H4CKINTO Android Build
REM This script must be run as Administrator

echo ========================================
echo Removing Windows Defender Exclusions
echo ========================================
echo.

REM Check if running as administrator
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo ERROR: This script must be run as Administrator!
    echo Right-click and select "Run as administrator"
    pause
    exit /b 1
)

set "PROJECT_PATH=%~dp0"
set "APK_PATH=%PROJECT_PATH%app\build\outputs\apk"
set "GRADLE_PATH=%USERPROFILE%\.gradle"

echo Removing exclusions...
powershell -Command "Remove-MpPreference -ExclusionPath '%PROJECT_PATH%'" 2>nul
powershell -Command "Remove-MpPreference -ExclusionPath '%APK_PATH%'" 2>nul
powershell -Command "Remove-MpPreference -ExclusionPath '%GRADLE_PATH%'" 2>nul
powershell -Command "Remove-MpPreference -ExclusionPath '%APK_PATH%\*.apk'" 2>nul

echo Exclusions removed!
pause



