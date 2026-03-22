@echo off
REM Add Windows Defender Exclusions for H4CKINTO Android Build
REM This script must be run as Administrator

echo ========================================
echo Adding Windows Defender Exclusions
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

echo Adding project folder to Windows Defender exclusions...
set "PROJECT_PATH=%~dp0"
set "APK_PATH=%PROJECT_PATH%app\build\outputs\apk"
set "GRADLE_PATH=%USERPROFILE%\.gradle"

REM Add project folder
powershell -Command "Add-MpPreference -ExclusionPath '%PROJECT_PATH%'" 2>nul
if %errorLevel% equ 0 (
    echo [OK] Project folder added: %PROJECT_PATH%
) else (
    echo [WARNING] Could not add project folder (may already exist)
)

REM Add APK output folder
powershell -Command "Add-MpPreference -ExclusionPath '%APK_PATH%'" 2>nul
if %errorLevel% equ 0 (
    echo [OK] APK output folder added: %APK_PATH%
) else (
    echo [WARNING] Could not add APK folder (may already exist)
)

REM Add Gradle cache folder
powershell -Command "Add-MpPreference -ExclusionPath '%GRADLE_PATH%'" 2>nul
if %errorLevel% equ 0 (
    echo [OK] Gradle cache folder added: %GRADLE_PATH%
) else (
    echo [WARNING] Could not add Gradle cache (may already exist)
)

REM Add specific APK file pattern
powershell -Command "Add-MpPreference -ExclusionPath '%APK_PATH%\*.apk'" 2>nul
if %errorLevel% equ 0 (
    echo [OK] APK file pattern added: %APK_PATH%\*.apk
) else (
    echo [WARNING] Could not add APK pattern (may already exist)
)

echo.
echo ========================================
echo Exclusions added successfully!
echo ========================================
echo.
echo To verify, check Windows Security settings:
echo Settings ^> Update ^& Security ^> Windows Security ^> Virus ^& threat protection
echo ^> Manage settings ^> Exclusions
echo.
pause



