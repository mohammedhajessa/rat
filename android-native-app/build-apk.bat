@echo off
echo ========================================
echo   Building Release APK with Keystore
echo ========================================
echo.

cd /d "%~dp0"

echo [1/3] Cleaning previous build...
call gradlew.bat clean

echo.
echo [2/3] Building release APK...
call gradlew.bat assembleRelease --no-daemon

echo.
echo [3/3] Checking for APK...
if exist "app\build\outputs\apk\release\app-release.apk" (
    echo.
    echo ========================================
    echo   BUILD SUCCESSFUL!
    echo ========================================
    echo.
    echo APK Location:
    echo   app\build\outputs\apk\release\app-release.apk
    echo.
    for %%F in ("app\build\outputs\apk\release\app-release.apk") do (
        echo APK Size: %%~zF bytes
        echo Created: %%~tF
    )
    echo.
    echo Keystore: mid-release-key.jks
    echo Alias: mid-key
    echo.
) else (
    echo.
    echo ========================================
    echo   BUILD FAILED!
    echo ========================================
    echo.
    echo APK not found. Check the error messages above.
    echo.
    exit /b 1
)

pause


