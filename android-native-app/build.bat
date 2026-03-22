@echo off
REM H4CKINTO Android Native App Build Script for Windows

echo Building H4CKINTO Android Native App...

REM Check if we're in the right directory
if not exist "settings.gradle" (
    echo Error: Please run this script from the android-native-app directory
    pause
    exit /b 1
)

REM Clean previous builds
echo Cleaning previous builds...
call gradlew clean

REM Build debug APK
echo Building debug APK...
call gradlew assembleDebug

REM Check if build was successful
if %errorlevel% equ 0 (
    echo Build successful!
    echo APK location: app\build\outputs\apk\debug\app-debug.apk
    
    REM Check if APK exists and show details
    if exist "app\build\outputs\apk\debug\app-debug.apk" (
        echo APK created: %date% %time%
        dir "app\build\outputs\apk\debug\app-debug.apk"
    )
) else (
    echo Build failed!
    pause
    exit /b 1
)

echo Build complete!
pause
