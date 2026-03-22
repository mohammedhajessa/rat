@echo off
echo Adding Windows Defender network exclusions for Gradle/Java...
powershell -Command "Add-MpPreference -ExclusionProcess 'java.exe' -ErrorAction SilentlyContinue"
powershell -Command "Add-MpPreference -ExclusionProcess 'javaw.exe' -ErrorAction SilentlyContinue"
powershell -Command "Add-MpPreference -ExclusionProcess 'gradle.exe' -ErrorAction SilentlyContinue"
powershell -Command "Add-MpPreference -ExclusionProcess 'gradlew.bat' -ErrorAction SilentlyContinue"
echo Done!
