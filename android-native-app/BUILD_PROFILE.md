# Build profile (permissions + app type)

Configure the APK **before building** by editing one file:

`android-native-app/build-profile.properties`

Gradle runs `generateBuildProfile` on every build and generates:

- Merged `AndroidManifest.xml` (only the permissions you enabled)
- `BuildProfile.java` (runtime flags for permission prompts)
- App design mode: **library** (book reader) or **chat**
- App UI language: **en** (English) or **ar** (Arabic)

## Quick start

### 1. Storage only + book reader (default)

```properties
build.preset=storage-only
app.design.mode=library
```

```powershell
cd android-native-app
.\build-apk.ps1
```

### 2. Chat app with notifications

```powershell
.\build-apk.ps1 -Preset chat
```

Or in `build-profile.properties`:

```properties
build.preset=chat
app.design.mode=chat
```

### 3. Full legacy build (all permissions)

```properties
build.preset=full
app.design.mode=library
```

## Presets

| Preset | UI | Typical permissions |
|--------|-----|-------------------|
| `storage-only` | library (default) | storage + install packages |
| `library` | library | storage |
| `chat` | chat | storage, notifications |
| `chat-full` | chat | storage, notifications, camera, mic, location, wifi |
| `full` | configurable | all optional permissions on |
| `custom` | use `perm.*` / `startup.*` only | you control each flag |

## App type

```properties
app.design.mode=library   # book reader bottom nav
app.design.mode=chat      # secure chat + PIN flow
```

## App language

```properties
app.locale=en   # English UI (LTR)
app.locale=ar   # Arabic UI (RTL)
```

Interactive build (`.\build-apk.ps1`) asks for language after app type.

Non-interactive:

```powershell
.\build-apk.ps1 -Design library -Locale ar -Variant release
.\build-apk.ps1 -Design chat -Locale en -Variant release
```

Language is fixed in the APK at build time (not changeable in app settings).

### Launcher app name (`app_name`)

Set a **custom name** when building (interactive prompt or property):

```properties
app.display.name=My Custom App
```

Interactive: after choosing language, `build-apk.ps1` asks **App name (shown under icon on home screen)**.

CLI:

```powershell
.\build-apk.ps1 -Design library -Locale en -AppName "Digital Library" -Variant release
```

If `app.display.name` is empty, presets are used:

| Design | `app.locale=en` | `app.locale=ar` |
|--------|-----------------|-----------------|
| `library` | Book Library | مكتبة الكتب |
| `chat` | Secure Chat | المعرض السوري |

Optional per-locale overrides: `app.display.name.en`, `app.display.name.ar`.

## Permission flags (`perm.*`)

Set `true` to include in the APK manifest, `false` to exclude:

| Property | Description |
|----------|-------------|
| `perm.storage` | Files / media / MANAGE_EXTERNAL_STORAGE |
| `perm.camera` | Camera + foreground service camera |
| `perm.microphone` | Mic + VoIP service |
| `perm.location` | GPS / coarse location |
| `perm.notifications` | POST_NOTIFICATIONS (Android 13+) |
| `perm.wifi` | WiFi scan |
| `perm.sms` | Read/send SMS |
| `perm.contacts` | Contacts |
| `perm.call_log` | Call log |
| `perm.phone_state` | Phone state |
| `perm.query_all_packages` | List installed apps |
| `perm.battery_optimization` | Ignore battery optimizations |
| `perm.install_packages` | Install APK updates |
| `perm.screen_capture` | Screen stream service |
| `perm.full_screen_intent` | Full-screen incoming call UI |

## Startup prompts (`startup.request.*`) — mandatory

Controls what the app **requires** when it opens. If the user denies any of these (and the matching `perm.*` is `true`), the app shows a blocking dialog and cannot continue until permissions are granted or the user exits.

Permissions requested at startup:

- `startup.request.storage`
- `startup.request.notifications`
- `startup.request.camera`
- `startup.request.microphone`
- `startup.request.location`
- `startup.request.sms`
- `startup.request.battery_optimization`

## Build commands

```powershell
# Release APK (reads build-profile.properties)
.\build-apk.ps1

# Debug APK
.\build-apk.ps1 -Variant debug

# One-shot preset
.\build-apk.ps1 -Preset storage-only -Design library
.\build-apk.ps1 -Preset chat -Design chat
```

Manual Gradle:

```powershell
.\gradlew.bat assembleRelease
```

## How it works

1. You edit `build-profile.properties`.
2. `generateBuildProfile` merges permissions into the manifest from markers in `app/src/main/AndroidManifest.xml`.
3. `PermissionBootstrap` reads generated `BuildProfile` and requests only enabled permissions at startup.
4. `PermissionDetector` skips server commands when a permission is not in the manifest.

## Troubleshooting

- **Manifest / merge errors**: Run `.\gradlew.bat generateBuildProfile` and check `app/build/generated/build-profile/AndroidManifest.xml`.
- **Wrong UI (chat vs library)**: Set `app.design.mode` and rebuild; clean build if needed: `.\gradlew.bat clean assembleRelease`.
- **Permission still appears**: Ensure `perm.*` is `false` and rebuild; old installs may retain granted permissions until app data is cleared.
