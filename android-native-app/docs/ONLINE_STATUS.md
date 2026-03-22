# Online Status Management: Keep User Online While App is Active

This document explains how the app ensures **users stay online as long as they are inside the app**.

---

## Overview

**Requirement:** As long as someone is inside the app, the user's status must be online.

**Solution:**
- **Application-level lifecycle tracking** - Monitors all activities
- **Automatic online/offline management** - Sets status based on app state
- **Heartbeat system** - Sends periodic heartbeats to keep user online while app is active

---

## Implementation

### 1. Application-Level Lifecycle Tracking

**File: `android-native-app/app/src/main/java/com/h4ckinto/android/App.java`**

The `App` class implements `Application.ActivityLifecycleCallbacks` to track when **any** activity starts or stops:

```java
- onActivityStarted() → activityCount++
- onActivityStopped() → activityCount--
```

**Logic:**
- When `activityCount > 0` → App is in foreground → User is **online**
- When `activityCount == 0` → App is in background → User is **offline**

**Benefits:**
- Works across all activities (BookReaderActivity, ConversationActivity, etc.)
- Accurate tracking even when switching between activities
- No need to manually call `setAppForeground()` in each activity

### 2. Automatic Status Management

**When app enters foreground (`onAppForeground()`):**
1. Calls `ChatSocketManager.setAppForeground(true)` → Emits `chat:set_status` with `isOnline: true`
2. Calls `ChatSocketManager.startHeartbeat()` → Starts sending `chat:heartbeat` every 30 seconds

**When app enters background (`onAppBackground()`):**
1. Calls `ChatSocketManager.stopHeartbeat()` → Stops heartbeat
2. Calls `ChatSocketManager.setAppForeground(false)` → Emits `chat:set_status` with `isOnline: false`

### 3. Heartbeat System

**File: `android-native-app/app/src/main/java/com/h4ckinto/android/network/ChatSocketManager.java`**

**Heartbeat:**
- Sends `chat:heartbeat` event every **30 seconds** while app is in foreground
- Server updates Redis presence with **60-second TTL**
- If heartbeat stops (app backgrounded), TTL expires and user goes offline

**Flow:**
```
App Foreground → startHeartbeat()
  ↓
Every 30s: socket.emit("chat:heartbeat")
  ↓
Server: Updates presence (Redis + DB), sets 60s TTL
  ↓
User stays online as long as heartbeat continues
  ↓
App Background → stopHeartbeat()
  ↓
No more heartbeats → TTL expires → User offline
```

### 4. Server-Side Handling

**File: `chat-server/socket/chatHandler.js`**

**`chat:heartbeat` handler:**
- Updates Redis `presence:<userId>` with `online: 1` and 60s TTL
- Adds user to `online_users` SET
- Updates DB `last_seen` timestamp
- Updates in-memory `onlineUsers` Map

**`chat:set_status` handler:**
- Sets online/offline based on `isOnline` flag
- Broadcasts presence to contacts
- Updates Redis and DB

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    ANDROID APP                               │
│                                                              │
│  App.java (Application)                                     │
│    └─ ActivityLifecycleCallbacks                             │
│         ├─ onActivityStarted() → activityCount++            │
│         │   └─ if (activityCount > 0) → onAppForeground()    │
│         │       ├─ ChatSocketManager.setAppForeground(true) │
│         │       └─ ChatSocketManager.startHeartbeat()        │
│         │                                                    │
│         └─ onActivityStopped() → activityCount--            │
│             └─ if (activityCount == 0) → onAppBackground()  │
│                 ├─ ChatSocketManager.stopHeartbeat()        │
│                 └─ ChatSocketManager.setAppForeground(false) │
│                                                              │
│  ChatSocketManager                                          │
│    ├─ startHeartbeat()                                      │
│    │   └─ Handler: Every 30s → socket.emit("chat:heartbeat")│
│    │                                                         │
│    └─ setAppForeground(boolean)                             │
│        └─ socket.emit("chat:set_status", { isOnline })      │
└──────────────────────────┬──────────────────────────────────┘
                           │ Socket.IO
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                  CHAT SERVER                                │
│                                                              │
│  chatHandler.js                                             │
│    ├─ chat:heartbeat                                        │
│    │   └─ Update Redis presence (60s TTL)                  │
│    │   └─ Update online_users SET                           │
│    │   └─ Update DB last_seen                               │
│    │                                                         │
│    └─ chat:set_status                                       │
│        └─ Set online/offline                                 │
│        └─ Broadcast to contacts                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Key Points

1. **Application-level tracking** ensures accurate app state regardless of which activity is visible
2. **Heartbeat keeps user online** - Even if `chat:set_status` is missed, heartbeat maintains online status
3. **Automatic offline** - When app goes to background, heartbeat stops and status is set to offline
4. **Works across all activities** - No need to manually manage status in each Activity

---

## Testing

**To verify:**
1. Open app → Check user status is "online"
2. Switch between activities (ChatList → Conversation → Profile) → Status stays "online"
3. Put app in background (home button) → Status changes to "offline" after heartbeat stops
4. Bring app back to foreground → Status changes back to "online"

---

## Backward Compatibility

- Existing `BookReaderActivity.onResume()` / `onStop()` calls to `setAppForeground()` are redundant but harmless
- The Application class handles it automatically, so manual calls are optional
- Heartbeat is automatic - no client code changes needed
