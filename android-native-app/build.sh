#!/bin/bash

# H4CKINTO Android Native App Build Script

echo "Building H4CKINTO Android Native App..."

# Check if we're in the right directory
if [ ! -f "settings.gradle" ]; then
    echo "Error: Please run this script from the android-native-app directory"
    exit 1
fi

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Build debug APK
echo "Building debug APK..."
./gradlew assembleDebug

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
    
    # List APK details
    if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        echo "APK size: $(du -h app/build/outputs/apk/debug/app-debug.apk | cut -f1)"
        echo "APK created: $(date)"
    fi
else
    echo "Build failed!"
    exit 1
fi

echo "Build complete!"
